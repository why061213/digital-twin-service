package com.jushen.digitaltwin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.grouping.GroupSummary;
import com.jushen.digitaltwin.grouping.GroupingContext;
import com.jushen.digitaltwin.grouping.OrderAwareRouteInfo;
import com.jushen.digitaltwin.grouping.PathAwareRouteInfo;
import com.jushen.digitaltwin.grouping.ProvinceAwareRouteInfo;
import com.jushen.digitaltwin.grouping.RouteInfo;
import com.jushen.digitaltwin.grouping.RouteGroupingEngine;
import com.jushen.digitaltwin.grouping.RouteGroupingResult;
import com.jushen.digitaltwin.model.City;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import com.jushen.digitaltwin.baidu.RoutePlanningService;
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
import java.util.Locale;
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
import java.util.concurrent.atomic.AtomicLong;
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
    /** 连续零速计数器：key=lineId, value=连续次数 */
    private final Map<String, Integer> zeroSpeedCounters = new ConcurrentHashMap<>();
    private final Map<String, BroadcastPositionState> lastBroadcastPositions = new ConcurrentHashMap<>();
    private final Map<String, String> rm2GroupIdByLineId = new ConcurrentHashMap<>();
    private final AtomicLong positionSequence = new AtomicLong();
    private volatile String rm2SnapshotVersion = "0";
    private static final double MAX_PROVIDER_SPEED_KMH = 140.0;
    private static final double MAX_CALCULATED_SPEED_KMH = 160.0;
    private static final double POSITION_CHANGE_THRESHOLD_METERS = 35.0;
    private static final long POSITION_MAX_SILENCE_MS = 120_000L;
    private static final double MAX_CALIBRATION_OFF_ROUTE_KM = 2.0;
    private static final long DEVIATION_EVENT_COOLDOWN_MS = 2 * 60_000L;
    private static final long DEVIATION_ALERT_TTL_MS = 10 * 60_000L;
    /** 装载中车辆位置偏离阈值（公里），超过此值触发订单刷新 */
    private static final double LOADING_DEPARTURE_THRESHOLD_KM = 1.0;
    /** 装载中车辆的初始位置：lineId → [lng, lat] */
    private final Map<String, double[]> loadingVehiclePositions = new ConcurrentHashMap<>();
    /** 装载中车辆的车牌缓存 */
    private final Map<String, String> loadingVehiclePlateMap = new ConcurrentHashMap<>();
    /** 装载中车辆的carId缓存 */
    private final Map<String, String> loadingVehicleCarIdMap = new ConcurrentHashMap<>();
    /** 装载车辆出发回调（触发订单同步） */
    private volatile Runnable onLoadingVehicleDeparted = null;
    private final boolean passivePositionPushEnabled;
    private final String simulationProfile;
    private final String externalPositionUrl;
    private final double testSimulationSpeedKmh;
    private final double realSimulationSpeedKmh;
    private final int groupSize;
    private final String defaultGroupStrategy;
    private final RouteGroupingEngine routeGroupingEngine;
    private final VehiclePositionCacheService positionCache;
    private final RoutePlanningService routePlanningService;
    private final Map<String, RouteDeviationAlert> routeDeviationAlerts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRouteReplanAt = new ConcurrentHashMap<>();
    private final RouteDeviationClassifier routeDeviationClassifier = new RouteDeviationClassifier();
    private final TruckRoutePatternStore truckRoutePatternStore = new TruckRoutePatternStore();
    private final Map<String, Long> routeCorrectionRevisions = new ConcurrentHashMap<>();
    /** 当前运行时间线的来源；首次真实定位必须覆盖 waiting/旧随机时间线。 */
    private final Map<String, String> routeProgressSources = new ConcurrentHashMap<>();
    /** 订单起终点的原始规划路线始终单独保留，不被车辆偏航后的专属路线覆盖。 */
    private final Map<String, List<double[]>> baselineRouteCoordinates = new ConcurrentHashMap<>();
    /** 每辆车只有一条可见偏航分支；后续偏航会更新它，而不是叠加新颜色。 */
    private final Map<String, List<double[]>> vehicleDeviationCoordinates = new ConcurrentHashMap<>();
    private final String externalPositionToken;
    private final int externalPositionBatchSize;
    private final long vehicleDictionaryRefreshMs;
    private final ConcurrentHashMap<String, String> lineIdPlateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lineIdCarIdMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VehicleRef> vehicleByNormalizedPlate = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VehicleRef> vehicleById = new ConcurrentHashMap<>();
    private final Set<String> unresolvedVehiclePlates = ConcurrentHashMap.newKeySet();
    private volatile boolean vehicleDictionaryInitialized;
    private volatile long vehicleDictionaryLoadedAt;
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
            VehiclePositionCacheService positionCache,
            RoutePlanningService routePlanningService,
            @Value("${dashboard.route.passive-position-push-enabled:false}") boolean passivePositionPushEnabled,
            @Value("${dashboard.route.simulation-profile:test}") String simulationProfile,
            @Value("${dashboard.route.external-position-url:}") String externalPositionUrl,
            @Value("${dashboard.route.test.simulation-speed-kmh:120}") double testSimulationSpeedKmh,
            @Value("${dashboard.route.real.simulation-speed-kmh:80}") double realSimulationSpeedKmh,
            @Value("${dashboard.route.group-size:5}") int groupSize,
            @Value("${dashboard.route.default-group-strategy:sequential}") String defaultGroupStrategy,
            @Value("${dashboard.route.external-position-token:}") String externalPositionToken,
            @Value("${dashboard.route.external-position-batch-size:50}") int externalPositionBatchSize,
            @Value("${dashboard.route.vehicle-dictionary-refresh-ms:0}") long vehicleDictionaryRefreshMs,
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
        this.positionCache = positionCache;
        this.routePlanningService = routePlanningService;
        this.passivePositionPushEnabled = passivePositionPushEnabled;
        this.simulationProfile = simulationProfile;
        this.externalPositionUrl = externalPositionUrl;
        this.testSimulationSpeedKmh = testSimulationSpeedKmh;
        this.realSimulationSpeedKmh = realSimulationSpeedKmh;
        this.groupSize = Math.max(1, groupSize);
        this.defaultGroupStrategy = defaultGroupStrategy;
        this.externalPositionToken = externalPositionToken;
        this.externalPositionBatchSize = externalPositionBatchSize;
        this.vehicleDictionaryRefreshMs = Math.max(0, vehicleDictionaryRefreshMs);
        this.testPlate = testPlate;
        this.testCarId = testCarId;
        this.testOnStartup = testOnStartup;
        this.authEnabled = authEnabled;
        this.authUrl = authUrl;
        this.authId = authId;
        this.authSecret = authSecret;
        this.tokenCacheEnabled = tokenCacheEnabled;
        this.tokenCachePathStr = tokenCachePathStr;
        log.info("[VehicleDictionary] refreshIntervalMs={} (0 means load once per process)", this.vehicleDictionaryRefreshMs);
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


    /**
     * 获取有效的 accessToken（公开方法，供 VehiclePositionCacheService 调用）。
     */
    public String getAccessTokenForExternal() throws IOException, InterruptedException {
        return getAccessToken();
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
                from.name(),
                to.name(),
                coordinates,
                coordinates,
                pathKey(from.name(), to.name(), coordinates),
                System.currentTimeMillis(),
                routeLengthKm,
                speedKmh,
                travelDurationMs,
                RouteScope.ROAD
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
                from,
                to,
                coordinates,
                coordinates,
                pathKey,
                System.currentTimeMillis(),
                routeLengthKm,
                speedKmh,
                travelDurationMs,
                RouteScope.ROAD
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
        response.put("scope", "rm1");
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
        response.put("scope", "rm1");
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

        VehicleRef vehicleRef = resolveVehicleByPlate(plate);
        if (vehicleRef == null) {
            String plateKey = normalizePlateKey(plate);
            if (unresolvedVehiclePlates.add(plateKey)) {
                log.warn("Vehicle dictionary has no vehicleId for plate={}; using simulated position", plate);
            }
            return null;
        }
        unresolvedVehiclePlates.remove(normalizePlateKey(plate));

        ProviderPosition position = fetchPositionByCarId(vehicleRef.vehicleId());
        if (position != null) {
            log.info("Plate mapped to vehicleId: queryPlate={}, providerVehicleId={}, providerVehicleName={}",
                    plate, vehicleRef.vehicleId(), vehicleRef.vehicleName());
            return position;
        }
        log.warn("VehicleId position lookup failed: queryPlate={}, providerVehicleId={}, providerVehicleName={}",
                plate, vehicleRef.vehicleId(), vehicleRef.vehicleName());
        return null;
    }

    private VehicleRef resolveVehicleByPlate(String plate) {
        if (plate == null || plate.isBlank()) return null;
        ensureVehicleDictionary();
        return vehicleByNormalizedPlate.get(normalizePlateKey(plate));
    }

    private void ensureVehicleDictionary() {
        long now = System.currentTimeMillis();
        boolean refreshDue = vehicleDictionaryRefreshMs > 0
                && now - vehicleDictionaryLoadedAt >= vehicleDictionaryRefreshMs;
        if (!vehicleDictionaryInitialized || refreshDue) {
            refreshVehicleDictionary();
        }
    }

    private synchronized void refreshVehicleDictionary() {
        if (!externalPositionConfigured()) {
            vehicleDictionaryInitialized = true;
            vehicleDictionaryLoadedAt = System.currentTimeMillis();
            return;
        }

        try {
            String token = getAccessToken();
            String url = externalPositionUrl + "/video/webapi/vehicle/list";
            int size = Math.max(100_000, externalPositionBatchSize);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("page", 1);
            body.put("size", size);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", token)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Vehicle list API returned status {}", response.statusCode());
                return;
            }

            Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
            if (!(result.get("code") instanceof Number code) || code.intValue() != 200) {
                log.warn("Vehicle list API code not 200: {}", result);
                return;
            }

            Map<String, Object> dataBlock = (Map<String, Object>) result.get("data");
            List<Map<String, Object>> pageList = dataBlock == null
                    ? List.of()
                    : (List<Map<String, Object>>) dataBlock.get("pageList");
            if (pageList == null) {
                pageList = List.of();
            }
            ConcurrentHashMap<String, VehicleRef> next = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, VehicleRef> nextById = new ConcurrentHashMap<>();
            for (Map<String, Object> vehicle : pageList) {
                String vehicleId = stringValue(vehicle.get("vehicle_id"));
                String vehicleName = stringValue(vehicle.get("vehicle_name"));
                if (vehicleId == null || vehicleName == null) continue;
                VehicleRef ref = new VehicleRef(vehicleId, vehicleName);
                nextById.put(vehicleId, ref);
                registerVehicleRef(next, normalizePlateKey(vehicleName), ref);
                registerVehicleRef(next, normalizePlateBaseKey(vehicleName), ref);
            }

            vehicleByNormalizedPlate.clear();
            vehicleByNormalizedPlate.putAll(next);
            vehicleById.clear();
            vehicleById.putAll(nextById);
            log.info("Vehicle dictionary loaded: total={}, pageList={}, indexKeys={}, idKeys={}, requestSize={}",
                    dataBlock == null ? null : dataBlock.get("total"), pageList.size(),
                    vehicleByNormalizedPlate.size(), vehicleById.size(), size);
        } catch (Exception e) {
            log.warn("Failed to refresh vehicle dictionary", e);
        } finally {
            vehicleDictionaryInitialized = true;
            vehicleDictionaryLoadedAt = System.currentTimeMillis();
        }
    }

    private void registerVehicleRef(Map<String, VehicleRef> index, String key, VehicleRef vehicleRef) {
        if (key == null || key.isBlank()) return;
        index.putIfAbsent(key, vehicleRef);
    }

    private String normalizePlateKey(String plate) {
        if (plate == null) return "";
        return plate.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[\\s.·•・—–_-]", "");
    }

    private String normalizePlateBaseKey(String plate) {
        if (plate == null) return "";
        String normalized = plate.trim().toUpperCase(Locale.ROOT);
        int separatorIndex = firstSeparatorIndex(normalized);
        return normalizePlateKey(separatorIndex > 0 ? normalized.substring(0, separatorIndex) : normalized);
    }

    private int firstSeparatorIndex(String value) {
        int result = -1;
        for (char separator : new char[]{'-', '—', '–'}) {
            int index = value.indexOf(separator);
            if (index > 0 && (result < 0 || index < result)) result = index;
        }
        return result;
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
                stringValue(vehicle.get("vehicle_name")),
                stringValue(vehicle.get("driverNameIC")),
                stringValue(vehicle.get("adree")),
                stringValue(vehicle.get("state_str")),
                integerValue(vehicle.get("dir")),
                stringValue(vehicle.get("dir_str")),
                stringValue(vehicle.get("alarm_str")),
                booleanValue(vehicle.get("online"))
        );
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Integer integerValue(Object value) {
        if (value == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Boolean booleanValue(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean booleanValue) return booleanValue;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        return null;
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

    /**
     * 批量 REST 校准专用：只读位置缓存；未命中时给出模拟坐标，绝不逐车穿透外部接口。
     */
    public Map<String, Object> getCachedOrSimulatedPosition(String lineId) {
        long now = System.currentTimeMillis();
        ScheduledRoute route = activeRoutes.get(lineId);
        if (route == null) return Map.of("type", "truck_position", "lineId", lineId, "status", "finished");

        PositionSnapshot cached = positionCache.getPosition(lineId);
        boolean simulated = cached == null;
        ProviderPosition position = cached == null
                ? simulatedPosition(route, now)
                : new ProviderPosition(cached.position(), cached.speedKmh(), cached.vehicleId(), cached.vehicleName());
        double progress = routeProgress(route, now);
        double effectiveSpeedKmh = simulated
                ? (now >= route.startTime() && progress < 1.0 ? normalizedRouteSpeed(route.speedKmh()) : 0)
                : Math.max(0, position.speedKmh());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "truck_position");
        message.put("lineId", lineId);
        message.put("scope", scopeName(route.scope()));
        message.put("groupId", positionGroupId(route));
        message.put("snapshotVersion", route.scope() == RouteScope.TOWN ? rm2SnapshotVersion : "");
        message.put("position", position.position());
        message.put("velocity", now < route.startTime() || progress >= 1.0
                ? new double[]{0, 0}
                : velocityFromSpeed(route.coordinates(), progress, effectiveSpeedKmh));
        message.put("speedKmh", effectiveSpeedKmh);
        message.put("progress", progress);
        message.put("status", progress >= 1.0 ? "finished" : "running");
        message.put("source", simulated ? "simulated" : cached.source());
        message.put("stale", cached != null && cached.stale());
        message.put("fetchedAt", cached == null ? Instant.ofEpochMilli(now).toString() : cached.fetchedAt().toString());
        message.put("vehicleId", position.vehicleId() == null ? lineIdCarIdMap.get(lineId) : position.vehicleId());
        message.put("plate", lineIdPlateMap.get(lineId));
        message.put("speedQuality", simulated ? "fallback" : "provider");
        addProviderPositionDetails(message, cached);
        if (simulated) addSimulatedDirectionDetails(message, route.coordinates(), progress);
        addRouteCorrectionDetails(message, route);
        message.put("sequence", positionSequence.incrementAndGet());
        return message;
    }

    public String rm2SnapshotVersion() {
        return rm2SnapshotVersion;
    }

    /** 供 RM2 快照复用运行池已经确定的速度、距离和预测时长。 */
    public RouteRuntimeMetrics routeRuntimeMetrics(String lineId) {
        ScheduledRoute route = activeRoutes.get(lineId);
        if (route == null) return null;
        PositionSnapshot snapshot = positionCache.getPosition(lineId);
        String vehicleId = lineIdCarIdMap.get(lineId);
        String plate = lineIdPlateMap.get(lineId);
        if (snapshot != null) {
            if (snapshot.vehicleId() != null && !snapshot.vehicleId().isBlank()) {
                vehicleId = snapshot.vehicleId();
            }
            if (snapshot.plate() != null && !snapshot.plate().isBlank()) {
                plate = normalizePlateDisplay(snapshot.plate());
            } else if (snapshot.vehicleName() != null && !snapshot.vehicleName().isBlank()) {
                plate = normalizePlateDisplay(snapshot.vehicleName());
            }
        }
        double effectiveSpeedKmh = snapshot != null && isProviderSpeed(snapshot.speedKmh())
                ? snapshot.speedKmh()
                : route.speedKmh();
        // 路线时长来自导航规划；当前瞬时速度只用于车辆运动，不能反推并覆盖 ETA。
        long effectiveTravelDurationMs = route.travelDurationMs();
        return new RouteRuntimeMetrics(
                effectiveSpeedKmh,
                route.routeLengthKm(),
                effectiveTravelDurationMs,
                vehicleId,
                plate,
                snapshot != null && !snapshot.stale(),
                snapshot == null ? null : snapshot.source(),
                route.coordinates(),
                route.pathKey()
        );
    }

    public boolean hasFreshProviderPosition(String lineId) {
        PositionSnapshot snapshot = positionCache.getPosition(lineId);
        return snapshot != null && !snapshot.stale()
                && snapshot.position() != null && snapshot.position().length >= 2
                && "real".equals(snapshot.source());
    }

    /** 路线初始化前只读已经批量拉取的真实位置，不触发单车接口。 */
    public PositionSnapshot freshProviderPosition(String lineId) {
        PositionSnapshot snapshot = positionCache.getPosition(lineId);
        return snapshot != null && !snapshot.stale() && "real".equals(snapshot.source())
                ? snapshot
                : null;
    }

    public boolean hasProviderVehicleId(String lineId) {
        String vehicleId = lineIdCarIdMap.get(lineId);
        return vehicleId != null && !vehicleId.isBlank();
    }

    public Map<String, Object> warmPositionCacheForLineIds(Set<String> lineIds) {
        if (lineIds == null || lineIds.isEmpty() || !positionCache.isEnabled()) {
            return Map.of("skipped", true, "reason", "disabled-or-empty");
        }
        Map<String, Set<String>> vehicleToLineIds = new LinkedHashMap<>();
        for (String lineId : lineIds) {
            if (lineId == null || lineId.isBlank()) continue;
            String vehicleId = lineIdCarIdMap.get(lineId);
            if (vehicleId == null || vehicleId.isBlank()) continue;
            vehicleToLineIds.computeIfAbsent(vehicleId, ignored -> new LinkedHashSet<>()).add(lineId);
        }
        return positionCache.runBatchRefresh(this::getAccessTokenForExternalSafe, vehicleToLineIds);
    }

    /** 订单快照预热位置后立即校准已有路线，避免等下一轮定时刷新。 */
    public int calibratePreparedRoutesFromCache() {
        return calibrateActiveRoutesFromCache(System.currentTimeMillis());
    }

    @Scheduled(
            initialDelayString = "${dashboard.route.position-refresh.initial-delay-ms:10000}",
            fixedDelayString = "${dashboard.route.position-refresh.fixed-delay-ms:30000}"
    )
    public void scheduledPositionRefresh() {
        if (!passivePositionPushEnabled || !positionCache.isEnabled()) return;

        long now = System.currentTimeMillis();
        cleanupExpiredRoutes(now);

        Map<String, Set<String>> vehicleToLineIds = collectActiveTransportVehicleMap();
        // 装载中车辆也纳入位置刷新，用于检测是否已出发
        for (Map.Entry<String, double[]> entry : loadingVehiclePositions.entrySet()) {
            String lineId = entry.getKey();
            String vehicleId = loadingVehicleCarIdMap.get(lineId);
            if (vehicleId != null && !vehicleId.isBlank()) {
                vehicleToLineIds.computeIfAbsent(vehicleId, k -> new LinkedHashSet<>()).add(lineId);
            }
        }
        Map<String, Object> summary = positionCache.runBatchRefresh(this::getAccessTokenForExternalSafe, vehicleToLineIds);
        int calibratedRouteCount = calibrateActiveRoutesFromCache(now);
        if (calibratedRouteCount > 0) {
            log.info("[PositionCache] calibrated active simulations: routeCount={}, refreshSummary={}",
                    calibratedRouteCount, summary);
        }

        // 检测装载中车辆是否已出发（偏离初始位置超过阈值）
        checkLoadingVehicleDepartures();

        // 每个 scope 一轮最多一个位置帧，避免逐车 WebSocket 广播风暴。
        if (passivePositionPushEnabled) {
            broadcastChangedPositionFrames(now);
        }
    }

    /** 检查装载中车辆是否偏离初始位置超过阈值，若是则触发订单刷新。 */
    private void checkLoadingVehicleDepartures() {
        if (loadingVehiclePositions.isEmpty()) return;
        List<String> departedLineIds = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : loadingVehiclePositions.entrySet()) {
            String lineId = entry.getKey();
            double[] initialPos = entry.getValue();
            PositionSnapshot snapshot = positionCache.getPosition(lineId);
            if (snapshot == null || snapshot.position() == null || snapshot.position().length < 2) continue;

            double distanceKm = distanceKm(initialPos, snapshot.position());
            if (distanceKm > LOADING_DEPARTURE_THRESHOLD_KM) {
                log.info("[PositionCache] loading vehicle departed: lineId={}, plate={}, distanceKm={}",
                        lineId, loadingVehiclePlateMap.getOrDefault(lineId, "?"), distanceKm);
                departedLineIds.add(lineId);
            }
        }
        if (!departedLineIds.isEmpty()) {
            departedLineIds.forEach(lineId -> {
                loadingVehiclePositions.remove(lineId);
                loadingVehiclePlateMap.remove(lineId);
                loadingVehicleCarIdMap.remove(lineId);
            });
            Runnable callback = onLoadingVehicleDeparted;
            if (callback != null) {
                try {
                    callback.run();
                } catch (Exception e) {
                    log.warn("[PositionCache] loading vehicle departure callback failed", e);
                }
            }
        }
    }

    /**
     * 用已批量拉取的真实位置修正后端模拟时间线。
     * 缓存读取不再触发外部请求，且偏离规划路线过远的点不会污染模拟状态。
     */
    private int calibrateActiveRoutesFromCache(long now) {
        int calibrated = 0;
        Map<String, PositionSnapshot> initialSnapshots = new LinkedHashMap<>();
        for (String lineId : activeRoutes.keySet()) {
            PositionSnapshot snapshot = positionCache.getPosition(lineId);
            if (snapshot != null) initialSnapshots.put(lineId, snapshot);
        }
        Map<String, Object> deviationConfirmationRefresh = null;
        for (Map.Entry<String, ScheduledRoute> entry : activeRoutes.entrySet()) {
            String lineId = entry.getKey();
            ScheduledRoute route = entry.getValue();
            PositionSnapshot snapshot = initialSnapshots.get(lineId);
            if (snapshot == null || snapshot.position() == null || snapshot.position().length < 2) {
                continue;
            }

            // 动态校准间隔
            double progress = route.travelDurationMs() > 0
                    ? (double)(now - route.startTime()) / route.travelDurationMs()
                    : 0;
            progress = Math.max(0, Math.min(1, progress));
            long customIntervalMs = positionCache.calibrationIntervalMs(
                    route.routeLengthKm(), progress, route.speedKmh());

            // 连续两次速度为0 → 停车状态，降低到30s
            double snapshotSpeed = snapshot.speedKmh();
            if (snapshotSpeed <= 0) {
                int zeroCount = zeroSpeedCounters.merge(lineId, 1, Integer::sum);
                if (zeroCount >= 2) {
                    customIntervalMs = Math.max(customIntervalMs, 30_000);
                }
            } else {
                zeroSpeedCounters.remove(lineId);
            }

            if (positionCache.isStale(snapshot, customIntervalMs)) {
                continue;
            }

            boolean firstRealCalibration = !"real-provider".equals(routeProgressSources.get(lineId));
            double hintProgress = calibrationHintProgress(routeProgressSources.get(lineId), progress);
            double pathProgress = progressOnCoordinates(
                    route.matchingCoordinates(), snapshot.position(), hintProgress);
            double[] currentRouteProjected = coordinateAtProgress(route.matchingCoordinates(), pathProgress);
            double offCurrentRouteKm = distanceKm(currentRouteProjected, snapshot.position());
            List<double[]> baselineCoordinates = baselineRouteCoordinates.getOrDefault(
                    lineId, route.matchingCoordinates());
            double baselineProgress = progressOnCoordinates(
                    baselineCoordinates, snapshot.position(), hintProgress);
            double[] projected = coordinateAtProgress(baselineCoordinates, baselineProgress);
            double offRouteKm = distanceKm(projected, snapshot.position());
            if (offRouteKm > MAX_CALIBRATION_OFF_ROUTE_KM) {
                if (deviationConfirmationRefresh == null) {
                    deviationConfirmationRefresh = warmPositionCacheForLineIds(activeRoutes.keySet());
                }
                PositionSnapshot confirmedSnapshot = confirmOffRoutePosition(
                        lineId, route, snapshot.fetchedAt(), offRouteKm, deviationConfirmationRefresh);
                if (confirmedSnapshot == null) continue;
                snapshot = confirmedSnapshot;
                pathProgress = progressOnCoordinates(
                        route.matchingCoordinates(), snapshot.position(), firstRealCalibration ? -1 : progress);
                currentRouteProjected = coordinateAtProgress(route.matchingCoordinates(), pathProgress);
                offCurrentRouteKm = distanceKm(currentRouteProjected, snapshot.position());
                baselineProgress = progressOnCoordinates(
                        baselineCoordinates, snapshot.position(), firstRealCalibration ? -1 : progress);
                projected = coordinateAtProgress(baselineCoordinates, baselineProgress);
                offRouteKm = distanceKm(projected, snapshot.position());
                if (offRouteKm <= MAX_CALIBRATION_OFF_ROUTE_KM) {
                    log.info("[RouteCorrection] stale prediction cleared after refresh: lineId={}, confirmedDistanceKm={}",
                            lineId, String.format(Locale.ROOT, "%.3f", offRouteKm));
                    continue;
                }
                classifyRouteDeviation(
                        route, snapshot, baselineCoordinates, baselineProgress,
                        pathProgress, offRouteKm);
                if (routeCorrectionRevisions.containsKey(lineId)
                        && offCurrentRouteKm <= MAX_CALIBRATION_OFF_ROUTE_KM) {
                    // 车辆仍沿上次修正路线行驶：更新分类证据，不重复调用规划 API。
                    routeCorrectionRevisions.put(lineId, now);
                    routeProgressSources.put(lineId, "real-provider");
                    lastBroadcastPositions.remove(lineId);
                    calibrated++;
                    continue;
                }
                Long previousReplanAt = lastRouteReplanAt.get(lineId);
                if (previousReplanAt != null && now - previousReplanAt < DEVIATION_EVENT_COOLDOWN_MS) {
                    continue;
                }
                lastRouteReplanAt.put(lineId, now);
                RoutePlanningService.PlannedRoute replanned = routePlanningService.plan(
                        baselineCoordinates.get(0),
                        baselineCoordinates.get(baselineCoordinates.size() - 1),
                        List.of(snapshot.position()));
                if (replanned.success()) {
                    List<double[]> correctedCoordinates = replanned.coordinates();
                    List<double[]> correctedMatchingCoordinates = replanned.matchingCoordinates();
                    double correctedProgress = progressOnCoordinates(
                            correctedMatchingCoordinates, snapshot.position(), progress);
                    double totalDistanceKm = replanned.distanceKm() > 0
                            ? replanned.distanceKm()
                            : pathLengthKm(correctedMatchingCoordinates);
                    double completedDistanceKm = correctedProgress * totalDistanceKm;
                    double effectiveSpeedKmh = isProviderSpeed(snapshot.speedKmh())
                            ? snapshot.speedKmh()
                            : normalizedRouteSpeed(route.speedKmh());
                    long durationMs = replanned.durationMs() > 0
                            ? replanned.durationMs()
                            : travelDurationMs(totalDistanceKm, effectiveSpeedKmh);
                    long completedDurationMs = Math.round(correctedProgress * durationMs);
                    long correctedStartTime = now - completedDurationMs;
                    List<double[]> deviationCoordinates = RouteDeviationPathBuilder.extract(
                            baselineCoordinates, correctedMatchingCoordinates);
                    ScheduledRoute correctedRoute = new ScheduledRoute(
                            route.lineId(), route.orderId(), route.orderFamilyId(), route.orderName(),
                            route.orderTotalTons(), route.orderVehicleCount(), route.from(), route.to(),
                            route.startProvince(), route.endProvince(), correctedCoordinates,
                            correctedMatchingCoordinates,
                            "vehicle-route::" + lineId, correctedStartTime,
                            totalDistanceKm, effectiveSpeedKmh, durationMs, route.scope());
                    activeRoutes.replace(lineId, route, correctedRoute);
                    routeProgressSources.put(lineId, "real-provider");
                    vehicleDeviationCoordinates.put(lineId,
                            RoutePlanningService.PlannedRoute.simplifyForRendering(deviationCoordinates, 240));
                    routeCorrectionRevisions.put(lineId, now);
                    lastBroadcastPositions.remove(lineId);
                    if (route.scope() == RouteScope.ROAD) {
                        webSocketHandler.broadcast(routeMessage(correctedRoute, false));
                    }
                    log.warn("[RouteCorrection] replanned remaining route with fixed order endpoints: lineId={}, provider={}, offRouteKm={}, completedKm={}, totalKm={}, progress={}%, durationMs={}",
                            lineId, replanned.provider(), String.format(Locale.ROOT, "%.3f", offRouteKm),
                            String.format(Locale.ROOT, "%.2f", completedDistanceKm),
                            String.format(Locale.ROOT, "%.2f", totalDistanceKm),
                            Math.round(correctedProgress * 100), durationMs);
                    calibrated++;
                } else {
                    log.warn("[RouteCorrection] replan failed: lineId={}, offRouteKm={}, reason={}",
                            lineId, String.format(Locale.ROOT, "%.3f", offRouteKm), replanned.error());
                }
                continue;
            }

            // 回到高精度基准路线时立即清除偏移事件和报警；不能等待报警 TTL 自然过期。
            classifyRouteDeviation(
                    route, snapshot, baselineCoordinates, baselineProgress,
                    pathProgress, offRouteKm);

            double effectiveSpeedKmh = isProviderSpeed(snapshot.speedKmh())
                    ? snapshot.speedKmh()
                    : normalizedRouteSpeed(route.speedKmh());
            long travelDurationMs = travelDurationMs(route.routeLengthKm(), effectiveSpeedKmh);
            long startTime = now - Math.round(pathProgress * travelDurationMs);
            ScheduledRoute calibratedRoute = new ScheduledRoute(
                    route.lineId(), route.orderId(), route.orderFamilyId(), route.orderName(),
                    route.orderTotalTons(), route.orderVehicleCount(), route.from(), route.to(),
                    route.startProvince(), route.endProvince(), route.coordinates(), route.matchingCoordinates(), route.pathKey(),
                    startTime, route.routeLengthKm(), effectiveSpeedKmh, travelDurationMs, route.scope()
            );
            activeRoutes.replace(lineId, route, calibratedRoute);
            routeProgressSources.put(lineId, "real-provider");
            calibrated++;
        }
        return calibrated;
    }

    private PositionSnapshot confirmOffRoutePosition(
            String lineId,
            ScheduledRoute route,
            Instant previousFetchedAt,
            double predictedDistanceKm,
            Map<String, Object> refresh
    ) {
        PositionSnapshot confirmed = positionCache.getPosition(lineId);
        if (confirmed == null || confirmed.stale() || confirmed.position() == null || confirmed.position().length < 2) {
            log.warn("[RouteCorrection] confirmation unavailable; ignored suspected deviation: lineId={}, predictedDistanceKm={}, refresh={}",
                    lineId, String.format(Locale.ROOT, "%.3f", predictedDistanceKm), refresh);
            return null;
        }
        if (previousFetchedAt != null && (confirmed.fetchedAt() == null
                || !confirmed.fetchedAt().isAfter(previousFetchedAt))) {
            log.warn("[RouteCorrection] confirmation did not return a newer provider sample; ignored suspected deviation: lineId={}, previousFetchedAt={}, confirmedFetchedAt={}, refresh={}",
                    lineId, previousFetchedAt, confirmed.fetchedAt(), refresh);
            return null;
        }
        double confirmedProgress = progressOnCoordinates(route.matchingCoordinates(), confirmed.position());
        double confirmedDistanceKm = distanceKm(
                coordinateAtProgress(route.matchingCoordinates(), confirmedProgress), confirmed.position());
        log.info("[RouteCorrection] deviation confirmation: lineId={}, predictedDistanceKm={}, confirmedDistanceKm={}, source={}",
                lineId, String.format(Locale.ROOT, "%.3f", predictedDistanceKm),
                String.format(Locale.ROOT, "%.3f", confirmedDistanceKm), confirmed.source());
        return confirmed;
    }

    /**
     * 收集所有运输中的活跃线路，按 vehicleId 去重映射。
     */
    public Map<String, Set<String>> collectActiveTransportVehicleMap() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ScheduledRoute> entry : activeRoutes.entrySet()) {
            String lineId = entry.getKey();
            ScheduledRoute route = entry.getValue();
            if (route.scope() != RouteScope.TOWN && route.scope() != RouteScope.ROAD) continue;

            String vehicleId = lineIdCarIdMap.get(lineId);
            if (vehicleId == null || vehicleId.isBlank()) continue;

            result.computeIfAbsent(vehicleId, k -> new LinkedHashSet<>()).add(lineId);
        }
        return result;
    }

    /** RM2 快照发布时同步稳定的 lineId → groupId 索引，供位置帧补齐展示身份。 */
    public void syncRm2PositionGroups(Map<String, String> groupIdByLineId, String snapshotVersion) {
        rm2GroupIdByLineId.clear();
        if (groupIdByLineId != null) rm2GroupIdByLineId.putAll(groupIdByLineId);
        rm2SnapshotVersion = snapshotVersion == null || snapshotVersion.isBlank() ? "0" : snapshotVersion;
    }

    /** 注册装载中车辆的初始位置，后续位置刷新时检测是否已出发。 */
    public void trackLoadingVehicle(String lineId, String plate, String carId, double[] position) {
        if (lineId == null || position == null || position.length < 2) return;
        loadingVehiclePositions.putIfAbsent(lineId, new double[]{position[0], position[1]});
        if (plate != null && !plate.isBlank()) loadingVehiclePlateMap.put(lineId, plate);
        if (carId != null && !carId.isBlank()) loadingVehicleCarIdMap.put(lineId, carId);
        rememberProviderVehicleId(lineId, plate, carId);
    }

    /** 装载车辆出发时回调（供 TownRoadRenderService 注入订单刷新逻辑）。 */
    public void setOnLoadingVehicleDeparted(Runnable callback) {
        this.onLoadingVehicleDeparted = callback;
    }

    private String getAccessTokenForExternalSafe() {
        try {
            return getAccessToken();
        } catch (Exception e) {
            log.warn("Failed to get access token for position refresh", e);
            return null;
        }
    }

    /** @deprecated 由 scheduledPositionRefresh 替代 */
    @Deprecated
    public void pushPassiveTruckPositions() {
        // 保留空实现以兼容 @Scheduled 引用
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
        PositionSnapshot cached = positionCache.getPosition(route.lineId());
        boolean simulated = cached == null;
        double progress = routeProgress(route, now);
        double providerSpeed = providerPosition.speedKmh();
        String speedQuality = "fallback";
        double speedKmh;
        if (waitingDeparture || progress >= 1.0) {
            speedKmh = 0;
            speedQuality = "fallback";
        } else if (simulated) {
            speedKmh = normalizedRouteSpeed(route.speedKmh());
            speedQuality = "fallback";
        } else if (providerSpeed > 0 && providerSpeed <= MAX_PROVIDER_SPEED_KMH) {
            speedKmh = providerSpeed;
            speedQuality = "provider";
        } else {
            double calculated = calculateSpeedKmh(route.lineId(), providerPosition.position(), now, route.speedKmh());
            if (calculated > 0 && calculated <= MAX_CALCULATED_SPEED_KMH) {
                speedKmh = calculated;
                speedQuality = "calculated";
            } else {
                speedKmh = Math.max(0, route.speedKmh());
                speedQuality = providerSpeed > MAX_PROVIDER_SPEED_KMH || calculated > MAX_CALCULATED_SPEED_KMH
                        ? "rejected" : "fallback";
            }
        }

        double[] velocity = waitingDeparture ? new double[]{0, 0} : velocityFromSpeed(route.coordinates(), progress, speedKmh);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "truck_position");
        message.put("lineId", route.lineId());
        message.put("scope", scopeName(route.scope()));
        message.put("groupId", positionGroupId(route));
        message.put("position", providerPosition.position());
        message.put("velocity", velocity);
        message.put("speedKmh", speedKmh);
        message.put("progress", progress);
        message.put("status", progress >= 1.0 ? "finished" : "running");
        message.put("source", cached == null ? "simulated" : cached.source());
        message.put("stale", cached != null && cached.stale());
        message.put("fetchedAt", cached == null ? Instant.ofEpochMilli(now).toString() : cached.fetchedAt().toString());
        message.put("vehicleId", providerPosition.vehicleId() == null ? lineIdCarIdMap.get(route.lineId()) : providerPosition.vehicleId());
        message.put("plate", lineIdPlateMap.get(route.lineId()));
        message.put("speedQuality", speedQuality);
        addProviderPositionDetails(message, cached);
        if (simulated) addSimulatedDirectionDetails(message, route.coordinates(), progress);
        addRouteCorrectionDetails(message, route);
        message.put("sequence", positionSequence.incrementAndGet());
        return message;
    }

    private void addProviderPositionDetails(Map<String, Object> message, PositionSnapshot snapshot) {
        if (snapshot == null) return;
        message.put("driverName", snapshot.driverName());
        message.put("address", snapshot.address());
        message.put("stateStr", snapshot.stateStr());
        message.put("alarmStr", snapshot.alarmStr());
        message.put("alarmSeverity", snapshot.alarmSeverity());
        message.put("online", snapshot.online());
        message.put("directionDeg", snapshot.directionDeg());
        message.put("directionLabel", snapshot.directionLabel());
        applyRouteDeviationAlert(message, snapshot.lineId(), System.currentTimeMillis());
    }

    private RouteDeviationClassifier.Decision classifyRouteDeviation(
            ScheduledRoute route,
            PositionSnapshot snapshot,
            List<double[]> baselineCoordinates,
            double baselineProgress,
            double routeProgress,
            double distanceKm
    ) {
        String baselineSignature = pathKey(route.from(), route.to(), baselineCoordinates);
        String plate = lineIdPlateMap.get(route.lineId());
        List<double[]> branch = vehicleDeviationCoordinates.getOrDefault(route.lineId(), List.of());
        double departureProgress = branch.isEmpty()
                ? baselineProgress
                : progressOnCoordinates(baselineCoordinates, branch.get(0), baselineProgress);
        boolean expectedPattern = truckRoutePatternStore.isExpected(
                baselineSignature, departureProgress, branch, plate, route.orderId());
        RouteDeviationClassifier.Decision decision = routeDeviationClassifier.observe(
                route.lineId(),
                new RouteDeviationClassifier.Sample(
                        snapshot.providerTime(), snapshot.position(), baselineProgress, routeProgress,
                        distanceKm(snapshot.position(), baselineCoordinates.get(baselineCoordinates.size() - 1)),
                        distanceKm, snapshot.speedKmh(), snapshot.directionDeg(), snapshot.stale()),
                expectedPattern);
        if (decision.state() == RouteDeviationClassifier.State.ALTERNATIVE
                || decision.state() == RouteDeviationClassifier.State.EXPECTED) {
            truckRoutePatternStore.recordAlternative(
                    baselineSignature, departureProgress, branch, plate, route.orderId());
        }
        if (decision.shouldWarn()) {
            String severity = decision.shouldCritical() ? "critical" : "warning";
            routeDeviationAlerts.put(route.lineId(), new RouteDeviationAlert(
                    severity, System.currentTimeMillis(), decision.anomalyStreak(), distanceKm));
        } else {
            routeDeviationAlerts.remove(route.lineId());
        }
        log.info("[RouteDeviation] classified: lineId={}, state={}, reason={}, confidence={}, anomalyScore={}, streak={}, distanceKm={}",
                route.lineId(), decision.state(), decision.reasonCode(),
                String.format(Locale.ROOT, "%.2f", decision.confidence()),
                decision.anomalyScore(), decision.anomalyStreak(),
                String.format(Locale.ROOT, "%.3f", distanceKm));
        return decision;
    }

    private void applyRouteDeviationAlert(Map<String, Object> message, String lineId, long now) {
        RouteDeviationClassifier.Decision decision = routeDeviationClassifier.decision(lineId);
        if (decision != null) {
            message.put("routeDeviationState", decision.state().name());
            message.put("routeDeviationReasonCode", decision.reasonCode());
            message.put("routeDeviationConfidence", decision.confidence());
            message.put("routeAnomalyScore", decision.anomalyScore());
            message.put("routeDeviationSampleCount", decision.sampleCount());
        }
        RouteDeviationAlert alert = routeDeviationAlerts.get(lineId);
        if (alert == null) return;
        if (now - alert.lastDeviationAt() >     DEVIATION_ALERT_TTL_MS) {
            routeDeviationAlerts.remove(lineId, alert);
            return;
        }
        String existingSeverity = String.valueOf(message.getOrDefault("alarmSeverity", "none"));
        if ("critical".equals(existingSeverity) && !"critical".equals(alert.severity())) return;
        String existingAlarm = String.valueOf(message.getOrDefault("alarmStr", ""));
        message.put("alarmStr", existingAlarm == null || existingAlarm.isBlank() || "null".equals(existingAlarm)
                ? "路线偏移" : existingAlarm + " · 路线偏移");
        message.put("alarmSeverity", alert.severity());
        message.put("routeDeviationCount", alert.count());
        message.put("routeDeviationDistanceKm", alert.distanceKm());
    }

    private void addSimulatedDirectionDetails(
            Map<String, Object> message,
            List<double[]> coordinates,
            double progress
    ) {
        Integer directionDeg = directionAtProgress(coordinates, progress);
        if (directionDeg == null) return;
        message.put("directionDeg", directionDeg);
        message.put("directionLabel", directionLabel(directionDeg));
    }

    private void addRouteCorrectionDetails(Map<String, Object> message, ScheduledRoute route) {
        Long revision = routeCorrectionRevisions.get(route.lineId());
        if (revision == null) return;
        message.put("routeRevision", revision);
        message.put("routeCoordinates", route.coordinates());
        message.put("routeLengthKm", route.routeLengthKm());
        message.put("travelDurationMs", route.travelDurationMs());
        message.put("pathKey", route.pathKey());
        RouteDeviationClassifier.Decision decision = routeDeviationClassifier.decision(route.lineId());
        boolean highlightedBranch = decision != null && switch (decision.state()) {
            case ALTERNATIVE, EXPECTED, ANOMALOUS -> true;
            default -> false;
        };
        message.put("deviationCoordinates", highlightedBranch
                ? vehicleDeviationCoordinates.getOrDefault(route.lineId(), List.of())
                : List.of());
        message.put("colorKey", highlightedBranch
                ? "branch:" + route.lineId()
                : route.orderFamilyId());
        message.put("isRouteBranch", highlightedBranch);
    }

    private double routeProgress(ScheduledRoute route, long now) {
        if (now <= route.startTime()) return 0;
        if (route.travelDurationMs() <= 0) return 1;
        return Math.max(0, Math.min(1.0,
                (now - route.startTime()) / (double) route.travelDurationMs()));
    }

    /** 方位角约定为 0 度正北、顺时针增加，与外部定位接口保持一致。 */
    private Integer directionAtProgress(List<double[]> coordinates, double progress) {
        if (coordinates == null || coordinates.size() < 2) return null;
        double clamped = Math.max(0, Math.min(1, progress));
        double fromProgress = clamped >= 1.0 ? Math.max(0, clamped - 0.001) : clamped;
        double toProgress = clamped >= 1.0 ? clamped : Math.min(1, clamped + 0.001);
        double[] from = coordinateAtProgress(coordinates, fromProgress);
        double[] to = coordinateAtProgress(coordinates, toProgress);
        if (distanceKm(from, to) <= 0.000001) return null;

        double fromLat = Math.toRadians(from[1]);
        double toLat = Math.toRadians(to[1]);
        double deltaLng = Math.toRadians(to[0] - from[0]);
        double y = Math.sin(deltaLng) * Math.cos(toLat);
        double x = Math.cos(fromLat) * Math.sin(toLat)
                - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLng);
        return (int) Math.round((Math.toDegrees(Math.atan2(y, x)) + 360) % 360) % 360;
    }

    private String directionLabel(int directionDeg) {
        String[] labels = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return labels[((directionDeg + 22) % 360) / 45];
    }

    private void broadcastChangedPositionFrames(long now) {
        Map<RouteScope, List<Map<String, Object>>> positionsByScope = new LinkedHashMap<>();
        for (ScheduledRoute route : activeRoutes.values()) {
            String scopeName = scopeName(route.scope());
            if (!webSocketHandler.hasVehiclePositionSubscribers(scopeName)) {
                continue;
            }
            Map<String, Object> position = positionMessage(route, now);
            if (!shouldBroadcastPosition(position, now)) continue;
            positionsByScope.computeIfAbsent(route.scope(), ignored -> new ArrayList<>()).add(position);
        }
        positionsByScope.forEach((scope, positions) -> {
            if (positions.isEmpty()) return;
            String scopeName = scopeName(scope);
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "vehicle_positions");
            frame.put("scope", scopeName);
            frame.put("serverTime", Instant.ofEpochMilli(now).toString());
            frame.put("snapshotVersion", scope == RouteScope.TOWN ? rm2SnapshotVersion : "");
            frame.put("positions", positions);
            webSocketHandler.broadcastVehiclePositions(scopeName, frame);
        });
    }

    private boolean shouldBroadcastPosition(Map<String, Object> position, long now) {
        String lineId = String.valueOf(position.get("lineId"));
        double[] coordinate = (double[]) position.get("position");
        double speed = ((Number) position.get("speedKmh")).doubleValue();
        String status = String.valueOf(position.get("status"));
        boolean stale = Boolean.TRUE.equals(position.get("stale"));
        String detailsSignature = String.join("|",
                String.valueOf(position.get("driverName")),
                String.valueOf(position.get("address")),
                String.valueOf(position.get("stateStr")),
                String.valueOf(position.get("alarmStr")),
                String.valueOf(position.get("alarmSeverity")),
                String.valueOf(position.get("online")),
                String.valueOf(position.get("directionDeg")),
                String.valueOf(position.get("directionLabel")),
                String.valueOf(position.get("routeRevision")));
        BroadcastPositionState previous = lastBroadcastPositions.get(lineId);
        BroadcastPositionState current = new BroadcastPositionState(
                coordinate, speed, status, stale, detailsSignature, now);
        if (previous == null
                || distanceKm(previous.position(), coordinate) * 1_000 >= POSITION_CHANGE_THRESHOLD_METERS
                || Math.abs(previous.speedKmh() - speed) >= 3.0
                || !previous.status().equals(status)
                || previous.stale() != stale
                || !previous.detailsSignature().equals(detailsSignature)
                || now - previous.sentAt() >= POSITION_MAX_SILENCE_MS) {
            lastBroadcastPositions.put(lineId, current);
            return true;
        }
        return false;
    }

    private String scopeName(RouteScope scope) {
        return scope == RouteScope.TOWN ? "rm2" : "rm1";
    }

    private String positionGroupId(ScheduledRoute route) {
        if (route.scope() == RouteScope.TOWN) {
            return rm2GroupIdByLineId.getOrDefault(route.lineId(), route.pathKey());
        }
        return groupIdFor(route.lineId());
    }

    private void cleanupExpiredRoutes(long now) {
        activeRoutes.entrySet().removeIf((entry) -> {
            boolean expired = now - entry.getValue().startTime() > entry.getValue().travelDurationMs();
            if (expired) {
                lastPositionSamples.remove(entry.getKey());
                routeDeviationAlerts.remove(entry.getKey());
                lastRouteReplanAt.remove(entry.getKey());
                routeDeviationClassifier.remove(entry.getKey());
                routeCorrectionRevisions.remove(entry.getKey());
                routeProgressSources.remove(entry.getKey());
                baselineRouteCoordinates.remove(entry.getKey());
                vehicleDeviationCoordinates.remove(entry.getKey());
                lastBroadcastPositions.remove(entry.getKey());
                rm2GroupIdByLineId.remove(entry.getKey());
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

        // 优先读缓存
        PositionSnapshot cached = positionCache.getPosition(lineId);
        if (cached != null && !cached.stale()) {
            return new ProviderPosition(
                    cached.position(), cached.speedKmh(),
                    cached.vehicleId(), cached.vehicleName());
        }

        // 缓存未命中或已过期：同步查询外部接口（兼容旧行为，同时写入缓存）
        String plate = lineIdPlateMap.get(lineId);
        if (plate != null && !plate.isBlank()) {
            ProviderPosition pos = fetchPositionByPlate(plate);
            if (pos != null) {
                positionCache.putPosition(lineId, PositionSnapshot.fromProvider(
                        lineId, pos.vehicleId(), pos.vehicleName(), plate,
                        pos.position()[0], pos.position()[1], pos.speedKmh(),
                        pos.driverName(), pos.address(), pos.stateStr(),
                        pos.directionDeg(), pos.directionLabel(), pos.alarmStr(), pos.online()));
                return pos;
            }
        }

        String carId = lineIdCarIdMap.get(lineId);
        if (carId != null && !carId.isBlank()) {
            ProviderPosition pos = fetchPositionByCarId(carId);
            if (pos != null) {
                positionCache.putPosition(lineId, PositionSnapshot.fromProvider(
                        lineId, pos.vehicleId(), pos.vehicleName(), plate,
                        pos.position()[0], pos.position()[1], pos.speedKmh(),
                        pos.driverName(), pos.address(), pos.stateStr(),
                        pos.directionDeg(), pos.directionLabel(), pos.alarmStr(), pos.online()));
                return pos;
            }
        }

        // 缓存有 stale 数据：返回 stale 位置而非回退模拟
        if (cached != null) {
            return new ProviderPosition(
                    cached.position(), cached.speedKmh(),
                    cached.vehicleId(), cached.vehicleName());
        }

        return null;
    }

    /**
     * 外部订单的 carId 字段并不可靠：有些来源会把车牌填在此字段。
     * 位置接口的 car_ids 只接收车辆列表返回的 vehicle_id，因此车牌值必须由
     * fetchPositionByPlate 通过车辆列表字典反查，不能直接作为 carId 请求参数。
     */
    private void rememberProviderVehicleId(String lineId, String plate, String candidateCarId) {
        lineIdCarIdMap.remove(lineId);
        String effectivePlate = normalizePlate(plate);
        String candidate = candidateCarId == null ? "" : candidateCarId.trim();

        // 外部订单可能把车牌填在 carId 字段，需要提升为车牌再经车辆列表反查 ID。
        if (effectivePlate.isBlank() && isPlateLike(candidate)) {
            effectivePlate = normalizePlateDisplay(candidate);
            lineIdPlateMap.put(lineId, effectivePlate);
            log.info("Promoted plate-like carId to plate: lineId={}, candidateCarId={}, effectivePlate={}",
                    lineId, candidateCarId, effectivePlate);
        }

        if (!effectivePlate.isBlank()) {
            effectivePlate = normalizePlateDisplay(effectivePlate);
            lineIdPlateMap.put(lineId, effectivePlate);
            VehicleRef vehicleRef = resolveVehicleByPlate(effectivePlate);
            if (vehicleRef != null) {
                lineIdCarIdMap.put(lineId, vehicleRef.vehicleId());
                log.info("Resolved provider vehicle: lineId={}, queryPlate={}, vehicle_name={}, vehicle_id={}",
                        lineId, effectivePlate, vehicleRef.vehicleName(), vehicleRef.vehicleId());
            } else {
                log.warn("Vehicle list contains no matching vehicle_name: lineId={}, queryPlate={}, normalizedPlate={}",
                        lineId, effectivePlate, normalizePlateKey(effectivePlate));
            }
            if (vehicleRef != null) return;
            // 车牌查询失败时，candidateCarId 仍可能是供应商真实 ID，继续按车辆字典校验。
            if (!candidate.isBlank() && !isPlateLike(candidate)) {
                ensureVehicleDictionary();
                VehicleRef candidateRef = vehicleById.get(candidate);
                if (candidateRef != null) {
                    lineIdCarIdMap.put(lineId, candidateRef.vehicleId());
                    if (candidateRef.vehicleName() != null && !candidateRef.vehicleName().isBlank()) {
                        lineIdPlateMap.put(lineId, normalizePlateDisplay(candidateRef.vehicleName()));
                    }
                    log.info("Resolved provider vehicle by candidateCarId fallback: lineId={}, vehicle_id={}",
                            lineId, candidateRef.vehicleId());
                }
            }
            return;
        }

        // 没有任何车牌时，candidateCarId 才被视为真实 vehicle_id 的兼容输入。
        if (!candidate.isBlank()) {
            ensureVehicleDictionary();
            VehicleRef vehicleRef = vehicleById.get(candidate);
            if (vehicleRef != null) {
                lineIdCarIdMap.put(lineId, vehicleRef.vehicleId());
                if (vehicleRef.vehicleName() != null) {
                    lineIdPlateMap.put(lineId, normalizePlateDisplay(vehicleRef.vehicleName()));
                }
            }
        }
    }

    /**
     * 为严格真实定位模式准备订单的 provider vehicleId。
     * 这里只解析一次车辆字典，不查询单车位置；实际位置仍由定时批量任务统一拉取。
     */
    public synchronized boolean prepareProviderPositionVehicle(String lineId, String plate, String candidateCarId) {
        if (lineId == null || lineId.isBlank() || !positionCache.isEnabled()) {
            return false;
        }
        rememberProviderVehicleId(lineId, plate, candidateCarId);
        String vehicleId = lineIdCarIdMap.get(lineId);
        return vehicleId != null && !vehicleId.isBlank();
    }

    private String normalizePlate(String value) {
        return normalizePlateKey(value);
    }

    private String normalizePlateDisplay(String value) {
        return normalizePlateBaseKey(value);
    }

    private boolean containsChineseCharacter(String value) {
        return value != null && value.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private boolean isPlateLike(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return containsChineseCharacter(normalizePlate(value));
    }

    @PostConstruct
    public void testExternalPositionAPI() {
        log.info("[RoutePush] externalPositionUrl={}, passivePositionPushEnabled={}",
                externalPositionUrl, passivePositionPushEnabled);
        if (!testOnStartup || !externalPositionConfigured()) return;

        log.info("===== 外部位置接口启动测试开始 =====");
        refreshVehicleDictionary();

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
            return Math.max(1, Math.min(MAX_PROVIDER_SPEED_KMH, realSimulationSpeedKmh));
        }
        return Math.max(1, Math.min(MAX_PROVIDER_SPEED_KMH, testSimulationSpeedKmh));
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
            String businessLineId,
            String orderName,
            Integer orderTotalTons,
            String from,
            String to,
            String fromProvince,
            String toProvince,
            double[] fromCoords,
            double[] toCoords,
            List<double[]> routeCoordinates,
            List<double[]> matchingRouteCoordinates,
            Long plannedTravelDurationMs,
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
        List<double[]> matchingCoordinates = sanitizeRouteCoordinates(
                fromCoords, toCoords, matchingRouteCoordinates == null ? routeCoordinates : matchingRouteCoordinates);
        double routeLengthKm = pathLengthKm(matchingCoordinates);
        if (plate != null && !plate.isBlank()) {
            lineIdPlateMap.put(lineId, plate);
        }
        rememberProviderVehicleId(lineId, plate, carId);

        ProviderPosition initialExternalPosition = fetchExternalVehiclePosition(lineId);
        String progressSource = initialProgressSource(initialExternalPosition, currentCoords, status);
        double[] resolvedCurrentCoords = initialExternalPosition != null
                ? initialExternalPosition.position()
                : currentCoords;
        double effectiveSpeedKmh = resolveExternalRouteSpeed(initialExternalPosition, speedKmh);
        String resolvedUpdatedAt = initialExternalPosition != null
                ? Instant.ofEpochMilli(now).toString()
                : updatedAt;
        double progress = initialProgressForExternalOrder(
                matchingCoordinates,
                resolvedCurrentCoords,
                routeLengthKm,
                effectiveSpeedKmh,
                resolvedUpdatedAt,
                status
        );
        long travelDurationMs = plannedTravelDurationMs != null && plannedTravelDurationMs > 0
                ? plannedTravelDurationMs
                : travelDurationMs(routeLengthKm, effectiveSpeedKmh);
        long startTime = "已完成".equals(status)
                ? now - travelDurationMs
                : now - Math.round(progress * travelDurationMs);

        ScheduledRoute route = new ScheduledRoute(
                lineId,
                businessLineId == null || businessLineId.isBlank()
                        ? (orderId == null || orderId.isBlank() ? lineId : orderId)
                        : businessLineId,
                orderId == null || orderId.isBlank() ? lineId : orderId,
                orderName == null || orderName.isBlank() ? "外部订单运输" : orderName,
                orderTotalTons == null ? 0 : orderTotalTons,
                1,
                from,
                to,
                fromProvince,
                toProvince,
                coordinates,
                matchingCoordinates,
                pathKey(from, to, coordinates),
                startTime,
                routeLengthKm,
                effectiveSpeedKmh,
                travelDurationMs,
                RouteScope.ROAD
        );

        activeRoutes.put(lineId, route);
        routeProgressSources.put(lineId, progressSource);
        baselineRouteCoordinates.put(lineId, copyRouteCoordinates(matchingCoordinates));
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
        return "waiting-position";
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
            if (distanceKm(coordinates.get(0), fromCoords) > 0.001) {
                coordinates.add(0, new double[]{fromCoords[0], fromCoords[1]});
            } else {
                coordinates.set(0, new double[]{fromCoords[0], fromCoords[1]});
            }
            if (distanceKm(coordinates.get(coordinates.size() - 1), toCoords) > 0.001) {
                coordinates.add(new double[]{toCoords[0], toCoords[1]});
            } else {
                coordinates.set(coordinates.size() - 1, new double[]{toCoords[0], toCoords[1]});
            }
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
            // 正式订单没有真实位置时不得伪造历史进度；严格模式会保持 WAITING_POSITION、不注册路线。
            return 0;
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
        return progressOnCoordinates(coordinates, position, -1);
    }

    static double calibrationHintProgress(String progressSource, double timelineProgress) {
        return "real-provider".equals(progressSource) ? timelineProgress : -1;
    }

    /**
     * 将 GPS 位置投影到路线坐标上，返回 [0,1] 的进度。
     * hintProgress 用于 U 形路线约束：只搜索 hintProgress ±30% 窗口内的线段，
     * 避免车辆被贴到弯路对面一侧。hintProgress < 0 时全图搜索。
     */
    private double progressOnCoordinates(List<double[]> coordinates, double[] position, double hintProgress) {
        return RouteProgressProjector.project(coordinates, position, hintProgress);
    }

    private List<double[]> copyRouteCoordinates(List<double[]> coordinates) {
        if (coordinates == null) return List.of();
        return coordinates.stream()
                .filter(coordinate -> coordinate != null && coordinate.length >= 2)
                .map(coordinate -> new double[]{coordinate[0], coordinate[1]})
                .toList();
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
     * TownRoad 专用：短途订单也注册进 RoadMap 同一套 activeRoutes。
     * 后续 /position 查询与被动 WebSocket 推送都会先尝试真实定位，失败后回落到模拟位置。
     */
    public synchronized void dispatchTownRoute(
            String lineId,
            String orderId,
            String businessLineId,
            String from,
            String to,
            double[] fromCoords,
            double[] toCoords,
            List<double[]> routeCoordinates,
            List<double[]> matchingRouteCoordinates,
            Long plannedTravelDurationMs,
            double[] currentCoords,
            String plate,
            String carId,
            Double speedKmh,
            String updatedAt,
            String status
    ) {
        if (lineId == null || lineId.isBlank() || fromCoords == null || toCoords == null
                || fromCoords.length < 2 || toCoords.length < 2) {
            log.warn("[TownRoad] dispatch skipped: invalid order coords, lineId={}", lineId);
            return;
        }

        long now = System.currentTimeMillis();
        cleanupExpiredRoutes(now);

        if (plate != null && !plate.isBlank()) {
            lineIdPlateMap.put(lineId, plate);
        }
        rememberProviderVehicleId(lineId, plate, carId);

        List<double[]> coordinates = sanitizeRouteCoordinates(fromCoords, toCoords, routeCoordinates);
        List<double[]> matchingCoordinates = sanitizeRouteCoordinates(
                fromCoords, toCoords, matchingRouteCoordinates == null ? routeCoordinates : matchingRouteCoordinates);
        String pathKey = pathKey(from, to, coordinates);
        double routeLengthKm = pathLengthKm(matchingCoordinates);

        // 外部订单快照会反复抵达。相同 lineId 的短途任务必须沿用第一次注册的
        // startTime，否则模拟进度会被重新随机并回跳到路线起点附近。
        ScheduledRoute existingRoute = activeRoutes.get(lineId);
        if (isSameTownOrderRoute(existingRoute, from, to, fromCoords, toCoords)) {
            log.debug("[TownRoad] retained active simulation: lineId={}, startTime={}, speedKmh={}",
                    lineId, existingRoute.startTime(), existingRoute.speedKmh());
            return;
        }

        ProviderPosition initialExternalPosition = fetchExternalVehiclePosition(lineId);
        String progressSource = initialProgressSource(initialExternalPosition, currentCoords, status);
        double[] resolvedCurrentCoords = initialExternalPosition != null
                ? initialExternalPosition.position()
                : currentCoords;
        double effectiveSpeedKmh = resolveExternalRouteSpeed(initialExternalPosition, speedKmh);
        String resolvedUpdatedAt = initialExternalPosition != null
                ? Instant.ofEpochMilli(now).toString()
                : updatedAt;
        double progress = initialProgressForExternalOrder(
                matchingCoordinates,
                resolvedCurrentCoords,
                routeLengthKm,
                effectiveSpeedKmh,
                resolvedUpdatedAt,
                status
        );
        long travelDurationMs = plannedTravelDurationMs != null && plannedTravelDurationMs > 0
                ? plannedTravelDurationMs
                : travelDurationMs(routeLengthKm, effectiveSpeedKmh);
        long startTime = "已完成".equals(status)
                ? now - travelDurationMs
                : now - Math.round(progress * travelDurationMs);

        ScheduledRoute route = new ScheduledRoute(
                lineId,
                businessLineId == null || businessLineId.isBlank()
                        ? (orderId == null || orderId.isBlank() ? lineId : orderId)
                        : businessLineId,
                orderId == null || orderId.isBlank() ? lineId : orderId,
                "短途运输",
                0,
                1,
                from,
                to,
                from,
                to,
                coordinates,
                matchingCoordinates,
                pathKey,
                startTime,
                routeLengthKm,
                effectiveSpeedKmh,
                travelDurationMs,
                RouteScope.TOWN
        );
        activeRoutes.put(lineId, route);
        routeProgressSources.put(lineId, progressSource);
        baselineRouteCoordinates.put(lineId, copyRouteCoordinates(matchingCoordinates));
        log.info("[TownRoad] dispatched town route: {} -> {}, lineId={}, orderId={}, routePoints={}, progress={}%, progressSource={}",
                from, to, lineId, route.orderId(), coordinates.size(), Math.round(progress * 100), progressSource);
    }

    private boolean isSameTownOrderRoute(
            ScheduledRoute route,
            String from,
            String to,
            double[] fromCoords,
            double[] toCoords
    ) {
        return route != null
                && route.scope() == RouteScope.TOWN
                && safeRouteText(route.from()).equals(safeRouteText(from))
                && safeRouteText(route.to()).equals(safeRouteText(to))
                && distanceKm(route.getFromCoords(), fromCoords) < 0.1
                && distanceKm(route.getToCoords(), toCoords) < 0.1;
    }

    /** 真实速度优先，0 表示停车；订单速度只作已校验的兜底。 */
    private double resolveExternalRouteSpeed(ProviderPosition providerPosition, Double orderSpeedKmh) {
        if (providerPosition != null && isProviderSpeed(providerPosition.speedKmh())) {
            return providerPosition.speedKmh();
        }
        if (orderSpeedKmh != null && Double.isFinite(orderSpeedKmh)
                && orderSpeedKmh > 0 && orderSpeedKmh <= MAX_PROVIDER_SPEED_KMH) {
            return orderSpeedKmh;
        }
        return Math.max(1, Math.min(MAX_PROVIDER_SPEED_KMH, realSimulationSpeedKmh));
    }

    private boolean isProviderSpeed(double speedKmh) {
        return Double.isFinite(speedKmh) && speedKmh >= 0 && speedKmh <= MAX_PROVIDER_SPEED_KMH;
    }

    private double normalizedRouteSpeed(double speedKmh) {
        return isProviderSpeed(speedKmh)
                ? speedKmh
                : Math.max(1, Math.min(MAX_PROVIDER_SPEED_KMH, realSimulationSpeedKmh));
    }

    private long travelDurationMs(double routeLengthKm, double speedKmh) {
        // 静止车辆保留真实 speed=0，但按 1km/h 计算兜底时长，防止模拟高速前进。
        return Math.max(60_000L, Math.round(routeLengthKm / Math.max(1, speedKmh) * 3_600_000));
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
            String startProvince,
            String endProvince,
            List<double[]> coordinates,
            List<double[]> matchingCoordinates,
            String pathKey,
            long startTime,
            double routeLengthKm,
            double speedKmh,
            long travelDurationMs,
            RouteScope scope
    ) implements RouteInfo, OrderAwareRouteInfo, PathAwareRouteInfo, ProvinceAwareRouteInfo {
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
        public String getStartProvince() { return startProvince; }
        @Override
        public String getEndProvince() { return endProvince; }
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

    public record RouteRuntimeMetrics(
            double speedKmh,
            double routeLengthKm,
            long travelDurationMs,
            String vehicleId,
            String plate,
            boolean hasFreshProviderPosition,
            String positionSource,
            List<double[]> coordinates,
            String pathKey
    ) {}

    private record ProviderPosition(
            double[] position,
            double speedKmh,
            String vehicleId,
            String vehicleName,
            String driverName,
            String address,
            String stateStr,
            Integer directionDeg,
            String directionLabel,
            String alarmStr,
            Boolean online
    ) {
        private ProviderPosition(double[] position, double speedKmh, String vehicleId, String vehicleName) {
            this(position, speedKmh, vehicleId, vehicleName,
                    null, null, null, null, null, null, null);
        }
    }

    private enum RouteScope {
        ROAD,
        TOWN
    }

    private record VehicleRef(
            String vehicleId,
            String vehicleName
    ) {
    }

    public Map<String, Object> queryPositionByVehicleKey(String vehicleKey) {
        ProviderPosition pos = null;
        if (externalPositionConfigured() && vehicleKey != null && !vehicleKey.isBlank()) {
            pos = fetchPositionByPlate(vehicleKey);
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

    private record RouteDeviationAlert(
            String severity,
            long lastDeviationAt,
            int count,
            double distanceKm
    ) {}

    private record BroadcastPositionState(
            double[] position,
            double speedKmh,
            String status,
            boolean stale,
            String detailsSignature,
            long sentAt
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
                .filter(route -> route.scope() == RouteScope.ROAD)
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
        int hash = 1;
        for (double[] coordinate : coordinates) {
            if (coordinate == null || coordinate.length < 2) {
                continue;
            }
            hash = 31 * hash + Double.hashCode(roundCoordinate(coordinate[0]));
            hash = 31 * hash + Double.hashCode(roundCoordinate(coordinate[1]));
        }
        return from + "->" + to + "-" + Integer.toHexString(hash);
    }

    private double roundCoordinate(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
