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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class RoutePushService {

    private static final Logger log = LoggerFactory.getLogger(RoutePushService.class);
    private final Path tokenCachePath = Path.of(System.getProperty("java.io.tmpdir"), "jushen_token_cache.json");

    private final RealtimeWebSocketHandler webSocketHandler;
    private final SimulationDataFactory dataFactory;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, ScheduledRoute> activeRoutes = new ConcurrentHashMap<>();
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

        // 大宗订单默认拆成两条可复用路径，便于验证“单订单多路径”和不同策略下的表现。
        List<List<double[]>> pathVariants = List.of(
                buildRandomRoadCoordinates(from, to),
                buildRandomRoadCoordinates(from, to)
        );
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String lineId = UUID.randomUUID().toString();
            List<double[]> coordinates = pathVariants.get(i % pathVariants.size());
            double routeLengthKm = pathLengthKm(coordinates);
            double speedKmh = simulationSpeedKmh();
            long travelDurationMs = Math.max(60_000L, Math.round(routeLengthKm / speedKmh * 3_600_000));
            ScheduledRoute route = new ScheduledRoute(
                    lineId,
                    orderId,
                    orderName,
                    totalTons,
                    count,
                    from.name(),
                    to.name(),
                    coordinates,
                    pathKey(from.name(), to.name(), coordinates),
                    System.currentTimeMillis(),
                    routeLengthKm,
                    speedKmh,
                    travelDurationMs
            );
            activeRoutes.put(lineId, route);
            Map<String, Object> message = routeMessage(route, true);
            messages.add(message);
            webSocketHandler.broadcast(message);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderId", orderId);
        response.put("orderName", orderName);
        response.put("totalTons", totalTons);
        response.put("vehicleCount", count);
        response.put("from", from.name());
        response.put("to", to.name());
        response.put("routes", messages);
        return response;
    }

    public Map<String, Object> listRouteGroups() {
        return listRouteGroups(null);
    }

    public Map<String, Object> listRouteGroups(String strategyName) {
        cleanupExpiredRoutes(System.currentTimeMillis());
        String strategy = resolveGroupStrategy(strategyName);
        List<RouteInfo> routes = activeRouteInfos();
        List<Map<String, Object>> groups = groupSummaries(strategy).stream()
                .map(this::groupSummaryMessage)
                .toList();

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
        List<RouteInfo> routes = routesByGroup(groupId, strategy);
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
            Map<String, Object> body = Map.of("plates", plate);
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
                return null;
            }

            Map<String, Object> vehicle = vehicleList.get(0);
            double lng = Double.parseDouble(String.valueOf(vehicle.get("lng")));
            double lat = Double.parseDouble(String.valueOf(vehicle.get("lat")));
            double speed = 0;
            try {
                speed = Double.parseDouble(String.valueOf(vehicle.get("speed")));
            } catch (NumberFormatException ignored) {}

            return new ProviderPosition(new double[]{lng, lat}, speed);

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
                return null;
            }

            Map<String, Object> vehicle = vehicleList.get(0);
            double lng = Double.parseDouble(String.valueOf(vehicle.get("lng")));
            double lat = Double.parseDouble(String.valueOf(vehicle.get("lat")));
            double speed = 0;
            try {
                speed = Double.parseDouble(String.valueOf(vehicle.get("speed")));
            } catch (NumberFormatException ignored) {}

            return new ProviderPosition(new double[]{lng, lat}, speed);

        } catch (Exception e) {
            log.warn("Failed to fetch position by carId", e);
            return null;
        }
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
        }
        if (route instanceof ScheduledRoute scheduledRoute) {
            message.put("orderName", scheduledRoute.orderName());
            message.put("orderTotalTons", scheduledRoute.orderTotalTons());
            message.put("orderVehicleCount", scheduledRoute.orderVehicleCount());
        }
        if (route instanceof PathAwareRouteInfo pathAwareRoute) {
            message.put("pathKey", pathAwareRoute.getPathKey());
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
        for (GroupSummary group : groupSummaries(defaultGroupStrategy)) {
            for (Object route : group.getRoutes()) {
                if (route instanceof RouteInfo routeInfo && routeInfo.getLineId().equals(lineId)) {
                    return group.getGroupId();
                }
            }
        }
        return groupId(0);
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
        double speedKmh = providerPosition.speedKmh();
        if (speedKmh <= 0) {
            speedKmh = calculateSpeedKmh(route.lineId(), providerPosition.position(), now, route.speedKmh());
        }

        long elapsed = Math.max(0, now - route.startTime());
        double progress = Math.min(1.0, elapsed / (double) route.travelDurationMs());
        double[] velocity = velocityFromSpeed(route.coordinates(), progress, speedKmh);

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

        long elapsed = Math.max(0, now - route.startTime());
        double progress = Math.min(1.0, elapsed / (double) route.travelDurationMs());
        double[] position = coordinateAtProgress(route.coordinates(), progress);
        return new ProviderPosition(position, 0);
    }

    private ProviderPosition fetchExternalVehiclePosition(String lineId) {
        // 优先使用车辆ID
        String carId = lineIdCarIdMap.get(lineId);
        if (carId != null) {
            ProviderPosition pos = fetchPositionByCarId(carId);
            if (pos != null) return pos;
        }

        // 其次使用车牌
        String plate = lineIdPlateMap.get(lineId);
        if (plate != null) {
            ProviderPosition pos = fetchPositionByPlate(plate);
            if (pos != null) return pos;
        }

        // 都没有或接口失败，返回 null，使用模拟位置
        return null;
    }

    @PostConstruct
    public void testExternalPositionAPI() {
        if (!testOnStartup) return;

        log.info("===== 外部位置接口启动测试开始 =====");

        if (!testPlate.isBlank()) {
            log.info("测试车牌: {}", testPlate);
            try {
                ProviderPosition pos = fetchPositionByPlate(testPlate);
                if (pos != null) {
                    log.info("车牌查询成功: lng={}, lat={}, speed={}", pos.position()[0], pos.position()[1], pos.speedKmh());
                } else {
                    log.warn("车牌查询失败，请检查配置和网络");
                }
            } catch (Exception e) {
                log.warn("车牌查询异常", e);
            }
        }

        if (!testCarId.isBlank()) {
            log.info("测试车辆ID: {}", testCarId);
            try {
                ProviderPosition pos = fetchPositionByCarId(testCarId);
                if (pos != null) {
                    log.info("车辆ID查询成功: lng={}, lat={}, speed={}", pos.position()[0], pos.position()[1], pos.speedKmh());
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

    private record ScheduledRoute(
            String lineId,
            String orderId,
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
            double speedKmh
    ) {
    }

    private record PositionSample(
            double[] position,
            long time
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
