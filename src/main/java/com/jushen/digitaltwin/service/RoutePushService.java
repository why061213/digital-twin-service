package com.jushen.digitaltwin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.grouping.GroupSummary;
import com.jushen.digitaltwin.grouping.GroupingContext;
import com.jushen.digitaltwin.grouping.OrderAwareRouteInfo;
import com.jushen.digitaltwin.grouping.PathAwareRouteInfo;
import com.jushen.digitaltwin.grouping.RouteInfo;
import com.jushen.digitaltwin.grouping.RouteGroupingEngine;
import com.jushen.digitaltwin.grouping.RouteGroupingResult;
import com.jushen.digitaltwin.model.City;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class RoutePushService {

    private static final Logger log = LoggerFactory.getLogger(RoutePushService.class);
    private static final String STARTUP_TEST_PLATE = "粤E54410";
    private final Path tokenCachePath = Path.of(System.getProperty("java.io.tmpdir"), "jushen_token_cache.json");

    private final RealtimeWebSocketHandler webSocketHandler;
    private final SimulationDataFactory dataFactory;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService bulkDispatchExecutor = Executors.newSingleThreadScheduledExecutor((runnable) -> {
        Thread thread = new Thread(runnable, "bulk-dispatch-simulator");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ScheduledRoute> activeRoutes = new ConcurrentHashMap<>();
    private final Map<String, DisplayGroupLock> displayGroupLocks = new ConcurrentHashMap<>();
    private final Map<String, PositionSample> lastPositionSamples = new ConcurrentHashMap<>();
    private final boolean passivePositionPushEnabled;
    private final String simulationProfile;
    private final String externalPositionUrl;
    private final double testSimulationSpeedKmh;
    private final double realSimulationSpeedKmh;
    private final int groupSize;
    private final String defaultGroupStrategy;
    private final RouteGroupingEngine routeGroupingEngine;
    private final String externalPositionToken;
    private final int externalPositionBatchSize;
    private final ConcurrentHashMap<String, String> lineIdPlateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lineIdCarIdMap = new ConcurrentHashMap<>();
    private final String testPlate;
    private final String testCarId;
    private final boolean testOnStartup;
    private final boolean authEnabled;
    private final String authUrl;
    private final String authId;
    private final String authSecret;
    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private volatile long tokenExpiresAt = 0;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private final boolean tokenCacheEnabled;
    private final String tokenCachePathStr;

    public RoutePushService(
            RealtimeWebSocketHandler webSocketHandler,
            SimulationDataFactory dataFactory,
            ObjectMapper objectMapper,
            RouteGroupingEngine routeGroupingEngine,
            @Value("${dashboard.route.passive-position-push-enabled:false}") boolean passivePositionPushEnabled,
            @Value("${dashboard.route.simulation-profile:test}") String simulationProfile,
            @Value("${dashboard.route.external-position-url:}") String externalPositionUrl,
            @Value("${dashboard.route.test.simulation-speed-kmh:36000}") double testSimulationSpeedKmh,
            @Value("${dashboard.route.real.simulation-speed-kmh:80}") double realSimulationSpeedKmh,
            @Value("${dashboard.route.group-size:5}") int groupSize,
            @Value("${dashboard.route.default-group-strategy:sequential}") String defaultGroupStrategy,
            @Value("${dashboard.route.external-position-token:}") String externalPositionToken,
            @Value("${dashboard.route.external-position-batch-size:50}") int externalPositionBatchSize,
            @Value("${dashboard.route.test-plate:}") String testPlate,
            @Value("${dashboard.route.test-car-id:}") String testCarId,
            @Value("${dashboard.route.test-on-startup:false}") boolean testOnStartup,
            @Value("${dashboard.route.auth-enabled:false}") boolean authEnabled,
            @Value("${dashboard.route.auth-url:}") String authUrl,
            @Value("${dashboard.route.auth-id:}") String authId,
            @Value("${dashboard.route.auth-secret:}") String authSecret,
            @Value("${dashboard.route.token-cache-enabled:true}") boolean tokenCacheEnabled,
            @Value("${dashboard.route.token-cache-path:target/local-cache/token-cache.json}") String tokenCachePathStr
    ) {
        this.webSocketHandler = webSocketHandler;
        this.dataFactory = dataFactory;
        this.objectMapper = objectMapper;
        this.routeGroupingEngine = routeGroupingEngine;
        this.passivePositionPushEnabled = passivePositionPushEnabled;
        this.simulationProfile = simulationProfile;
        this.externalPositionUrl = externalPositionUrl;
        this.testSimulationSpeedKmh = testSimulationSpeedKmh;
        this.realSimulationSpeedKmh = realSimulationSpeedKmh;
        this.groupSize = Math.max(1, groupSize);
        this.defaultGroupStrategy = defaultGroupStrategy;
        this.externalPositionToken = externalPositionToken;
        this.externalPositionBatchSize = externalPositionBatchSize;
        this.testPlate = testPlate;
        this.testCarId = testCarId;
        this.testOnStartup = testOnStartup;
        this.authEnabled = authEnabled;
        this.authUrl = authUrl;
        this.authId = authId;
        this.authSecret = authSecret;
        this.tokenCacheEnabled = tokenCacheEnabled;
        this.tokenCachePathStr = tokenCachePathStr;
    }


    private Path resolveTokenCachePath() {
        return Path.of(tokenCachePathStr);
    }

//    public ProviderPosition queryPositionByCarId(String carId) {
//        return fetchPositionByCarId(carId);
//    }

    private String loadTokenFromFile() {
        if (!tokenCacheEnabled) return null;
        Path path = resolveTokenCachePath();
        if (!Files.exists(path)) return null;
        try {
            String content = Files.readString(path);
            Map<String, Object> data = objectMapper.readValue(content, new TypeReference<>() {});
            String token = (String) data.get("token");
            long expiresAt = ((Number) data.get("expiresAt")).longValue();
            if (System.currentTimeMillis() < expiresAt - 300_000) {
                cachedToken.set(token);
                tokenExpiresAt = expiresAt;
                log.info("Token loaded from file cache, expires at {}", expiresAt);
                return token;
            }
        } catch (Exception e) {
            log.warn("Failed to load token cache file", e);
        }
        return null;
    }

    private void saveTokenToFile(String token, long expiresAt) {
        if (!tokenCacheEnabled) return;
        Path path = resolveTokenCachePath();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", token);
            data.put("expiresAt", expiresAt);
            Files.writeString(path, objectMapper.writeValueAsString(data));
            log.debug("Token cache saved to {}", path);
        } catch (IOException e) {
            log.warn("Failed to save token cache", e);
        }
    }


    /**
     * 获取有效的 accessToken，如果需要则自动获取或刷新。
     */
    private String getAccessToken() throws IOException, InterruptedException {
        if (!authEnabled) {
            return externalPositionToken;
        }

        long now = System.currentTimeMillis();
        String token = cachedToken.get();

        // 1) 若内存无 token，尝试从文件加载
        if (token == null) {
            token = loadTokenFromFile();
            if (token != null) {
                return token;
            }
        }

        // 2) 内存中的 token 是否有效
        if (token != null && now < tokenExpiresAt - 300_000) {
            log.info("Using cached token (memory)");
            return token;
        }

        tokenLock.lock();
        try {
            // 双重检查
            token = cachedToken.get();
            if (token != null && now < tokenExpiresAt - 300_000) {
                log.info("Using cached token (memory)");
                return token;
            }

            // 3) 登录获取新 token
            Map<String, String> body = new LinkedHashMap<>();
            body.put("id", authId);
            body.put("secret", authSecret);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(authUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Token fetch failed with status " + response.statusCode());
            }

            Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (!(result.get("code") instanceof Number code) || code.intValue() != 200) {
                throw new RuntimeException("Token API returned error: " + result);
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            String newToken = (String) data.get("token");
            int expiresIn = ((Number) data.get("expires_in")).intValue(); // 秒

            cachedToken.set(newToken);
            tokenExpiresAt = now + expiresIn * 1000L;
            log.info("New access token obtained, expires at {}", tokenExpiresAt);

            // 4) 保存到文件（供下次启动使用）
            saveTokenToFile(newToken, tokenExpiresAt);

            return newToken;
        } finally {
            tokenLock.unlock();
        }
    }


    public synchronized Map<String, Object> dispatchRandomRoute() {
        cleanupExpiredRoutes(System.currentTimeMillis());

        City from = dataFactory.randomCity();
        City to = dataFactory.randomDifferentCity(from);
        String lineId = UUID.randomUUID().toString();
        List<double[]> coordinates = buildRandomRoadCoordinates(from, to);
        double routeLengthKm = pathLengthKm(coordinates);
        double speedKmh = simulationSpeedKmh();
        long travelDurationMs = Math.max(60_000L, Math.round(routeLengthKm / speedKmh * 3_600_000));
        ScheduledRoute route = new ScheduledRoute(
                lineId,
                lineId,
                lineId,
                "临时运输任务",
                ThreadLocalRandom.current().nextInt(18, 46),
                1,
                from.name(),
                to.name(),
                coordinates,
                pathKey(from.name(), to.name(), coordinates),
                System.currentTimeMillis(),
                routeLengthKm,
                speedKmh,
                travelDurationMs
        );
        // 随机车牌（用于测试）
        String plate = dataFactory.randomPlate();
        lineIdPlateMap.put(lineId, plate);

        // 如果将来有真实的车辆ID，可以从订单信息中获取，然后存入 lineIdCarIdMap
        // String carId = order.getCarId();   // 假设有
        // if (carId != null) lineIdCarIdMap.put(lineId, carId);
        activeRoutes.put(lineId, route);

        Map<String, Object> message = routeMessage(route, true);
        webSocketHandler.broadcast(message);
        log.debug("Dispatched road route: {} -> {}, lineId: {}", from.name(), to.name(), lineId);
        return message;
    }

    public synchronized Map<String, Object> dispatchBulkOrder(int vehicleCount) {
        cleanupExpiredRoutes(System.currentTimeMillis());

        int count = Math.max(1, Math.min(vehicleCount, 80));
        City from = dataFactory.randomCity();
        City to = dataFactory.randomDifferentCity(from);
        String orderId = "BULK-" + System.currentTimeMillis();
        String orderName = "大宗运输订单";
        int totalTons = count * ThreadLocalRandom.current().nextInt(22, 38);
        /*

        // 大宗订单默认拆成两条可复用路径，便于验证“单订单多路径”和不同策略下的表现。
        List<List<double[]>> pathVariants = List.of(
        */
        List<double[]> bulkCoordinates = buildRandomRoadCoordinates(from, to);
        String bulkPathKey = pathKey(from.name(), to.name(), bulkCoordinates);
        double bulkRouteLengthKm = pathLengthKm(bulkCoordinates);
        double bulkSpeedKmh = simulationSpeedKmh();
        long bulkTravelDurationMs = Math.max(60_000L, Math.round(bulkRouteLengthKm / bulkSpeedKmh * 3_600_000));
        long departureStepMs = Math.max(800L, Math.min(2_500L, bulkTravelDurationMs / Math.max(2, count * 3L)));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int routeIndex = i;
            String childOrderId = bulkOrderIdFor(orderId, routeIndex, count);
            String childOrderName = bulkOrderNameFor(routeIndex);
            if (i == 0) {
                messages.add(dispatchBulkRoute(
                        orderId,
                        childOrderId,
                        childOrderName,
                        totalTons,
                        count,
                        from.name(),
                        to.name(),
                        bulkCoordinates,
                        bulkPathKey,
                        bulkRouteLengthKm,
                        bulkSpeedKmh,
                        bulkTravelDurationMs
                ));
            } else {
                bulkDispatchExecutor.schedule(
                        () -> dispatchBulkRoute(
                                orderId,
                                childOrderId,
                                childOrderName,
                                totalTons,
                                count,
                                from.name(),
                                to.name(),
                                bulkCoordinates,
                                bulkPathKey,
                                bulkRouteLengthKm,
                                bulkSpeedKmh,
                                bulkTravelDurationMs
                        ),
                        routeIndex * departureStepMs,
                        TimeUnit.MILLISECONDS
                );
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", orderId);
        response.put("orderName", orderName);
        response.put("totalTons", totalTons);
        response.put("vehicleCount", count);
        response.put("from", from.name());
        response.put("to", to.name());
        response.put("routes", messages);
        response.put("dispatchedRouteCount", messages.size());
        response.put("scheduledRouteCount", Math.max(0, count - messages.size()));
        response.put("dispatchIntervalMs", departureStepMs);
        return response;
    }

    private synchronized Map<String, Object> dispatchBulkRoute(
            String orderFamilyId,
            String orderId,
            String orderName,
            int totalTons,
            int vehicleCount,
            String from,
            String to,
            List<double[]> coordinates,
            String pathKey,
            double routeLengthKm,
            double speedKmh,
            long travelDurationMs
    ) {
        cleanupExpiredRoutes(System.currentTimeMillis());
        String lineId = UUID.randomUUID().toString();
        ScheduledRoute route = new ScheduledRoute(
                lineId,
                orderId,
                orderFamilyId,
                orderName,
                totalTons,
                vehicleCount,
                from,
                to,
                coordinates,
                pathKey,
                System.currentTimeMillis(),
                routeLengthKm,
                speedKmh,
                travelDurationMs
        );
        lineIdPlateMap.put(lineId, dataFactory.randomPlate());
        activeRoutes.put(lineId, route);
        Map<String, Object> message = routeMessage(route, true);
        webSocketHandler.broadcast(message);
        log.debug("Dispatched bulk route: {} -> {}, orderId: {}, family: {}, lineId: {}",
                from, to, orderId, orderFamilyId, lineId);
        return message;
    }

    private String bulkOrderIdFor(String rootOrderId, int routeIndex, int vehicleCount) {
        int firstBatchSize = Math.max(1, Math.min(vehicleCount, Math.max(3, vehicleCount / 3)));
        if (routeIndex < firstBatchSize) {
            return rootOrderId;
        }
        int appendIndex = ((routeIndex - firstBatchSize) / Math.max(1, groupSize)) + 1;
        return rootOrderId + "-ADD-" + appendIndex;
    }

    private String bulkOrderNameFor(int routeIndex) {
        return routeIndex == 0
                ? "\u5927\u5b97\u8fd0\u8f93\u8ba2\u5355"
                : "\u5927\u5b97\u8fd0\u8f93\u8ba2\u5355-\u8ffd\u52a0\u914d\u9001";
    }

    public Map<String, Object> listRouteGroups() {
        return listRouteGroups(null);
    }

    public Map<String, Object> listRouteGroups(String strategyName) {
        cleanupExpiredRoutes(System.currentTimeMillis());
        String strategy = resolveGroupStrategy(strategyName);
        List<RouteInfo> routes = activeRouteInfos();
        List<GroupSummary> summaries = groupSummaries(strategy);
        DisplayGroupLock lock = displayGroupLocks.get(strategy);
        List<RouteInfo> lockedRoutes = lock == null ? List.of() : routesByLockedDisplayGroup(lock.groupId(), strategy);
        Set<String> lockedRouteIds = lockedRoutes.stream()
                .map(RouteInfo::getLineId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        boolean lockedGroupIncluded = false;
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GroupSummary summary : summaries) {
            if (lock != null && summary.getGroupId().equals(lock.groupId()) && !lockedRoutes.isEmpty()) {
                groups.add(lockedDisplayGroupMessage(lock.groupId(), lockedRoutes));
                lockedGroupIncluded = true;
                continue;
            }
            if (!lockedRoutes.isEmpty() && isCoveredByLockedDisplayGroup(summary, lockedRoutes, lockedRouteIds)) {
                continue;
            }
            groups.add(groupSummaryMessage(summary));
        }
        if (lock != null && !lockedGroupIncluded && !lockedRoutes.isEmpty()) {
            groups.add(0, lockedDisplayGroupMessage(lock.groupId(), lockedRoutes));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groupSize", groupSize);
        response.put("strategy", strategy);
        response.put("totalRoutes", routes.size());
        response.put("groups", groups);
        return response;
    }

    public Map<String, Object> listRoutesByGroup(String groupId) {
        return listRoutesByGroup(groupId, null);
    }

    public Map<String, Object> listRoutesByGroup(String groupId, String strategyName) {
        cleanupExpiredRoutes(System.currentTimeMillis());
        String strategy = resolveGroupStrategy(strategyName);
        List<RouteInfo> routes = routesByLockedDisplayGroup(groupId, strategy);
        if (routes.isEmpty()) {
            routes = routesByGroup(groupId, strategy);
        }
        rememberDisplayGroup(strategy, groupId, routes);
        List<Map<String, Object>> routeMessages = routes.stream()
                .map((route) -> routeMessage(route, false))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groupId", groupId);
        response.put("groupSize", groupSize);
        response.put("strategy", strategy);
        response.put("routes", routeMessages);
        return response;
    }

    /**
     * 通过车牌查询位置
     */
    private ProviderPosition fetchPositionByPlate(String plate) {
        if (externalPositionUrl == null || externalPositionUrl.isBlank()) {
            return null;
        }

        try {
            String token = getAccessToken();
            String url = externalPositionUrl + "/video/webapi/location/get-location-use-plates";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plates", plate);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", token)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Plate API returned status {}", response.statusCode());
                return null;
            }

            Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (!(result.get("code") instanceof Number code) || code.intValue() != 200) {
                log.warn("Plate API code not 200: {}", result);
                return null;
            }

            Map<String, Object> dataBlock = (Map<String, Object>) result.get("data");
            List<Map<String, Object>> vehicleList = (List<Map<String, Object>>) dataBlock.get("data");
            if (vehicleList == null || vehicleList.isEmpty()) {
                log.info("Plate API returned empty data for plate={}, message={}, dataTime={}",
                        plate, result.get("message"), dataBlock == null ? null : dataBlock.get("time"));
                return null;
            }

            Map<String, Object> vehicle = vehicleList.get(0);
            double lng = Double.parseDouble(String.valueOf(vehicle.get("lng")));
            double lat = Double.parseDouble(String.valueOf(vehicle.get("lat")));
            double speed = 0;
            try {
                speed = Double.parseDouble(String.valueOf(vehicle.get("speed")));
            } catch (NumberFormatException ignored) {}

            return providerPosition(vehicle, lng, lat, speed);

        } catch (Exception e) {
            log.warn("Failed to fetch position by plate", e);
            return null;
        }
    }

    /**
     * 通过车辆ID查询位置
     */
    private ProviderPosition fetchPositionByCarId(String carIds) {
        if (externalPositionUrl == null || externalPositionUrl.isBlank()) {
            return null;
        }

        try {
            String token = getAccessToken();  // 动态获取 token
            String url = externalPositionUrl + "/video/webapi/location/get-location-use-carids";
            Map<String, Object> body = Map.of("car_ids", carIds);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", token)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("CarID API returned status {}", response.statusCode());
                return null;
            }

            Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (!(result.get("code") instanceof Number code) || code.intValue() != 200) {
                log.warn("CarID API code not 200: {}", result);
                return null;
            }

            Map<String, Object> dataBlock = (Map<String, Object>) result.get("data");
            List<Map<String, Object>> vehicleList = (List<Map<String, Object>>) dataBlock.get("data");
            if (vehicleList == null || vehicleList.isEmpty()) {
                log.info("CarID API returned empty data for carIds={}, message={}, dataTime={}",
                        carIds, result.get("message"), dataBlock == null ? null : dataBlock.get("time"));
                return null;
            }

            Map<String, Object> vehicle = vehicleList.get(0);
            double lng = Double.parseDouble(String.valueOf(vehicle.get("lng")));
            double lat = Double.parseDouble(String.valueOf(vehicle.get("lat")));
            double speed = 0;
            try {
                speed = Double.parseDouble(String.valueOf(vehicle.get("speed")));
            } catch (NumberFormatException ignored) {}

            return providerPosition(vehicle, lng, lat, speed);

        } catch (Exception e) {
            log.warn("Failed to fetch position by carId", e);
            return null;
        }
    }

    private ProviderPosition providerPosition(Map<String, Object> vehicle, double lng, double lat, double speedKmh) {
        return new ProviderPosition(
                new double[]{lng, lat},
                speedKmh,
                stringValue(vehicle.get("vehicle_id")),
                stringValue(vehicle.get("vehicle_name"))
        );
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public Map<String, Object> getPosition(String lineId) {
        long now = System.currentTimeMillis();
        cleanupExpiredRoutes(now);
        ScheduledRoute route = activeRoutes.get(lineId);
        if (route == null) {
            return Map.of(
                    "type", "truck_position",
                    "lineId", lineId,
                    "status", "finished"
            );
        }

        return positionMessage(route, now);
    }

    @Scheduled(fixedRateString = "${dashboard.route.truck-position-push-rate-ms:60000}")
    public void pushPassiveTruckPositions() {
        if (!passivePositionPushEnabled) return;

        long now = System.currentTimeMillis();
        cleanupExpiredRoutes(now);
        activeRoutes.values().forEach((route) -> webSocketHandler.broadcast(positionMessage(route, now)));
    }

    private List<double[]> buildRandomRoadCoordinates(City from, City to) {
        List<double[]> waypoints = new ArrayList<>();
        waypoints.add(new double[]{from.lng(), from.lat()});

        int extraPoints = ThreadLocalRandom.current().nextInt(1, 3);
        for (int i = 0; i < extraPoints; i++) {
            double ratio = (i + 1.0) / (extraPoints + 1.0);
            double baseLng = from.lng() + (to.lng() - from.lng()) * ratio;
            double baseLat = from.lat() + (to.lat() - from.lat()) * ratio;
            double offsetLng = ThreadLocalRandom.current().nextDouble(-2.2, 2.2);
            double offsetLat = ThreadLocalRandom.current().nextDouble(-1.8, 1.8);
            waypoints.add(new double[]{baseLng + offsetLng, baseLat + offsetLat});
        }

        waypoints.add(new double[]{to.lng(), to.lat()});
        return dataFactory.simulateMultiPointPath(waypoints, 200);
    }

    private Map<String, Object> routeMessage(RouteInfo route, boolean created) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "road_path");
        message.put("lineId", route.getLineId());
        message.put("groupId", groupIdFor(route.getLineId()));
        if (route instanceof OrderAwareRouteInfo orderAwareRoute) {
            message.put("orderId", orderAwareRoute.getOrderId());
            message.put("orderFamilyId", orderAwareRoute.getOrderFamilyId());
        }
        if (route instanceof ScheduledRoute scheduledRoute) {
            message.put("orderName", scheduledRoute.orderName());
            message.put("orderTotalTons", scheduledRoute.orderTotalTons());
            message.put("orderVehicleCount", scheduledRoute.orderVehicleCount());
            if (scheduledRoute.orderTotalTons() > 0) {
                message.put("cargo", scheduledRoute.orderTotalTons() + "吨");
            }
        }
        if (route instanceof PathAwareRouteInfo pathAwareRoute) {
            message.put("pathKey", pathAwareRoute.getPathKey());
        }
        String plate = lineIdPlateMap.get(route.getLineId());
        if (plate != null && !plate.isBlank()) {
            message.put("plate", plate);
        }
        String carId = lineIdCarIdMap.get(route.getLineId());
        if (carId != null && !carId.isBlank()) {
            message.put("carId", carId);
        }
        message.put("from", route.getFrom());
        message.put("to", route.getTo());
        message.put("coordinates", route.getCoordinates());
        message.put("created", created);
        message.put("routeLengthKm", route.getRouteLengthKm());
        message.put("speedKmh", route.getSpeedKmh());
        message.put("travelDurationMs", route.getTravelDurationMs());
        return message;
    }

    private List<ScheduledRoute> sortedActiveRoutes() {
        return activeRoutes.values().stream()
                .sorted(Comparator.comparingLong(ScheduledRoute::startTime))
                .toList();
    }

    private String groupIdFor(String lineId) {
        RouteInfo route = activeRoutes.get(lineId);
        String lockedGroupId = lockedGroupIdFor(route, defaultGroupStrategy);
        if (lockedGroupId != null) {
            return lockedGroupId;
        }
        for (GroupSummary group : groupSummaries(defaultGroupStrategy)) {
            for (Object groupedRoute : group.getRoutes()) {
                if (groupedRoute instanceof RouteInfo routeInfo && routeInfo.getLineId().equals(lineId)) {
                    return group.getGroupId();
                }
            }
        }
        return groupId(0);
    }

    private String lockedGroupIdFor(RouteInfo route, String strategy) {
        if (route == null) {
            return null;
        }
        DisplayGroupLock lock = displayGroupLocks.get(strategy);
        if (lock == null) {
            return null;
        }
        if (lock.routeIds().contains(route.getLineId())) {
            return lock.groupId();
        }
        Map<String, RouteInfo> activeById = new LinkedHashMap<>();
        activeRouteInfos().forEach((activeRoute) -> activeById.put(activeRoute.getLineId(), activeRoute));
        List<RouteInfo> lockedRoutes = lock.routeIds().stream()
                .map(activeById::get)
                .filter((activeRoute) -> activeRoute != null)
                .toList();
        return belongsToLockedDisplayGroup(route, lockedRoutes) ? lock.groupId() : null;
    }

    private int parseGroupIndex(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return 0;
        }
        if (groupId.startsWith("group-")) {
            try {
                return Math.max(0, Integer.parseInt(groupId.substring("group-".length())) - 1);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        try {
            return Math.max(0, Integer.parseInt(groupId));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String groupId(int index) {
        return "group-" + (Math.max(0, index) + 1);
    }

    private Map<String, Object> positionMessage(ScheduledRoute route, long now) {
        ProviderPosition providerPosition = fetchVehiclePosition(route, now);
        boolean waitingDeparture = now < route.startTime();
        double speedKmh = waitingDeparture ? 0 : providerPosition.speedKmh();
        if (speedKmh <= 0) {
            speedKmh = waitingDeparture ? 0 : calculateSpeedKmh(route.lineId(), providerPosition.position(), now, route.speedKmh());
        }

        long elapsed = Math.max(0, now - route.startTime());
        double progress = Math.min(1.0, elapsed / (double) route.travelDurationMs());
        double[] velocity = waitingDeparture ? new double[]{0, 0} : velocityFromSpeed(route.coordinates(), progress, speedKmh);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "truck_position");
        message.put("lineId", route.lineId());
        message.put("position", providerPosition.position());
        message.put("velocity", velocity);
        message.put("speedKmh", speedKmh);
        message.put("progress", progress);
        message.put("status", progress >= 1.0 ? "finished" : "running");
        return message;
    }

    private void cleanupExpiredRoutes(long now) {
        activeRoutes.entrySet().removeIf((entry) -> {
            boolean expired = now - entry.getValue().startTime() > entry.getValue().travelDurationMs();
            if (expired) {
                lastPositionSamples.remove(entry.getKey());
            }
            return expired;
        });
    }

    private ProviderPosition fetchVehiclePosition(ScheduledRoute route, long now) {
        ProviderPosition externalPosition = fetchExternalVehiclePosition(route.lineId());
        if (externalPosition != null) {
            return externalPosition;
        }

        return simulatedPosition(route, now);
    }

    private ProviderPosition simulatedPosition(ScheduledRoute route, long now) {
        if (now < route.startTime()) {
            return new ProviderPosition(coordinateAtProgress(route.coordinates(), 0), 0, null, null);
        }
        long elapsed = Math.max(0, now - route.startTime());
        double progress = Math.min(1.0, elapsed / (double) route.travelDurationMs());
        double[] position = coordinateAtProgress(route.coordinates(), progress);
        return new ProviderPosition(position, 0, null, null);
    }

    private ProviderPosition fetchExternalVehiclePosition(String lineId) {
        if (!externalPositionConfigured()) {
            return null;
        }

        // 外部订单当前只有车牌号，先按车牌查；保留 carId 作为以后兼容兜底。
        String plate = lineIdPlateMap.get(lineId);
        if (plate != null && !plate.isBlank()) {
            ProviderPosition pos = fetchPositionByPlate(plate);
            if (pos != null) return pos;
        }

        String carId = lineIdCarIdMap.get(lineId);
        if (carId != null && !carId.isBlank()) {
            ProviderPosition pos = fetchPositionByCarId(carId);
            if (pos != null) return pos;
        }

        // 都没有或接口失败，返回 null，使用模拟位置
        return null;
    }

    @PostConstruct
    public void testExternalPositionAPI() {
        log.info("[RoutePush] externalPositionUrl={}, passivePositionPushEnabled={}",
                externalPositionUrl, passivePositionPushEnabled);
        if (!testOnStartup || !externalPositionConfigured()) return;

        log.info("===== 外部位置接口启动测试开始 =====");

        LinkedHashSet<String> startupTestPlates = new LinkedHashSet<>();
        if (!testPlate.isBlank()) {
            startupTestPlates.add(testPlate);
        }
        startupTestPlates.add(STARTUP_TEST_PLATE);
        for (String startupTestPlate : startupTestPlates) {
            log.info("测试车牌: {}", startupTestPlate);
            try {
                ProviderPosition pos = fetchPositionByPlate(startupTestPlate);
                if (pos != null) {
                    log.info("车牌查询成功: queryPlate={}, vehicleId={}, vehicleName={}, lng={}, lat={}, speed={}",
                            startupTestPlate, pos.vehicleId(), pos.vehicleName(), pos.position()[0], pos.position()[1], pos.speedKmh());
                } else {
                    log.warn("车牌查询失败: plate={}，请检查配置和网络", startupTestPlate);
                }
            } catch (Exception e) {
                log.warn("车牌查询异常: plate={}", startupTestPlate, e);
            }
        }

        if (!testCarId.isBlank()) {
            log.info("测试车辆ID: {}", testCarId);
            try {
                ProviderPosition pos = fetchPositionByCarId(testCarId);
                if (pos != null) {
                    log.info("车辆ID查询成功: carId={}, vehicleId={}, vehicleName={}, lng={}, lat={}, speed={}",
                            testCarId, pos.vehicleId(), pos.vehicleName(), pos.position()[0], pos.position()[1], pos.speedKmh());
                } else {
                    log.warn("车辆ID查询失败，请检查配置和网络");
                }
            } catch (Exception e) {
                log.warn("车辆ID查询异常", e);
            }
        }

        log.info("===== 外部位置接口启动测试结束 =====");
    }

    private double[] readPosition(Object value) {
        if (!(value instanceof List<?> list) || list.size() < 2) {
            return null;
        }
        Object lng = list.get(0);
        Object lat = list.get(1);
        if (!(lng instanceof Number lngNumber) || !(lat instanceof Number latNumber)) {
            return null;
        }
        return new double[]{lngNumber.doubleValue(), latNumber.doubleValue()};
    }

    private double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0;
    }

    private double calculateSpeedKmh(String lineId, double[] position, long now, double fallbackSpeedKmh) {
        PositionSample previous = lastPositionSamples.put(lineId, new PositionSample(position, now));
        if (previous == null || now <= previous.time()) {
            return fallbackSpeedKmh;
        }

        double elapsedHours = (now - previous.time()) / 3_600_000.0;
        if (elapsedHours <= 0) {
            return fallbackSpeedKmh;
        }

        double distanceKm = distanceKm(previous.position(), position);
        double speedKmh = distanceKm / elapsedHours;
        return speedKmh > 0 ? speedKmh : fallbackSpeedKmh;
    }

    private double[] velocityFromSpeed(List<double[]> coordinates, double progress, double speedKmh) {
        double[] position = coordinateAtProgress(coordinates, progress);
        double[] nextPosition = coordinateAtProgress(coordinates, Math.min(1, progress + 0.001));
        double lngDelta = nextPosition[0] - position[0];
        double latDelta = nextPosition[1] - position[1];
        double vectorLength = Math.sqrt(lngDelta * lngDelta + latDelta * latDelta);
        if (vectorLength <= 0 || speedKmh <= 0) {
            return new double[]{0, 0};
        }

        double kmPerMs = speedKmh / 3_600_000.0;
        double kmForVector = distanceKm(position, nextPosition);
        if (kmForVector <= 0) {
            return new double[]{0, 0};
        }

        double degreesPerMsScale = kmPerMs / kmForVector;
        return new double[]{
                lngDelta * degreesPerMsScale,
                latDelta * degreesPerMsScale
        };
    }

    private double[] coordinateAtProgress(List<double[]> coordinates, double progress) {
        if (coordinates == null || coordinates.isEmpty()) {
            return new double[]{0, 0};
        }
        if (coordinates.size() == 1) {
            return coordinates.get(0);
        }

        double totalLength = pathLength(coordinates);
        if (totalLength <= 0) {
            return coordinates.get(0);
        }

        double targetDistance = Math.max(0, Math.min(1, progress)) * totalLength;
        double walked = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            double[] start = coordinates.get(i - 1);
            double[] end = coordinates.get(i);
            double segmentLength = distance(start, end);
            if (segmentLength <= 0) {
                continue;
            }

            if (walked + segmentLength >= targetDistance) {
                double localProgress = (targetDistance - walked) / segmentLength;
                return new double[]{
                        start[0] + (end[0] - start[0]) * localProgress,
                        start[1] + (end[1] - start[1]) * localProgress
                };
            }
            walked += segmentLength;
        }

        return coordinates.get(coordinates.size() - 1);
    }

    /** 反算真实GPS位置在路径上的进度（0~1），用于用外部位置校准初始进度 */
    private double progressAtCoordinate(List<double[]> coordinates, double[] realPos) {
        if (coordinates == null || coordinates.isEmpty() || realPos == null) return 0;
        double totalLength = pathLength(coordinates);
        if (totalLength <= 0) return 0;

        // 找离 realPos 最近的线段点
        double bestDist = Double.MAX_VALUE;
        double bestWalked = 0;
        double walked = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            double[] start = coordinates.get(i - 1);
            double[] end = coordinates.get(i);
            double segLen = distance(start, end);
            if (segLen <= 0) continue;
            // 点到线段的最近点
            double t = clamp01(dot(realPos[0] - start[0], realPos[1] - start[1],
                    end[0] - start[0], end[1] - start[1]) / (segLen * segLen));
            double px = start[0] + (end[0] - start[0]) * t;
            double py = start[1] + (end[1] - start[1]) * t;
            double dist = distance(realPos, new double[]{px, py});
            if (dist < bestDist) {
                bestDist = dist;
                bestWalked = walked + segLen * t;
            }
            walked += segLen;
        }
        return clamp01(bestWalked / totalLength);
    }

    private double dot(double x1, double y1, double x2, double y2) { return x1 * x2 + y1 * y2; }
    private double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    private double pathLength(List<double[]> coordinates) {
        double total = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            total += distance(coordinates.get(i - 1), coordinates.get(i));
        }
        return total;
    }

    private double distance(double[] start, double[] end) {
        double dx = end[0] - start[0];
        double dy = end[1] - start[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double pathLengthKm(List<double[]> coordinates) {
        double total = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            total += distanceKm(coordinates.get(i - 1), coordinates.get(i));
        }
        return total;
    }

    private double distanceKm(double[] start, double[] end) {
        double earthRadiusKm = 6371.0;
        double startLat = Math.toRadians(start[1]);
        double endLat = Math.toRadians(end[1]);
        double deltaLat = Math.toRadians(end[1] - start[1]);
        double deltaLng = Math.toRadians(end[0] - start[0]);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(startLat) * Math.cos(endLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private double simulationSpeedKmh() {
        if ("real".equalsIgnoreCase(simulationProfile)) {
            return Math.max(1, realSimulationSpeedKmh);
        }
        return Math.max(1, testSimulationSpeedKmh);
    }

    private boolean isRealPositionProfile() {
        return "real".equalsIgnoreCase(simulationProfile);
    }

    private boolean externalPositionConfigured() {
        return externalPositionUrl != null && !externalPositionUrl.isBlank();
    }

    public synchronized Map<String, Object> dispatchExternalOrderRoute(
            String lineId,
            String orderId,
            String orderName,
            Integer orderTotalTons,
            String from,
            String to,
            double[] fromCoords,
            double[] toCoords,
            List<double[]> routeCoordinates,
            double[] currentCoords,
            String plate,
            String carId,
            Double speedKmh,
            String updatedAt,
            String status
    ) {
        if (lineId == null || lineId.isBlank() || fromCoords == null || toCoords == null
                || fromCoords.length < 2 || toCoords.length < 2) {
            return Map.of("ok", false, "reason", "invalid-road-order", "lineId", lineId == null ? "" : lineId);
        }

        long now = System.currentTimeMillis();
        cleanupExpiredRoutes(now);

        List<double[]> coordinates = sanitizeRouteCoordinates(fromCoords, toCoords, routeCoordinates);
        double routeLengthKm = pathLengthKm(coordinates);
        if (plate != null && !plate.isBlank()) {
            lineIdPlateMap.put(lineId, plate);
        }
        if (carId != null && !carId.isBlank()) {
            lineIdCarIdMap.put(lineId, carId);
        }

        ProviderPosition initialExternalPosition = fetchExternalVehiclePosition(lineId);
        String progressSource = initialProgressSource(initialExternalPosition, currentCoords, status);
        double[] resolvedCurrentCoords = initialExternalPosition != null
                ? initialExternalPosition.position()
                : currentCoords;
        Double resolvedSpeedKmh = initialExternalPosition != null && initialExternalPosition.speedKmh() > 0
                ? initialExternalPosition.speedKmh()
                : speedKmh;
        double effectiveSpeedKmh = resolvedSpeedKmh != null && resolvedSpeedKmh > 0
                ? resolvedSpeedKmh
                : Math.max(1, realSimulationSpeedKmh);
        String resolvedUpdatedAt = initialExternalPosition != null
                ? Instant.ofEpochMilli(now).toString()
                : updatedAt;
        double progress = initialProgressForExternalOrder(
                coordinates,
                resolvedCurrentCoords,
                routeLengthKm,
                effectiveSpeedKmh,
                resolvedUpdatedAt,
                status
        );
        long travelDurationMs = Math.max(60_000L, Math.round(routeLengthKm / effectiveSpeedKmh * 3_600_000));
        long startTime = "已完成".equals(status)
                ? now - travelDurationMs
                : now - Math.round(progress * travelDurationMs);

        ScheduledRoute route = new ScheduledRoute(
                lineId,
                orderId == null || orderId.isBlank() ? lineId : orderId,
                orderId == null || orderId.isBlank() ? lineId : orderId,
                orderName == null || orderName.isBlank() ? "外部订单运输" : orderName,
                orderTotalTons == null ? 0 : orderTotalTons,
                1,
                from,
                to,
                coordinates,
                pathKey(from, to, coordinates),
                startTime,
                routeLengthKm,
                effectiveSpeedKmh,
                travelDurationMs
        );

        activeRoutes.put(lineId, route);
        Map<String, Object> message = routeMessage(route, true);
        webSocketHandler.broadcast(message);
        log.info("[RoadMap] dispatched external long-haul route: {} -> {}, lineId={}, orderId={}, progress={}%, progressSource={}",
                from, to, lineId, route.orderId(), Math.round(progress * 100), progressSource);
        return message;
    }

    private String initialProgressSource(ProviderPosition initialExternalPosition, double[] currentCoords, String status) {
        if ("已完成".equals(status)) return "completed-status";
        if (initialExternalPosition != null) return "real-provider";
        if (currentCoords != null && currentCoords.length >= 2) return "order-current-coords";
        return "simulated-random";
    }

    private List<double[]> sanitizeRouteCoordinates(double[] fromCoords, double[] toCoords, List<double[]> routeCoordinates) {
        List<double[]> coordinates = new ArrayList<>();
        if (routeCoordinates != null) {
            for (double[] coord : routeCoordinates) {
                if (coord == null || coord.length < 2) continue;
                if (!Double.isFinite(coord[0]) || !Double.isFinite(coord[1])) continue;
                addDistinctCoordinate(coordinates, coord);
            }
        }

        if (coordinates.size() >= 2) {
            return coordinates;
        }

        coordinates.clear();
        addDistinctCoordinate(coordinates, fromCoords);
        addDistinctCoordinate(coordinates, toCoords);
        return coordinates;
    }

    private void addDistinctCoordinate(List<double[]> coordinates, double[] coord) {
        if (coord == null || coord.length < 2) return;
        double[] next = new double[]{coord[0], coord[1]};
        if (coordinates.isEmpty()) {
            coordinates.add(next);
            return;
        }
        double[] previous = coordinates.get(coordinates.size() - 1);
        if (Math.abs(previous[0] - next[0]) < 0.000001 && Math.abs(previous[1] - next[1]) < 0.000001) {
            return;
        }
        coordinates.add(next);
    }

    private double initialProgressForExternalOrder(
            List<double[]> coordinates,
            double[] currentCoords,
            double routeLengthKm,
            double speedKmh,
            String updatedAt,
            String status
    ) {
        if ("已完成".equals(status)) return 1.0;

        if (currentCoords == null || currentCoords.length < 2) {
            return ThreadLocalRandom.current().nextDouble(0.1, 0.9);
        }

        double baseProgress = progressOnCoordinates(coordinates, currentCoords);
        Long updatedAtMs = parseUpdatedAtMillis(updatedAt);
        if (updatedAtMs == null || speedKmh <= 0 || routeLengthKm <= 0) {
            return Math.max(0, Math.min(1, baseProgress));
        }

        double elapsedHours = Math.max(0, (System.currentTimeMillis() - updatedAtMs) / 3_600_000.0);
        return Math.max(0, Math.min(1, baseProgress + speedKmh * elapsedHours / routeLengthKm));
    }

    private double progressOnCoordinates(List<double[]> coordinates, double[] position) {
        if (coordinates == null || coordinates.size() < 2 || position == null || position.length < 2) {
            return 0;
        }

        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        double distanceAlong = 0;
        double walked = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            double[] start = coordinates.get(i - 1);
            double[] end = coordinates.get(i);
            double dx = end[0] - start[0];
            double dy = end[1] - start[1];
            double segmentLengthSq = dx * dx + dy * dy;
            if (segmentLengthSq <= 0) continue;

            double t = ((position[0] - start[0]) * dx + (position[1] - start[1]) * dy) / segmentLengthSq;
            t = Math.max(0, Math.min(1, t));
            double projectedLng = start[0] + dx * t;
            double projectedLat = start[1] + dy * t;
            double distSq = Math.pow(position[0] - projectedLng, 2) + Math.pow(position[1] - projectedLat, 2);
            double segmentKm = distanceKm(start, end);
            if (distSq < nearestDistanceSq) {
                nearestDistanceSq = distSq;
                distanceAlong = walked + segmentKm * t;
            }
            walked += segmentKm;
        }

        return walked <= 0 ? 0 : Math.max(0, Math.min(1, distanceAlong / walked));
    }

    private Long parseUpdatedAtMillis(String updatedAt) {
        if (updatedAt == null || updatedAt.isBlank()) return null;
        String value = updatedAt.trim();
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value.replace(" ", "T"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * TownRoad 专用：指定起终点 + 直线路线 + 中途随机起点 + real 速度。
     * 共享 RoadMap 的 activeRoutes 和 pushPassiveTruckPositions 定时推送。
     */
    public synchronized void dispatchTownRoute(
            String lineId, String orderId, String from, String to,
            double fromLng, double fromLat, double toLng, double toLat,
            String plate, String carId
    ) {
        if (!passivePositionPushEnabled) return;
        cleanupExpiredRoutes(System.currentTimeMillis());

        // 登记车牌/车辆ID（供真实GPS接口查询）
        if (plate != null && !plate.isBlank()) lineIdPlateMap.put(lineId, plate);
        if (carId != null && !carId.isBlank()) lineIdCarIdMap.put(lineId, carId);

        // 直线：只有起终点两个坐标
        List<double[]> coordinates = List.of(
                new double[]{fromLng, fromLat},
                new double[]{toLng, toLat}
        );
        String pathKey = pathKey(from, to, coordinates);
        double routeLengthKm = pathLengthKm(coordinates);
        double speedKmh = realSimulationSpeedKmh;
        long travelDurationMs = Math.max(60_000L, Math.round(routeLengthKm / speedKmh * 3_600_000));

        // 先尝试从外部接口拿真实位置（校准初始进度）
        double initialProgress;
        long startTime;
        ProviderPosition realPos = null;
        if (plate != null && !plate.isBlank() && externalPositionConfigured()) {
            log.debug("[TownRoad] fetching real position for plate={}", plate);
            realPos = fetchPositionByPlate(plate);
        }
        if (realPos == null && carId != null && !carId.isBlank() && externalPositionConfigured()) {
            realPos = fetchPositionByCarId(carId);
        }
        if (realPos != null) {
            // 用真实位置反算进度
            initialProgress = progressAtCoordinate(coordinates, realPos.position());
            startTime = System.currentTimeMillis() - Math.round(initialProgress * travelDurationMs);
            log.info("[TownRoad] real position for lineId={}: lng={}, lat={}, progress={}%",
                    lineId, realPos.position()[0], realPos.position()[1], Math.round(initialProgress * 100));
        } else {
            // 中途随机起点
            initialProgress = ThreadLocalRandom.current().nextDouble(0.1, 0.9);
            startTime = System.currentTimeMillis() - Math.round(initialProgress * travelDurationMs);
        }

        ScheduledRoute route = new ScheduledRoute(
                lineId, orderId, orderId, "短途运输",
                0, 1, from, to, coordinates, pathKey,
                startTime, routeLengthKm, speedKmh, travelDurationMs
        );
        activeRoutes.put(lineId, route);
        log.info("[TownRoad] dispatched town route: {} -> {}, lineId={}, progress={}%, progressSource=simulated-random",
                from, to, lineId, Math.round(initialProgress * 100));
    }

    @PreDestroy
    public void shutdownBulkDispatchExecutor() {
        bulkDispatchExecutor.shutdownNow();
    }

    private record ScheduledRoute(
            String lineId,
            String orderId,
            String orderFamilyId,
            String orderName,
            int orderTotalTons,
            int orderVehicleCount,
            String from,
            String to,
            List<double[]> coordinates,
            String pathKey,
            long startTime,
            double routeLengthKm,
            double speedKmh,
            long travelDurationMs
    ) implements RouteInfo, OrderAwareRouteInfo, PathAwareRouteInfo {
        @Override
        public String getLineId() { return lineId; }
        @Override
        public String getOrderId() { return orderId; }
        @Override
        public String getOrderFamilyId() { return orderFamilyId; }
        @Override
        public String getFrom() { return from; }
        @Override
        public String getTo() { return to; }
        @Override
        public double[] getFromCoords() { return coordinates.get(0); }
        @Override
        public double[] getToCoords() { return coordinates.get(coordinates.size() - 1); }
        @Override
        public double getRouteLengthKm() { return routeLengthKm; }
        @Override
        public double getSpeedKmh() { return speedKmh; }
        @Override
        public long getTravelDurationMs() { return travelDurationMs; }
        @Override
        public long getStartTime() { return startTime; }
        @Override
        public List<double[]> getCoordinates() { return coordinates; }
        @Override
        public String getPathKey() { return pathKey; }
    }

    private record ProviderPosition(
            double[] position,
            double speedKmh,
            String vehicleId,
            String vehicleName
    ) {
    }

    public Map<String, Object> queryPositionByVehicleKey(String vehicleKey) {
        ProviderPosition pos = null;
        if (externalPositionConfigured() && vehicleKey != null && !vehicleKey.isBlank()) {
            pos = fetchPositionByPlate(vehicleKey);
            if (pos == null) {
                pos = fetchPositionByCarId(vehicleKey);
            }
        }
        if (pos == null) {
            pos = querySimulatedPosition(vehicleKey);
        }
        if (pos == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lng", pos.position()[0]);
        result.put("lat", pos.position()[1]);
        result.put("speedKmh", pos.speedKmh());
        return result;
    }

    public Map<String, Object> queryPositionByCarId(String carId) {
        return queryPositionByVehicleKey(carId);
    }

    private ProviderPosition querySimulatedPosition(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.trim();
        long now = System.currentTimeMillis();
        return activeRoutes.values().stream()
                .filter(route -> route.lineId().equalsIgnoreCase(normalized)
                        || normalized.equalsIgnoreCase(lineIdPlateMap.get(route.lineId()))
                        || normalized.equalsIgnoreCase(lineIdCarIdMap.get(route.lineId())))
                .findFirst()
                .map(route -> {
                    ProviderPosition pos = simulatedPosition(route, now);
                    return new ProviderPosition(pos.position(), route.speedKmh(), null, null);
                })
                .orElse(null);
    }

    private record PositionSample(
            double[] position,
            long time
    ) {
    }

    private record DisplayGroupLock(
            String groupId,
            LinkedHashSet<String> routeIds,
            long updatedAt
    ) {
    }

    private List<RouteInfo> activeRouteInfos() {
        return sortedActiveRoutes().stream()
                .map(RouteInfo.class::cast)
                .toList();
    }

    private List<GroupSummary> groupSummaries(String strategyName) {
        RouteGroupingResult result = routeGroupingEngine.group(
                activeRouteInfos(),
                GroupingContext.defaults(strategyName, groupSize)
        );
        return result.getGroups();
    }

    private List<RouteInfo> routesByGroup(String groupId, String strategyName) {
        return routeGroupingEngine.routesByGroup(
                activeRouteInfos(),
                GroupingContext.defaults(strategyName, groupSize),
                groupId
        );
    }

    private void rememberDisplayGroup(String strategy, String groupId, List<RouteInfo> routes) {
        if (groupId == null || groupId.isBlank() || routes == null || routes.isEmpty()) {
            return;
        }
        LinkedHashSet<String> routeIds = new LinkedHashSet<>();
        routes.forEach((route) -> routeIds.add(route.getLineId()));
        displayGroupLocks.put(strategy, new DisplayGroupLock(groupId, routeIds, System.currentTimeMillis()));
    }

    private List<RouteInfo> routesByLockedDisplayGroup(String groupId, String strategy) {
        DisplayGroupLock lock = displayGroupLocks.get(strategy);
        if (lock == null || !lock.groupId().equals(groupId)) {
            return List.of();
        }

        List<RouteInfo> active = activeRouteInfos();
        Map<String, RouteInfo> activeById = new LinkedHashMap<>();
        active.forEach((route) -> activeById.put(route.getLineId(), route));

        List<RouteInfo> lockedRoutes = lock.routeIds().stream()
                .map(activeById::get)
                .filter((route) -> route != null)
                .toList();
        List<RouteInfo> groupedRoutes = routesByGroup(groupId, strategy);
        LinkedHashSet<String> seedRouteIds = new LinkedHashSet<>();
        lockedRoutes.forEach((route) -> seedRouteIds.add(route.getLineId()));
        groupedRoutes.forEach((route) -> seedRouteIds.add(route.getLineId()));
        List<RouteInfo> seedRoutes = active.stream()
                .filter((route) -> seedRouteIds.contains(route.getLineId()))
                .toList();
        if (seedRoutes.isEmpty()) {
            displayGroupLocks.remove(strategy);
            return List.of();
        }

        LinkedHashSet<String> nextRouteIds = new LinkedHashSet<>();
        seedRoutes.forEach((route) -> nextRouteIds.add(route.getLineId()));
        for (RouteInfo route : active) {
            if (nextRouteIds.contains(route.getLineId())) {
                continue;
            }
            if (belongsToLockedDisplayGroup(route, seedRoutes)) {
                nextRouteIds.add(route.getLineId());
            }
        }

        DisplayGroupLock nextLock = new DisplayGroupLock(groupId, nextRouteIds, System.currentTimeMillis());
        displayGroupLocks.put(strategy, nextLock);
        return active.stream()
                .filter((route) -> nextRouteIds.contains(route.getLineId()))
                .toList();
    }

    private boolean belongsToLockedDisplayGroup(RouteInfo candidate, List<RouteInfo> lockedRoutes) {
        for (RouteInfo lockedRoute : lockedRoutes) {
            if (sameOrderFamily(candidate, lockedRoute) || sameOrder(candidate, lockedRoute)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameOrderFamily(RouteInfo left, RouteInfo right) {
        if (!(left instanceof OrderAwareRouteInfo leftOrder) || !(right instanceof OrderAwareRouteInfo rightOrder)) {
            return false;
        }
        String leftFamily = leftOrder.getOrderFamilyId();
        String rightFamily = rightOrder.getOrderFamilyId();
        return leftFamily != null && !leftFamily.isBlank() && leftFamily.equals(rightFamily);
    }

    private boolean sameOrder(RouteInfo left, RouteInfo right) {
        if (!(left instanceof OrderAwareRouteInfo leftOrder) || !(right instanceof OrderAwareRouteInfo rightOrder)) {
            return false;
        }
        String leftOrderId = leftOrder.getOrderId();
        String rightOrderId = rightOrder.getOrderId();
        return leftOrderId != null && !leftOrderId.isBlank() && leftOrderId.equals(rightOrderId);
    }

    private boolean samePath(RouteInfo left, RouteInfo right) {
        if (!(left instanceof PathAwareRouteInfo leftPath) || !(right instanceof PathAwareRouteInfo rightPath)) {
            return false;
        }
        String leftPathKey = leftPath.getPathKey();
        String rightPathKey = rightPath.getPathKey();
        return leftPathKey != null && !leftPathKey.isBlank() && leftPathKey.equals(rightPathKey);
    }

    private boolean sameDirection(RouteInfo left, RouteInfo right) {
        return safeRouteText(left.getFrom()).equals(safeRouteText(right.getFrom()))
                && safeRouteText(left.getTo()).equals(safeRouteText(right.getTo()));
    }

    private String routeDirectionKey(RouteInfo route) {
        return safeRouteText(route.getFrom()) + "→" + safeRouteText(route.getTo());
    }

    private String safeRouteText(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }

    private void appendLockedDisplayGroupIfNeeded(
            String strategy,
            List<GroupSummary> summaries,
            List<Map<String, Object>> groups
    ) {
        DisplayGroupLock lock = displayGroupLocks.get(strategy);
        if (lock == null) {
            return;
        }
        boolean alreadyIncluded = summaries.stream()
                .anyMatch((summary) -> lock.groupId().equals(summary.getGroupId()));
        if (alreadyIncluded) {
            return;
        }

        List<RouteInfo> routes = routesByLockedDisplayGroup(lock.groupId(), strategy);
        if (routes.isEmpty()) {
            return;
        }
        groups.add(0, lockedDisplayGroupMessage(lock.groupId(), routes));
    }

    private boolean isCoveredByLockedDisplayGroup(
            GroupSummary summary,
            List<RouteInfo> lockedRoutes,
            Set<String> lockedRouteIds
    ) {
        List<RouteInfo> summaryRoutes = summary.getRoutes();
        if (summaryRoutes == null || summaryRoutes.isEmpty()) {
            return false;
        }
        boolean allAlreadyLocked = summaryRoutes.stream()
                .allMatch((route) -> lockedRouteIds.contains(route.getLineId()));
        if (allAlreadyLocked) {
            return true;
        }
        return summaryRoutes.stream()
                .allMatch((route) -> belongsToLockedDisplayGroup(route, lockedRoutes));
    }

    private Map<String, Object> lockedDisplayGroupMessage(String groupId, List<RouteInfo> routes) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("groupId", groupId);
        group.put("groupKey", "播放中稳定分组");
        group.put("index", 0);
        group.put("subIndex", 1);
        group.put("count", routes.size());
        group.put("groupType", "mixed");
        group.put("groupScenario", "mixed");
        group.put("scenarioReason", "前端正在播放，后端保持该组稳定并只追加兼容路线");
        group.put("displayTemplate", "basic");
        group.put("styleHint", null);
        group.put("orderIds", routes.stream()
                .filter((route) -> route instanceof OrderAwareRouteInfo)
                .map((route) -> ((OrderAwareRouteInfo) route).getOrderId())
                .filter((orderId) -> orderId != null && !orderId.isBlank())
                .distinct()
                .toList());
        group.put("routeKey", routes.isEmpty() ? null : routeDirectionKey(routes.get(0)));
        group.put("pathKey", routes.get(0) instanceof PathAwareRouteInfo pathAwareRoute ? pathAwareRoute.getPathKey() : null);
        return group;
    }

    private Map<String, Object> groupSummaryMessage(GroupSummary summary) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("groupId", summary.getGroupId());
        group.put("groupKey", summary.getGroupKey());
        group.put("index", summary.getIndex());
        group.put("subIndex", summary.getSubIndex());
        group.put("count", summary.getCount());
        group.put("groupType", summary.getGroupType());
        group.put("groupScenario", summary.getGroupScenario());
        group.put("scenarioReason", summary.getScenarioReason());
        group.put("displayTemplate", summary.getDisplayTemplate());
        group.put("styleHint", summary.getStyleHint());
        group.put("orderIds", summary.getOrderIds());
        group.put("routeKey", summary.getRouteKey());
        group.put("pathKey", summary.getPathKey());
        return group;
    }

    private String resolveGroupStrategy(String strategyName) {
        return strategyName == null || strategyName.isBlank() ? defaultGroupStrategy : strategyName;
    }

    private String pathKey(String from, String to, List<double[]> coordinates) {
        return from + "->" + to + "-" + Integer.toHexString(coordinates.hashCode());
    }
}
