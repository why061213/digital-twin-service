package com.jushen.digitaltwin.baidu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;

/**
 * 统一驾车规划入口。百度优先、高德回退，并按起终点缓存稳定结果。
 */
@Service
public class RoutePlanningService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanningService.class);
    private static final long DEFAULT_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    private final BaiduRoutePlanService baidu;
    private final AmapRoutePlanService amap;
    private final boolean enabled;
    private final long cacheTtlMs;
    private final long minRequestIntervalMs;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Object providerRequestLock = new Object();
    private long nextProviderRequestAtMs;
    private final ExecutorService preloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "route-plan-preload");
        thread.setDaemon(true);
        return thread;
    });

    public RoutePlanningService(
            BaiduRoutePlanService baidu,
            AmapRoutePlanService amap,
            @Value("${dashboard.route-plan.enabled:true}") boolean enabled,
            @Value("${dashboard.route-plan.cache-ttl-ms:86400000}") long cacheTtlMs,
            @Value("${dashboard.route-plan.min-request-interval-ms:400}") long minRequestIntervalMs
    ) {
        this.baidu = baidu;
        this.amap = amap;
        this.enabled = enabled;
        this.cacheTtlMs = cacheTtlMs > 0 ? cacheTtlMs : DEFAULT_CACHE_TTL_MS;
        this.minRequestIntervalMs = Math.max(0, minRequestIntervalMs);
    }

    public PlannedRoute plan(double[] origin, double[] destination) {
        return plan(origin, destination, List.of());
    }

    public PlannedRoute plan(double[] origin, double[] destination, List<double[]> waypoints) {
        if (!enabled || !validCoordinate(origin) || !validCoordinate(destination)) {
            return PlannedRoute.unavailable("disabled-or-invalid-coordinates");
        }
        List<double[]> validWaypoints = sanitizeWaypoints(waypoints);

        String key = routeKey(origin, destination, validWaypoints);
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(key);
        if (cached != null && now - cached.createdAt() <= cacheTtlMs) {
            return cached.route();
        }

        PlannedRoute planned = planBaidu(origin, destination, validWaypoints);
        if (!planned.success()) planned = planAmap(origin, destination, validWaypoints);
        if (planned.success()) {
            cache.put(key, new CacheEntry(planned, now));
        } else {
            log.warn("[RoutePlan] all providers failed: origin={}, destination={}, reason={}",
                    coordinateText(origin), coordinateText(destination), planned.error());
        }
        return planned;
    }

    public void preload(List<RouteRequest> requests) {
        if (!enabled || requests == null || requests.isEmpty()) return;
        Map<String, RouteRequest> unique = new java.util.LinkedHashMap<>();
        for (RouteRequest request : requests) {
            if (request == null || !validCoordinate(request.origin()) || !validCoordinate(request.destination())) continue;
            unique.putIfAbsent(routeKey(request.origin(), request.destination()), request);
        }
        List<CompletableFuture<Void>> tasks = unique.values().stream()
                .filter(request -> !hasFreshCache(request.origin(), request.destination()))
                .map(request -> CompletableFuture.runAsync(
                        () -> plan(request.origin(), request.destination()), preloadExecutor))
                .toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        if (!tasks.isEmpty()) {
            log.info("[RoutePlan] preload completed: uniqueRoutes={}, requestedRoutes={}", tasks.size(), requests.size());
        }
    }

    private boolean hasFreshCache(double[] origin, double[] destination) {
        CacheEntry entry = cache.get(routeKey(origin, destination));
        return entry != null && System.currentTimeMillis() - entry.createdAt() <= cacheTtlMs;
    }

    private PlannedRoute planBaidu(double[] origin, double[] destination, List<double[]> waypoints) {
        if (!awaitProviderRequestSlot()) return PlannedRoute.unavailable("baidu: interrupted");
        BaiduRoutePlanService.RoutePlanResult result = waypoints.isEmpty()
                ? baidu.planRoute(origin[1], origin[0], destination[1], destination[0])
                : baidu.planRoute(origin[1], origin[0], destination[1], destination[0], waypoints);
        if (!result.success || result.path == null || result.path.size() < 2) {
            return PlannedRoute.unavailable("baidu: " + result.error);
        }
        return PlannedRoute.success(
                "baidu", withExactEndpoints(result.path, origin, destination), result.totalDistance, result.totalDuration);
    }

    private PlannedRoute planAmap(double[] origin, double[] destination, List<double[]> waypoints) {
        if (!awaitProviderRequestSlot()) return PlannedRoute.unavailable("amap: interrupted");
        AmapRoutePlanService.RoutePlanResult result = waypoints.isEmpty()
                ? amap.planRoute(origin[1], origin[0], destination[1], destination[0])
                : amap.planRoute(origin[1], origin[0], destination[1], destination[0], waypoints);
        if (!result.success || result.path == null || result.path.size() < 2) {
            return PlannedRoute.unavailable("amap: " + result.error);
        }
        return PlannedRoute.success(
                "amap", withExactEndpoints(result.path, origin, destination), result.totalDistance, result.totalDuration);
    }

    private List<double[]> withExactEndpoints(List<double[]> path, double[] origin, double[] destination) {
        List<double[]> result = new ArrayList<>(path.size() + 2);
        result.add(new double[]{origin[0], origin[1]});
        for (double[] coordinate : path) {
            if (validCoordinate(coordinate)) result.add(new double[]{coordinate[0], coordinate[1]});
        }
        result.add(new double[]{destination[0], destination[1]});
        return result;
    }

    private boolean awaitProviderRequestSlot() {
        synchronized (providerRequestLock) {
            long waitMs = nextProviderRequestAtMs - System.currentTimeMillis();
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            nextProviderRequestAtMs = System.currentTimeMillis() + minRequestIntervalMs;
            return true;
        }
    }

    private boolean validCoordinate(double[] coordinate) {
        return coordinate != null && coordinate.length >= 2
                && Double.isFinite(coordinate[0]) && Double.isFinite(coordinate[1])
                && coordinate[0] >= 72 && coordinate[0] <= 136
                && coordinate[1] >= 3 && coordinate[1] <= 54;
    }

    private String routeKey(double[] origin, double[] destination) {
        return routeKey(origin, destination, List.of());
    }

    private String routeKey(double[] origin, double[] destination, List<double[]> waypoints) {
        String waypointKey = waypoints.stream()
                .map(this::coordinateText)
                .collect(java.util.stream.Collectors.joining("|"));
        return String.format(Locale.ROOT, "%.5f,%.5f>%.5f,%.5f",
                origin[0], origin[1], destination[0], destination[1]) + "@" + waypointKey;
    }

    private List<double[]> sanitizeWaypoints(List<double[]> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) return List.of();
        List<double[]> result = new ArrayList<>();
        for (double[] waypoint : waypoints) {
            if (!validCoordinate(waypoint)) continue;
            if (!result.isEmpty() && coordinateText(result.get(result.size() - 1)).equals(coordinateText(waypoint))) {
                continue;
            }
            result.add(new double[]{waypoint[0], waypoint[1]});
            if (result.size() == 10) break;
        }
        return List.copyOf(result);
    }

    private String coordinateText(double[] coordinate) {
        return String.format(Locale.ROOT, "%.6f,%.6f", coordinate[0], coordinate[1]);
    }

    public record PlannedRoute(
            boolean success,
            String provider,
            List<double[]> coordinates,
            double distanceKm,
            long durationMs,
            String error
    ) {
        static PlannedRoute success(String provider, List<double[]> coordinates, int distanceMeters, int durationSeconds) {
            List<double[]> copy = new ArrayList<>(coordinates.size());
            for (double[] coordinate : coordinates) {
                if (coordinate != null && coordinate.length >= 2) {
                    copy.add(new double[]{coordinate[0], coordinate[1]});
                }
            }
            return new PlannedRoute(true, provider, simplify(copy, 240),
                    Math.max(0, distanceMeters) / 1000.0,
                    Math.max(0L, durationSeconds) * 1000L, null);
        }

        public static PlannedRoute unavailable(String error) {
            return new PlannedRoute(false, "fallback", List.of(), 0, 0, error);
        }

        private static List<double[]> simplify(List<double[]> coordinates, int maxPoints) {
            if (coordinates.size() <= maxPoints) return List.copyOf(coordinates);
            List<double[]> sampled = new ArrayList<>(maxPoints);
            for (int i = 0; i < maxPoints; i++) {
                int index = (int) Math.round(i * (coordinates.size() - 1.0) / (maxPoints - 1.0));
                double[] coordinate = coordinates.get(index);
                sampled.add(new double[]{coordinate[0], coordinate[1]});
            }
            return List.copyOf(sampled);
        }
    }

    public record RouteRequest(double[] origin, double[] destination) {}

    @PreDestroy
    void shutdown() {
        preloadExecutor.shutdownNow();
    }

    private record CacheEntry(PlannedRoute route, long createdAt) {}
}
