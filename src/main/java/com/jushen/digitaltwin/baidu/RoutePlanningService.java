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
        List<double[]> exactCoordinates = waypoints.isEmpty()
                ? withExactEndpoints(result.path, origin, destination)
                : withExactStops(result.path, origin, waypoints, destination);
        return PlannedRoute.success(
                "baidu", exactCoordinates,
                waypoints, result.totalDistance, result.totalDuration);
    }

    private PlannedRoute planAmap(double[] origin, double[] destination, List<double[]> waypoints) {
        if (!awaitProviderRequestSlot()) return PlannedRoute.unavailable("amap: interrupted");
        AmapRoutePlanService.RoutePlanResult result = waypoints.isEmpty()
                ? amap.planRoute(origin[1], origin[0], destination[1], destination[0])
                : amap.planRoute(origin[1], origin[0], destination[1], destination[0], waypoints);
        if (!result.success || result.path == null || result.path.size() < 2) {
            return PlannedRoute.unavailable("amap: " + result.error);
        }
        List<double[]> exactCoordinates = waypoints.isEmpty()
                ? withExactEndpoints(result.path, origin, destination)
                : withExactStops(result.path, origin, waypoints, destination);
        return PlannedRoute.success(
                "amap", exactCoordinates,
                waypoints, result.totalDistance, result.totalDuration);
    }

    private List<double[]> withExactEndpoints(List<double[]> path, double[] origin, double[] destination) {
        List<double[]> result = new ArrayList<>((path == null ? 0 : path.size()) + 2);
        result.add(new double[]{origin[0], origin[1]});
        if (path != null) {
            for (double[] coordinate : path) {
                if (validCoordinate(coordinate)) result.add(new double[]{coordinate[0], coordinate[1]});
            }
        }
        result.add(new double[]{destination[0], destination[1]});
        return result;
    }

    /** 将每个业务节点按供应商路线中的最近顺序位置钉入坐标，避免首轮只保留总起终点。 */
    private List<double[]> withExactStops(
            List<double[]> path,
            double[] origin,
            List<double[]> waypoints,
            double[] destination
    ) {
        List<double[]> validPath = path == null ? List.of() : path.stream()
                .filter(this::validCoordinate)
                .map(point -> new double[]{point[0], point[1]})
                .toList();
        List<double[]> result = new ArrayList<>(validPath.size() + waypoints.size() + 2);
        result.add(new double[]{origin[0], origin[1]});
        int cursor = 0;
        for (double[] waypoint : waypoints) {
            int nearest = nearestForwardIndex(validPath, cursor, waypoint);
            if (nearest >= cursor) {
                for (int index = cursor; index <= nearest; index++) addDistinct(result, validPath.get(index));
                cursor = nearest + 1;
            }
            addDistinct(result, waypoint);
        }
        for (int index = cursor; index < validPath.size(); index++) addDistinct(result, validPath.get(index));
        addDistinct(result, destination);
        return List.copyOf(result);
    }

    private int nearestForwardIndex(List<double[]> path, int fromIndex, double[] target) {
        if (path == null || path.isEmpty() || fromIndex >= path.size()) return -1;
        int nearest = fromIndex;
        double best = Double.MAX_VALUE;
        for (int index = Math.max(0, fromIndex); index < path.size(); index++) {
            double dx = path.get(index)[0] - target[0];
            double dy = path.get(index)[1] - target[1];
            double distanceSquared = dx * dx + dy * dy;
            if (distanceSquared < best) {
                best = distanceSquared;
                nearest = index;
            }
        }
        return nearest;
    }

    private void addDistinct(List<double[]> coordinates, double[] coordinate) {
        if (!validCoordinate(coordinate)) return;
        if (!coordinates.isEmpty()) {
            double[] previous = coordinates.get(coordinates.size() - 1);
            if (Math.abs(previous[0] - coordinate[0]) < 0.0000001
                    && Math.abs(previous[1] - coordinate[1]) < 0.0000001) return;
        }
        coordinates.add(new double[]{coordinate[0], coordinate[1]});
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
            List<double[]> matchingCoordinates,
            double distanceKm,
            long durationMs,
            String error
    ) {
        static PlannedRoute success(String provider, List<double[]> coordinates, int distanceMeters, int durationSeconds) {
            return success(provider, coordinates, List.of(), distanceMeters, durationSeconds);
        }

        static PlannedRoute success(
                String provider,
                List<double[]> coordinates,
                List<double[]> requiredAnchors,
                int distanceMeters,
                int durationSeconds
        ) {
            List<double[]> copy = new ArrayList<>(coordinates.size());
            for (double[] coordinate : coordinates) {
                if (coordinate != null && coordinate.length >= 2) {
                    copy.add(new double[]{coordinate[0], coordinate[1]});
                }
            }
            List<double[]> matching = List.copyOf(copy);
            return new PlannedRoute(true, provider,
                    simplifyForRenderingPreservingAnchors(matching, requiredAnchors, 240), matching,
                    Math.max(0, distanceMeters) / 1000.0,
                    Math.max(0L, durationSeconds) * 1000L, null);
        }

        public static PlannedRoute unavailable(String error) {
            return new PlannedRoute(false, "fallback", List.of(), List.of(), 0, 0, error);
        }

        /**
         * 仅生成前端渲染折线。判偏、投影和进度必须使用 matchingCoordinates，不能使用这里的结果。
         * 先用 Douglas-Peucker 保留弯道，再二分误差阈值，直到满足传输点数上限。
         */
        public static List<double[]> simplifyForRendering(List<double[]> coordinates, int maxPoints) {
            if (coordinates.size() <= maxPoints) return List.copyOf(coordinates);
            if (maxPoints < 2) return List.of(copyOf(coordinates.get(0)));

            double lowKm = 0;
            double highKm = 0.01;
            List<double[]> simplified = douglasPeucker(coordinates, highKm);
            while (simplified.size() > maxPoints && highKm < 1_000) {
                highKm *= 2;
                simplified = douglasPeucker(coordinates, highKm);
            }
            for (int iteration = 0; iteration < 32; iteration++) {
                double middleKm = (lowKm + highKm) / 2;
                List<double[]> candidate = douglasPeucker(coordinates, middleKm);
                if (candidate.size() > maxPoints) {
                    lowKm = middleKm;
                } else {
                    highKm = middleKm;
                    simplified = candidate;
                }
            }
            return List.copyOf(simplified);
        }

        private static List<double[]> simplifyForRenderingPreservingAnchors(
                List<double[]> coordinates,
                List<double[]> requiredAnchors,
                int maxPoints
        ) {
            if (requiredAnchors == null || requiredAnchors.isEmpty() || coordinates.size() <= maxPoints) {
                return simplifyForRendering(coordinates, maxPoints);
            }
            List<Integer> anchorIndexes = new ArrayList<>();
            anchorIndexes.add(0);
            int cursor = 1;
            for (double[] anchor : requiredAnchors) {
                for (int index = cursor; index < coordinates.size() - 1; index++) {
                    double[] point = coordinates.get(index);
                    if (Math.abs(point[0] - anchor[0]) < 0.0000001
                            && Math.abs(point[1] - anchor[1]) < 0.0000001) {
                        anchorIndexes.add(index);
                        cursor = index + 1;
                        break;
                    }
                }
            }
            anchorIndexes.add(coordinates.size() - 1);
            if (anchorIndexes.size() <= 2) return simplifyForRendering(coordinates, maxPoints);

            double lowKm = 0;
            double highKm = 0.01;
            List<double[]> simplified = simplifySections(coordinates, anchorIndexes, highKm);
            while (simplified.size() > maxPoints && highKm < 1_000) {
                highKm *= 2;
                simplified = simplifySections(coordinates, anchorIndexes, highKm);
            }
            for (int iteration = 0; iteration < 32; iteration++) {
                double middleKm = (lowKm + highKm) / 2;
                List<double[]> candidate = simplifySections(coordinates, anchorIndexes, middleKm);
                if (candidate.size() > maxPoints) {
                    lowKm = middleKm;
                } else {
                    highKm = middleKm;
                    simplified = candidate;
                }
            }
            return List.copyOf(simplified);
        }

        private static List<double[]> simplifySections(
                List<double[]> coordinates,
                List<Integer> anchorIndexes,
                double toleranceKm
        ) {
            boolean[] kept = new boolean[coordinates.size()];
            for (int index : anchorIndexes) kept[index] = true;
            for (int index = 1; index < anchorIndexes.size(); index++) {
                simplifySection(coordinates, anchorIndexes.get(index - 1), anchorIndexes.get(index), toleranceKm, kept);
            }
            List<double[]> result = new ArrayList<>();
            for (int index = 0; index < coordinates.size(); index++) {
                if (kept[index]) result.add(copyOf(coordinates.get(index)));
            }
            return result;
        }

        private static List<double[]> douglasPeucker(List<double[]> coordinates, double toleranceKm) {
            boolean[] kept = new boolean[coordinates.size()];
            kept[0] = true;
            kept[coordinates.size() - 1] = true;
            simplifySection(coordinates, 0, coordinates.size() - 1, toleranceKm, kept);
            List<double[]> result = new ArrayList<>();
            for (int i = 0; i < coordinates.size(); i++) {
                if (kept[i]) result.add(copyOf(coordinates.get(i)));
            }
            return result;
        }

        private static void simplifySection(
                List<double[]> coordinates, int start, int end, double toleranceKm, boolean[] kept) {
            if (end <= start + 1) return;
            double maximumKm = -1;
            int farthest = -1;
            for (int i = start + 1; i < end; i++) {
                double distanceKm = perpendicularDistanceKm(
                        coordinates.get(i), coordinates.get(start), coordinates.get(end));
                if (distanceKm > maximumKm) {
                    maximumKm = distanceKm;
                    farthest = i;
                }
            }
            if (farthest >= 0 && maximumKm > toleranceKm) {
                kept[farthest] = true;
                simplifySection(coordinates, start, farthest, toleranceKm, kept);
                simplifySection(coordinates, farthest, end, toleranceKm, kept);
            }
        }

        private static double perpendicularDistanceKm(double[] point, double[] start, double[] end) {
            double referenceLatRad = Math.toRadians((start[1] + end[1] + point[1]) / 3.0);
            double x = (point[0] - start[0]) * 111.320 * Math.cos(referenceLatRad);
            double y = (point[1] - start[1]) * 110.574;
            double dx = (end[0] - start[0]) * 111.320 * Math.cos(referenceLatRad);
            double dy = (end[1] - start[1]) * 110.574;
            double lengthSquared = dx * dx + dy * dy;
            if (lengthSquared <= 1e-12) return Math.hypot(x, y);
            double t = Math.max(0, Math.min(1, (x * dx + y * dy) / lengthSquared));
            return Math.hypot(x - t * dx, y - t * dy);
        }

        private static double[] copyOf(double[] coordinate) {
            return new double[]{coordinate[0], coordinate[1]};
        }
    }

    public record RouteRequest(double[] origin, double[] destination) {}

    @PreDestroy
    void shutdown() {
        preloadExecutor.shutdownNow();
    }

    private record CacheEntry(PlannedRoute route, long createdAt) {}
}
