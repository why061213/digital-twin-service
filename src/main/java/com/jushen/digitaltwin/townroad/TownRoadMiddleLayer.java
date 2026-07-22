package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.townroad.ProvinceRoadGraph.ProvincePath;
import com.jushen.digitaltwin.baidu.RoutePlanningService;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.ProvinceEdgeView;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.ProvincePathCandidate;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.ProvinceRef;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.TownRoadOrder;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.TownRoadRouteGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TownRoadMiddleLayer {
    private static final Logger log = LoggerFactory.getLogger(TownRoadMiddleLayer.class);

    /**
     * 两点之间最多保留多少条等长最短路径，避免某些节点组合出现过多等价路径。
     */
    private static final int MAX_CANDIDATE_PATHS_PER_PAIR = 12;

    private final ObjectMapper objectMapper;
    private final ProvinceRoadGraph provinceRoadGraph;
    private final CityRoadGraph cityRoadGraph;
    private final DistrictRoadGraph districtRoadGraph;
    private final ProvinceCodeResolver provinceCodeResolver;
    private final TownRoadExternalOrderProperties properties;
    private final TownRoadCoordinateResolver coordinateResolver;
    private final DailyOrderStatisticsService dailyOrderStatisticsService;
    private final ChinaBoundaryConstraint chinaBoundaryConstraint;
    private final RoutePlanningService routePlanningService;

    private final Map<String, NormalizedTownRoadOrder> ordersByInstanceId = new ConcurrentHashMap<>();
    private final Map<String, List<double[]>> baselineRouteCoordinatesByInstanceId = new ConcurrentHashMap<>();

    /**
     * 区县/地点 OD MapMap：fromKey -> toKey -> transport instance ids。
     */
    private final Map<String, Map<String, Set<String>>> odIndex = new ConcurrentHashMap<>();

    /**
     * 省份 OD MapMap：fromProvince -> toProvince -> transport instance ids。
     */
    private final Map<String, Map<String, Set<String>>> provincePairIndex = new ConcurrentHashMap<>();

    /**
     * 精确省份路径索引：provincePathKey -> transport instance ids。
     * 如果一个运输实例有多条候选路径，会被放到多个 pathKey 下。
     */
    private final Map<String, Set<String>> provincePathIndex = new ConcurrentHashMap<>();

    /**
     * 起点省份索引：sourceProvinceKey -> transport instance ids。
     */
    private final Map<String, Set<String>> sourceProvinceIndex = new ConcurrentHashMap<>();

    public TownRoadMiddleLayer(
            ObjectMapper objectMapper,
            ProvinceRoadGraph provinceRoadGraph,
            CityRoadGraph cityRoadGraph,
            DistrictRoadGraph districtRoadGraph,
            ProvinceCodeResolver provinceCodeResolver,
            TownRoadExternalOrderProperties properties,
            TownRoadCoordinateResolver coordinateResolver,
            DailyOrderStatisticsService dailyOrderStatisticsService,
            ChinaBoundaryConstraint chinaBoundaryConstraint,
            RoutePlanningService routePlanningService
    ) {
        this.objectMapper = objectMapper;
        this.provinceRoadGraph = provinceRoadGraph;
        this.cityRoadGraph = cityRoadGraph;
        this.districtRoadGraph = districtRoadGraph;
        this.provinceCodeResolver = provinceCodeResolver;
        this.properties = properties;
        this.coordinateResolver = coordinateResolver;
        this.dailyOrderStatisticsService = dailyOrderStatisticsService;
        this.chinaBoundaryConstraint = chinaBoundaryConstraint;
        this.routePlanningService = routePlanningService;
    }

    public synchronized ExternalOrderSnapshotResult processSnapshot(List<ExternalOrderRecord> rawOrders) {
        List<ExternalOrderRecord> safeRawOrders = rawOrders == null ? List.of() : rawOrders;
        List<ExternalOrderRecord> expandedRawOrders = expandVehicleInstances(safeRawOrders);

        // 预过滤：先筛选出值得调用路线规划API的订单。
        // 避免对已取消/待装载/已完成超时等注定被丢弃的订单浪费百度/高德API调用。
        List<ExternalOrderRecord> ordersNeedingRoutePlan = new ArrayList<>();
        for (ExternalOrderRecord raw : expandedRawOrders) {
            if (isWorthRoutePlanning(raw)) {
                ordersNeedingRoutePlan.add(raw);
            }
        }
        log.info("[TownRoad] pre-filter: expandedTotal={}, worthRoutePlan={}, skipped={}",
                expandedRawOrders.size(), ordersNeedingRoutePlan.size(),
                expandedRawOrders.size() - ordersNeedingRoutePlan.size());
        Map<String, RoutePlanBundle> routePlansByOrderLine = planOrderLineRoutes(ordersNeedingRoutePlan);
        Map<String, NormalizedTownRoadOrder> previous = new LinkedHashMap<>(ordersByInstanceId);

        Map<String, NormalizedTownRoadOrder> dedupedCandidates = new LinkedHashMap<>();
        List<String> skippedInvalidLineIds = new ArrayList<>();
        List<String> deletedOrCancelledLineIds = new ArrayList<>();
        int skippedNotRenderable = 0;
        int skippedLongHaul = 0;
        List<String> skippedNotRenderableLineIds = new ArrayList<>();
        List<String> skippedLongHaulLineIds = new ArrayList<>();

        for (ExternalOrderRecord raw : expandedRawOrders) {
            if (!isValidBasic(raw)) {
                skippedInvalidLineIds.add(rawDebugId(raw));
                continue;
            }

            NormalizedTownRoadOrder order;
            try {
                order = normalize(raw, routePlansByOrderLine);
            } catch (Exception e) {
                log.error("[TownRoad] normalize failed: instanceId={}, orderId={}, lineId={}, status={}, deleted={}, from={}, to={}",
                        rawDebugId(raw), raw.orderId(), raw.lineId(), raw.status(), raw.deleted(),
                        raw.from() == null ? null : raw.from().name(),
                        raw.to() == null ? null : raw.to().name(), e);
                throw e;
            }
            dedupedCandidates.merge(order.instanceId(), order, this::newerOrder);
        }

        // 统计口径位于车辆实例去重之后、装卸/运输/完成状态筛选之前。
        // 服务内部按 instanceId 累积，因此重复快照和状态迁移不会重复计数。
        dailyOrderStatisticsService.applySnapshot(List.copyOf(dedupedCandidates.values()));

        List<NormalizedTownRoadOrder> normalized = new ArrayList<>();
        for (NormalizedTownRoadOrder order : dedupedCandidates.values()) {

            if (order.deleted() || "已取消".equals(order.status())) {
                deletedOrCancelledLineIds.add(order.instanceId());
                continue;
            }

            normalized.add(order);
        }

        rebuildIndexes(normalized);
        baselineRouteCoordinatesByInstanceId.keySet().retainAll(ordersByInstanceId.keySet());

        List<NormalizedTownRoadOrder> shortHaulOrders = new ArrayList<>();
        List<NormalizedTownRoadOrder> longHaulOrders = new ArrayList<>();
        for (NormalizedTownRoadOrder order : normalized) {
            if (properties.isRequireRenderableCoords() && !isRenderable(order)) {
                skippedNotRenderable++;
                skippedNotRenderableLineIds.add(order.instanceId());
                continue;
            }

            if (!isShortHaul(order)) {
                skippedLongHaul++;
                skippedLongHaulLineIds.add(order.instanceId());
                if (isRoadMapRenderable(order) && isDispatchableStatus(order)) {
                    longHaulOrders.add(order);
                }
                continue;
            }

            // 待装载：始终不发送
            if ("待装载".equals(order.status())) {
                continue;
            }
            // 已完成：仅在保留时间窗口内发送（前端做模拟+修正用）
            if ("已完成".equals(order.status())) {
                if (isCompletedExpired(order.updatedAt())) {
                    continue;
                }
            }

            shortHaulOrders.add(order);
        }

        OrderSnapshotDiff diff = buildDiff(
                previous,
                ordersByInstanceId,
                skippedInvalidLineIds.size(),
                skippedNotRenderable,
                skippedLongHaul,
                deletedOrCancelledLineIds.size(),
                skippedInvalidLineIds,
                skippedNotRenderableLineIds,
                skippedLongHaulLineIds,
                deletedOrCancelledLineIds
        );

        List<TownRoadRenderCommand> commands = buildTownRoadCommands(shortHaulOrders);

        return new ExternalOrderSnapshotResult(
                safeRawOrders.size(),
                normalized.size(),
                shortHaulOrders.size(),
                longHaulOrders.size(),
                shortHaulOrders,
                longHaulOrders,
                commands,
                diff
        );
    }

    public List<NormalizedTownRoadOrder> findSameOd(String fromKey, String toKey) {
        Set<String> instanceIds = odIndex
                .getOrDefault(fromKey, Map.of())
                .getOrDefault(toKey, Set.of());

        return instanceIds.stream()
                .map(ordersByInstanceId::get)
                .filter(item -> item != null)
                .sorted(orderComparator())
                .toList();
    }

    public List<NormalizedTownRoadOrder> findByProvincePath(String provincePathKey) {
        Set<String> instanceIds = provincePathIndex.getOrDefault(provincePathKey, Set.of());

        return instanceIds.stream()
                .map(ordersByInstanceId::get)
                .filter(item -> item != null)
                .sorted(orderComparator())
                .toList();
    }

    /**
     * 将 440000>330000 这种起终点 routeKey 解析成所有等长最短路径，
     * 再合并查找这些路径下的订单。
     */
    public List<NormalizedTownRoadOrder> findByProvinceRoute(String routeKey) {
        List<String> resolvedPathKeys = resolveProvincePathKeys(routeKey);
        LinkedHashMap<String, NormalizedTownRoadOrder> result = new LinkedHashMap<>();

        for (String pathKey : resolvedPathKeys) {
            for (NormalizedTownRoadOrder order : findByProvincePath(pathKey)) {
                result.put(order.instanceId(), order);
            }
        }

        return result.values()
                .stream()
                .sorted(orderComparator())
                .toList();
    }

    public List<String> resolveProvincePathKeys(String routeKey) {
        List<String> parts = parseProvincePathKey(routeKey);
        if (parts.isEmpty()) return List.of();
        if (parts.size() == 1) return List.of(parts.get(0));

        String start = parts.get(0);
        String target = parts.get(parts.size() - 1);

        return candidateProvincePaths(start, target)
                .stream()
                .map(ProvincePath::pathKey)
                .toList();
    }

    public List<NormalizedTownRoadOrder> allOrders() {
        return ordersByInstanceId.values()
                .stream()
                .sorted(orderComparator())
                .toList();
    }

    private void rebuildIndexes(List<NormalizedTownRoadOrder> normalizedOrders) {
        ordersByInstanceId.clear();
        odIndex.clear();
        provincePairIndex.clear();
        provincePathIndex.clear();
        sourceProvinceIndex.clear();

        for (NormalizedTownRoadOrder order : normalizedOrders) {
            ordersByInstanceId.put(order.instanceId(), order);

            odIndex
                    .computeIfAbsent(order.fromKey(), ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(order.toKey(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(order.instanceId());

            provincePairIndex
                    .computeIfAbsent(order.fromProvinceKey(), ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(order.toProvinceKey(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(order.instanceId());

            sourceProvinceIndex
                    .computeIfAbsent(order.fromProvinceKey(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(order.instanceId());

            for (String provincePathKey : order.provincePathKeys()) {
                if (provincePathKey.isBlank()) continue;
                provincePathIndex
                        .computeIfAbsent(provincePathKey, ignored -> ConcurrentHashMap.newKeySet())
                        .add(order.instanceId());
            }
        }
    }

    private OrderSnapshotDiff buildDiff(
            Map<String, NormalizedTownRoadOrder> previous,
            Map<String, NormalizedTownRoadOrder> current,
            int skippedInvalid,
            int skippedNotRenderable,
            int skippedLongHaul,
            int deletedOrCancelled,
            List<String> skippedInvalidLineIds,
            List<String> skippedNotRenderableLineIds,
            List<String> skippedLongHaulLineIds,
            List<String> deletedOrCancelledLineIds
    ) {
        int added = 0;
        int updated = 0;
        int deleted = 0;
        int unchanged = 0;
        int routeChanged = 0;
        List<String> addedLineIds = new ArrayList<>();
        List<String> updatedLineIds = new ArrayList<>();
        List<String> deletedLineIds = new ArrayList<>();
        List<String> unchangedLineIds = new ArrayList<>();
        List<String> routeChangedLineIds = new ArrayList<>();

        for (Map.Entry<String, NormalizedTownRoadOrder> entry : current.entrySet()) {
            String lineId = entry.getKey();
            NormalizedTownRoadOrder next = entry.getValue();
            NormalizedTownRoadOrder old = previous.get(lineId);

            if (old == null) {
                added++;
                addedLineIds.add(lineId);
                continue;
            }

            boolean dataChanged = !old.dataSignature().equals(next.dataSignature());
            boolean routeHasChanged = !old.routeSignature().equals(next.routeSignature());

            if (!dataChanged && !routeHasChanged) {
                unchanged++;
                unchangedLineIds.add(lineId);
            } else {
                updated++;
                updatedLineIds.add(lineId);
                if (routeHasChanged) {
                    routeChanged++;
                    routeChangedLineIds.add(lineId);
                }
            }
        }

        for (String oldLineId : previous.keySet()) {
            if (!current.containsKey(oldLineId)) {
                deleted++;
                deletedLineIds.add(oldLineId);
            }
        }

        return new OrderSnapshotDiff(
                added,
                updated,
                deleted,
                unchanged,
                routeChanged,
                skippedInvalid,
                skippedNotRenderable,
                skippedLongHaul,
                deletedOrCancelled,
                addedLineIds,
                updatedLineIds,
                deletedLineIds,
                unchangedLineIds,
                routeChangedLineIds,
                skippedInvalidLineIds,
                skippedNotRenderableLineIds,
                skippedLongHaulLineIds,
                deletedOrCancelledLineIds
        );
    }

    public List<ExternalOrderRecord> expandVehicleInstances(List<ExternalOrderRecord> rawOrders) {
        List<ExternalOrderRecord> expanded = new ArrayList<>();

        for (ExternalOrderRecord raw : rawOrders) {
            if (raw == null) {
                expanded.add(null);
                continue;
            }

            List<ExternalOrderRecord.Line> lines = raw.lines() == null ? List.of() : raw.lines();
            if (lines.isEmpty()) {
                expanded.add(raw);
                continue;
            }

            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                ExternalOrderRecord.Line line = lines.get(lineIndex);
                if (line == null) {
                    expanded.add(copyRawOrderLineVehicle(raw, null, null, lineIndex, 0));
                    continue;
                }

                List<ExternalOrderRecord.Vehicle> vehicles = lineVehicles(raw, line);
                for (int vehicleIndex = 0; vehicleIndex < vehicles.size(); vehicleIndex++) {
                    expanded.add(copyRawOrderLineVehicle(
                            raw,
                            line,
                            vehicles.get(vehicleIndex),
                            lineIndex,
                            vehicleIndex
                    ));
                }
            }
        }

        return expanded;
    }

    public String instanceIdFor(ExternalOrderRecord raw) {
        return rawDebugId(raw);
    }

    private List<ExternalOrderRecord.Vehicle> lineVehicles(
            ExternalOrderRecord raw,
            ExternalOrderRecord.Line line
    ) {
        List<ExternalOrderRecord.Vehicle> vehicles = new ArrayList<>();
        if (line.vehicles() != null) {
            vehicles.addAll(line.vehicles());
        }
        if (vehicles.isEmpty() && line.vehicle() != null) {
            vehicles.add(line.vehicle());
        }
        if (vehicles.isEmpty() && raw.vehicle() != null) {
            vehicles.add(raw.vehicle());
        }
        if (vehicles.isEmpty()) {
            vehicles.add(null);
        }
        return vehicles;
    }

    private ExternalOrderRecord copyRawOrderLineVehicle(
            ExternalOrderRecord raw,
            ExternalOrderRecord.Line line,
            ExternalOrderRecord.Vehicle vehicle,
            int lineIndex,
            int vehicleIndex
    ) {
        return new ExternalOrderRecord(
                raw.orderId(),
                firstNonBlank(line == null ? null : line.lineId(), raw.lineId()),
                null,
                firstNonNull(line == null ? null : line.from(), raw.from()),
                firstNonNull(line == null ? null : line.to(), raw.to()),
                vehicle,
                firstNonBlank(line == null ? null : line.status(), raw.status()),
                firstNonBlank(line == null ? null : line.updatedAt(), raw.updatedAt()),
                firstNonNull(line == null ? null : line.deleted(), raw.deleted()),
                firstNonNull(line == null ? null : line.upToDate(), raw.upToDate()),
                lineIndex,
                vehicleIndex
        );
    }

    private String rawDebugId(ExternalOrderRecord raw) {
        if (raw == null) return "null-instance";
        return instanceId(
                firstNonBlank(raw.orderId(), raw.lineId(), "unknown-order"),
                firstNonBlank(raw.lineId(), "unknown-line"),
                raw.lineIndex(),
                raw.vehicleIndex(),
                vehicleKey(raw)
        );
    }

    private NormalizedTownRoadOrder normalize(
            ExternalOrderRecord raw,
            Map<String, RoutePlanBundle> routePlansByOrderLine
    ) {
        // 补全缺失的经纬度：从地址名称中解析省市区并查本地坐标库
        ExternalOrderRecord.Location resolvedFrom = coordinateResolver.resolveLocation(raw.from());
        ExternalOrderRecord.Location resolvedTo = coordinateResolver.resolveLocation(raw.to());

        String fromKey = locationKey(resolvedFrom);
        String toKey = locationKey(resolvedTo);
        String odKey = fromKey + "->" + toKey;

        String fromProvinceKey = provinceCodeResolver.provinceKey(resolvedFrom);
        String toProvinceKey = provinceCodeResolver.provinceKey(resolvedTo);

        List<ProvincePath> provinceCandidatePaths = candidateProvincePaths(fromProvinceKey, toProvinceKey);
        List<String> cityPath = cityPathFor(
                resolvedFrom,
                resolvedTo,
                provinceCandidatePaths
        );
        List<String> cityNames = cityPath.stream()
                .map(cityRoadGraph::cityName)
                .toList();
        Set<String> allowedProvinceCodes = allowedProvinceCodes(provinceCandidatePaths);
        List<String> districtPath = districtRoadGraph.constrainedPath(
                resolvedFrom,
                resolvedTo,
                cityPath,
                allowedProvinceCodes
        );
        List<double[]> fallbackCoordinates = routeCoordinatesFor(resolvedFrom, resolvedTo, cityPath, districtPath);
        RoutePlanBundle routePlanBundle = routePlansByOrderLine.get(
                routePlanKey(raw, resolvedFrom, resolvedTo));
        RoutePlanningService.PlannedRoute baselineRoute = routePlanBundle == null
                ? RoutePlanningService.PlannedRoute.unavailable("missing-order-line-plan")
                : routePlanBundle.baseline();
        List<double[]> baselineRouteCoordinates = baselineRoute.success()
                ? baselineRoute.coordinates()
                : fallbackCoordinates;
        List<double[]> matchingRouteCoordinates = baselineRoute.success()
                ? baselineRoute.matchingCoordinates()
                : fallbackCoordinates;
        List<double[]> routeCoordinates = baselineRouteCoordinates;
        double routeLengthKm = baselineRoute.success() ? baselineRoute.distanceKm()
                : pathLengthKm(routeCoordinates);
        Long travelDurationMs = null;
        if (baselineRoute.success() && baselineRoute.durationMs() > 0) {
            travelDurationMs = baselineRoute.durationMs();
        }
        String routeProvider = baselineRoute.success() ? baselineRoute.provider() : "fallback";
        Double speedKmh = raw.vehicle() == null ? null : raw.vehicle().speedKmh();

        List<ProvincePath> candidatePaths = candidateProvincePathsForOrder(
                fromProvinceKey,
                toProvinceKey,
                cityPath,
                provinceCandidatePaths
        );

        List<List<String>> provincePaths = candidatePaths.stream()
                .map(ProvincePath::provinces)
                .toList();

        List<String> provincePathKeys = candidatePaths.stream()
                .map(ProvincePath::pathKey)
                .toList();

        List<Integer> provincePathCosts = candidatePaths.stream()
                .map(ProvincePath::cost)
                .toList();

        String groupId = "town-route-" + fromProvinceKey + "-" + toProvinceKey;
        String groupName = provinceCodeResolver.shortName(fromProvinceKey)
                + " -> "
                + provinceCodeResolver.shortName(toProvinceKey)
                + " 短途运输";

        String orderId = raw.orderId() == null || raw.orderId().isBlank()
                ? raw.lineId()
                : raw.orderId();
        String vehicleKey = vehicleKey(raw);
        String instanceId = instanceId(orderId, raw.lineId(), raw.lineIndex(), raw.vehicleIndex(), vehicleKey);
        baselineRouteCoordinatesByInstanceId.put(instanceId, copyCoordinates(baselineRouteCoordinates));

        String dataSignature = signature(Map.of(
                "orderId", safe(orderId),
                "lineId", safe(raw.lineId()),
                "instanceId", safe(instanceId),
                "vehicleKey", safe(vehicleKey),
                "fromKey", safe(fromKey),
                "toKey", safe(toKey),
                "vehicle", raw.vehicle() == null ? "" : raw.vehicle(),
                "status", safe(raw.status()),
                "updatedAt", safe(raw.updatedAt()),
                "deleted", Boolean.TRUE.equals(raw.deleted())
        ));

        Map<String, Object> routeSignatureValues = new LinkedHashMap<>();
        routeSignatureValues.put("fromKey", safe(fromKey));
        routeSignatureValues.put("toKey", safe(toKey));
        routeSignatureValues.put("fromCoords", coordsForSignature(resolvedFrom.coords()));
        routeSignatureValues.put("toCoords", coordsForSignature(resolvedTo.coords()));
        routeSignatureValues.put("cityPath", cityPath);
        routeSignatureValues.put("districtPath", districtPath);
        routeSignatureValues.put("baselineRouteCoordinates",
                baselineRouteCoordinates.stream().map(this::coordsForSignature).toList());
        routeSignatureValues.put("routeCoordinates",
                routeCoordinates.stream().map(this::coordsForSignature).toList());
        routeSignatureValues.put("routeProvider", routeProvider);
        routeSignatureValues.put("provincePathKeys", provincePathKeys);
        routeSignatureValues.put("upToDate", Boolean.TRUE.equals(raw.upToDate()));
        String routeSignature = signature(routeSignatureValues);

        return new NormalizedTownRoadOrder(
                orderId,
                raw.lineId(),
                instanceId,
                vehicleKey,

                fromKey,
                toKey,
                odKey,

                fromProvinceKey,
                toProvinceKey,

                provincePaths,
                provincePathKeys,
                provincePathCosts,
                cityPath,
                cityNames,
                routeCoordinates,
                matchingRouteCoordinates,
                routeLengthKm > 0 ? routeLengthKm : null,
                speedKmh,
                travelDurationMs,
                routeProvider,

                groupId,
                groupName,

                resolvedFrom,
                resolvedTo,
                raw.vehicle(),

                raw.status(),
                raw.updatedAt(),
                Boolean.TRUE.equals(raw.deleted()),
                Boolean.TRUE.equals(raw.upToDate()),

                dataSignature,
                routeSignature
        );
    }

    private List<TownRoadRenderCommand> buildTownRoadCommands(List<NormalizedTownRoadOrder> shortHaulOrders) {
        Map<String, List<NormalizedTownRoadOrder>> bySourceProvince = new LinkedHashMap<>();

        shortHaulOrders.stream()
                .sorted(Comparator.comparing(NormalizedTownRoadOrder::fromProvinceKey)
                        .thenComparing(NormalizedTownRoadOrder::toProvinceKey)
                        .thenComparing(NormalizedTownRoadOrder::instanceId))
                .forEach(order -> bySourceProvince
                        .computeIfAbsent(order.fromProvinceKey(), ignored -> new ArrayList<>())
                        .add(order));

        List<TownRoadRenderCommand> commands = new ArrayList<>();
        String issuedAt = Instant.now().toString();

        for (Map.Entry<String, List<NormalizedTownRoadOrder>> sourceEntry : bySourceProvince.entrySet()) {
            String sourceProvinceKey = sourceEntry.getKey();
            List<NormalizedTownRoadOrder> sourceOrders = sourceEntry.getValue();

            SourceCommandBuildResult buildResult = buildCommandForSourceProvince(
                    sourceProvinceKey,
                    sourceOrders,
                    shortHaulOrders,
                    issuedAt
            );

            commands.add(buildResult.command());
        }

        return commands;
    }

    private SourceCommandBuildResult buildCommandForSourceProvince(
            String sourceProvinceKey,
            List<NormalizedTownRoadOrder> sourceOrders,
            List<NormalizedTownRoadOrder> allShortHaulOrders,
            String issuedAt
    ) {
        Map<String, List<NormalizedTownRoadOrder>> byTargetProvince = new LinkedHashMap<>();

        for (NormalizedTownRoadOrder order : sourceOrders) {
            byTargetProvince
                    .computeIfAbsent(order.toProvinceKey(), ignored -> new ArrayList<>())
                    .add(order);
        }

        LinkedHashSet<String> renderProvinceSet = new LinkedHashSet<>();
        renderProvinceSet.add(sourceProvinceKey);

        LinkedHashMap<String, NormalizedTownRoadOrder> flatOrderMap = new LinkedHashMap<>();
        LinkedHashMap<String, ProvinceEdgeAccumulator> edgeAccumulators = new LinkedHashMap<>();

        List<TownRoadRouteGroup> routeGroups = new ArrayList<>();

        for (Map.Entry<String, List<NormalizedTownRoadOrder>> targetEntry : byTargetProvince.entrySet()) {
            String targetProvinceKey = targetEntry.getKey();
            List<NormalizedTownRoadOrder> primaryOrders = targetEntry.getValue();

            TownRoadRouteGroup routeGroup = buildRouteGroup(
                    sourceProvinceKey,
                    targetProvinceKey,
                    primaryOrders,
                    allShortHaulOrders,
                    renderProvinceSet,
                    flatOrderMap,
                    edgeAccumulators
            );

            routeGroups.add(routeGroup);
        }

        routeGroups.sort(Comparator.comparing(TownRoadRouteGroup::toProvinceKey));
        List<TownRoadRouteGroup> annotatedRouteGroups = annotateRouteGroups(routeGroups);
        List<TownRoadRouteGroup> displayRouteGroups = annotatedRouteGroups.stream()
                .filter(group -> !Boolean.FALSE.equals(group.display()))
                .toList();

        List<ProvinceEdgeView> provinceEdges = edgeAccumulators.values()
                .stream()
                .map(ProvinceEdgeAccumulator::toView)
                .sorted(Comparator.comparing(ProvinceEdgeView::edgeKey))
                .toList();

        List<TownRoadOrder> orders = flatOrderMap.values()
                .stream()
                .filter(order -> !"已完成".equals(order.status()) && !"待装载".equals(order.status()))
                .sorted(orderComparator())
                .map(this::toTownRoadOrder)
                .toList();

        String sourceProvinceName = provinceCodeResolver.shortName(sourceProvinceKey);

        TownRoadRenderCommand command = new TownRoadRenderCommand(
                "town_road_render",
                "town-short-haul-source-" + sourceProvinceKey + "-" + System.currentTimeMillis(),
                sourceProvinceName + "短途区县展示",
                "后端根据外部订单、OD MapMap、省份路网、二级分组和省份边去重生成的短途运输展示",
                new ProvinceRef(sourceProvinceKey, sourceProvinceName),
                new ArrayList<>(renderProvinceSet),
                annotatedRouteGroups,
                displayRouteGroups,
                provinceEdges,
                orders,
                issuedAt
        );

        return new SourceCommandBuildResult(command);
    }

    private TownRoadRouteGroup buildRouteGroup(
            String sourceProvinceKey,
            String targetProvinceKey,
            List<NormalizedTownRoadOrder> primaryOrders,
            List<NormalizedTownRoadOrder> allShortHaulOrders,
            LinkedHashSet<String> renderProvinceSet,
            LinkedHashMap<String, NormalizedTownRoadOrder> flatOrderMap,
            LinkedHashMap<String, ProvinceEdgeAccumulator> edgeAccumulators
    ) {
        String routeGroupId = "town-route-" + sourceProvinceKey + "-" + targetProvinceKey;
        String routeGroupName = provinceCodeResolver.shortName(sourceProvinceKey)
                + " -> "
                + provinceCodeResolver.shortName(targetProvinceKey)
                + " 短途运输";

        List<String> primaryLineIds = primaryOrders.stream()
                .map(NormalizedTownRoadOrder::instanceId)
                .distinct()
                .sorted()
                .toList();

        primaryOrders.forEach(order -> flatOrderMap.put(order.instanceId(), order));

        List<ProvincePath> candidateProvincePaths = candidateProvincePathsForOrders(
                sourceProvinceKey,
                targetProvinceKey,
                primaryOrders
        );
        Map<String, NormalizedTownRoadOrder> representativeOrdersByPathKey = representativeOrdersByPathKey(primaryOrders);

        List<ProvincePathCandidate> candidates = new ArrayList<>();
        LinkedHashSet<String> routeGroupAlongLineIds = new LinkedHashSet<>();
        Integer bestPathCost = candidateProvincePaths.isEmpty() ? null : candidateProvincePaths.get(0).cost();

        for (ProvincePath candidateProvincePath : candidateProvincePaths) {
            List<String> provincePath = candidateProvincePath.provinces();

            provincePath.forEach(renderProvinceSet::add);

            String pathId = candidateProvincePath.pathKey();
            List<String> edgeKeys = candidateProvincePath.edgeKeys();
            NormalizedTownRoadOrder representativeOrder = representativeOrdersByPathKey.get(pathId);

            LinkedHashSet<String> candidateAlongLineIds = new LinkedHashSet<>();

            for (NormalizedTownRoadOrder candidateOrder : allShortHaulOrders) {
                if (primaryLineIds.contains(candidateOrder.instanceId())) {
                    continue;
                }

                if (orderHasAnyPathAsContinuousSubPath(candidateOrder, provincePath)) {
                    candidateAlongLineIds.add(candidateOrder.instanceId());
                    routeGroupAlongLineIds.add(candidateOrder.instanceId());
                    flatOrderMap.put(candidateOrder.instanceId(), candidateOrder);
                }
            }

            List<String> sortedCandidateAlongLineIds = sorted(candidateAlongLineIds);

            for (String edgeKey : edgeKeys) {
                ProvinceEdgeAccumulator accumulator = edgeAccumulators.computeIfAbsent(
                        edgeKey,
                        ignored -> ProvinceEdgeAccumulator.fromEdgeKey(edgeKey, provinceCodeResolver)
                );

                accumulator.routeGroupIds.add(routeGroupId);
                accumulator.pathIds.add(pathId);
                accumulator.primaryOrderLineIds.addAll(primaryLineIds);

                for (String alongLineId : sortedCandidateAlongLineIds) {
                    NormalizedTownRoadOrder alongOrder = ordersByInstanceId.get(alongLineId);
                    if (alongOrder != null && orderUsesProvinceEdge(alongOrder, edgeKey)) {
                        accumulator.alongOrderLineIds.add(alongLineId);
                    }
                }
            }

            candidates.add(new ProvincePathCandidate(
                    pathId,
                    provincePath,
                    provincePath.stream().map(provinceCodeResolver::shortName).toList(),
                    edgeKeys,
                    candidateProvincePath.cost(),
                    bestPathCost != null && candidateProvincePath.cost() == bestPathCost,
                    primaryLineIds,
                    sortedCandidateAlongLineIds,
                    representativeOrder == null ? List.of() : representativeOrder.cityPath(),
                    representativeOrder == null ? List.of() : representativeOrder.cityNames(),
                    representativeOrder == null ? List.of() : representativeOrder.routeCoordinates()
            ));
        }

        candidates.sort(Comparator.comparing(ProvincePathCandidate::pathId));

        return new TownRoadRouteGroup(
                routeGroupId,
                routeGroupName,
                sourceProvinceKey,
                provinceCodeResolver.shortName(sourceProvinceKey),
                targetProvinceKey,
                provinceCodeResolver.shortName(targetProvinceKey),
                primaryLineIds,
                sorted(routeGroupAlongLineIds),
                candidates,
                true,
                false,
                List.of(),
                null
        );
    }

    private List<TownRoadRouteGroup> annotateRouteGroups(List<TownRoadRouteGroup> routeGroups) {
        if (routeGroups.isEmpty()) return List.of();

        List<TownRoadRouteGroup> result = new ArrayList<>();
        for (TownRoadRouteGroup group : routeGroups) {
            List<String> absorbedByGroupIds = routeGroups.stream()
                    .filter(candidate -> isLargerAbsorbingGroup(candidate, group))
                    .map(TownRoadRouteGroup::groupId)
                    .sorted()
                    .toList();

            boolean absorbed = !absorbedByGroupIds.isEmpty();
            result.add(copyRouteGroup(
                    group,
                    !absorbed,
                    absorbed,
                    absorbedByGroupIds,
                    absorbed ? "primary order appears as along order of larger route" : null
            ));
        }

        return result;
    }

    private boolean isLargerAbsorbingGroup(TownRoadRouteGroup candidate, TownRoadRouteGroup target) {
        if (candidate.groupId().equals(target.groupId())) return false;

        int candidateOrderCount = allGroupLineIds(candidate).size();
        int targetOrderCount = allGroupLineIds(target).size();
        if (candidateOrderCount <= targetOrderCount) return false;

        List<String> targetPrimaryLineIds = target.primaryOrderLineIds() == null
                ? List.of()
                : target.primaryOrderLineIds();
        if (targetPrimaryLineIds.isEmpty()) return false;

        Set<String> candidateAlongLineIds = new LinkedHashSet<>(
                candidate.alongOrderLineIds() == null ? List.of() : candidate.alongOrderLineIds()
        );
        return candidateAlongLineIds.containsAll(targetPrimaryLineIds);
    }

    private Set<String> allGroupLineIds(TownRoadRouteGroup group) {
        LinkedHashSet<String> lineIds = new LinkedHashSet<>();
        if (group.primaryOrderLineIds() != null) {
            lineIds.addAll(group.primaryOrderLineIds());
        }
        if (group.alongOrderLineIds() != null) {
            lineIds.addAll(group.alongOrderLineIds());
        }
        return lineIds;
    }

    private TownRoadRouteGroup copyRouteGroup(
            TownRoadRouteGroup group,
            boolean display,
            boolean absorbed,
            List<String> absorbedByGroupIds,
            String absorbedReason
    ) {
        return new TownRoadRouteGroup(
                group.groupId(),
                group.groupName(),
                group.fromProvinceKey(),
                group.fromProvinceName(),
                group.toProvinceKey(),
                group.toProvinceName(),
                group.primaryOrderLineIds(),
                group.alongOrderLineIds(),
                group.candidatePaths(),
                display,
                absorbed,
                absorbedByGroupIds,
                absorbedReason
        );
    }

    private List<ProvincePath> candidateProvincePaths(String fromProvinceKey, String toProvinceKey) {
        return provinceRoadGraph.candidatePaths(
                fromProvinceKey,
                toProvinceKey,
                properties.getMaxCandidateProvinceCount(),
                properties.getCandidateToleranceRatio(),
                properties.getCandidateAbsoluteSlack(),
                properties.getMaxCandidatePathCount()
        );
    }

    private List<ProvincePath> candidateProvincePathsForOrder(
            String fromProvinceKey,
            String toProvinceKey,
            List<String> cityPath,
            List<ProvincePath> fallbackProvincePaths
    ) {
        List<String> provincePath = provincePathFromCityPath(cityPath);
        if (!provincePath.isEmpty()
                && provincePath.get(0).equals(fromProvinceKey)
                && provincePath.get(provincePath.size() - 1).equals(toProvinceKey)) {
            return List.of(new ProvincePath(provincePath, Math.max(0, cityPath.size() - 1)));
        }

        return fallbackProvincePaths == null || fallbackProvincePaths.isEmpty()
                ? candidateProvincePaths(fromProvinceKey, toProvinceKey)
                : fallbackProvincePaths;
    }

    private List<ProvincePath> candidateProvincePathsForOrders(
            String fromProvinceKey,
            String toProvinceKey,
            List<NormalizedTownRoadOrder> orders
    ) {
        LinkedHashMap<String, ProvincePath> pathsByKey = new LinkedHashMap<>();

        for (NormalizedTownRoadOrder order : orders) {
            List<List<String>> provincePaths = order.provincePaths() == null ? List.of() : order.provincePaths();
            List<Integer> costs = order.provincePathCosts() == null ? List.of() : order.provincePathCosts();
            for (int i = 0; i < provincePaths.size(); i++) {
                List<String> path = provincePaths.get(i);
                if (path == null || path.isEmpty()) continue;
                if (!path.get(0).equals(fromProvinceKey) || !path.get(path.size() - 1).equals(toProvinceKey)) {
                    continue;
                }
                int cost = i < costs.size() ? costs.get(i) : Math.max(0, path.size() - 1);
                ProvincePath candidate = new ProvincePath(path, cost);
                pathsByKey.putIfAbsent(candidate.pathKey(), candidate);
            }
        }

        if (pathsByKey.isEmpty()) {
            return candidateProvincePaths(fromProvinceKey, toProvinceKey);
        }

        return pathsByKey.values()
                .stream()
                .sorted(Comparator.comparingInt(ProvincePath::cost).thenComparing(ProvincePath::pathKey))
                .limit(Math.max(1, properties.getMaxCandidatePathCount()))
                .toList();
    }

    private Map<String, NormalizedTownRoadOrder> representativeOrdersByPathKey(List<NormalizedTownRoadOrder> orders) {
        LinkedHashMap<String, NormalizedTownRoadOrder> result = new LinkedHashMap<>();
        for (NormalizedTownRoadOrder order : orders) {
            List<String> pathKeys = order.provincePathKeys() == null ? List.of() : order.provincePathKeys();
            for (String pathKey : pathKeys) {
                if (pathKey == null || pathKey.isBlank()) continue;
                result.putIfAbsent(pathKey, order);
            }
        }
        return result;
    }

    private List<String> cityPathFor(
            ExternalOrderRecord.Location from,
            ExternalOrderRecord.Location to,
            List<ProvincePath> provinceCandidatePaths
    ) {
        String fromCityCode = cityRoadGraph.cityCodeFor(from);
        String toCityCode = cityRoadGraph.cityCodeFor(to);
        if (fromCityCode.isBlank() || toCityCode.isBlank()) return List.of();
        List<String> preferredProvincePath = provinceCandidatePaths == null || provinceCandidatePaths.isEmpty()
                ? List.of()
                : provinceCandidatePaths.get(0).provinces();
        Set<String> allowedProvinceCodes = allowedProvinceCodes(provinceCandidatePaths);
        return cityRoadGraph.shortestPath(fromCityCode, toCityCode, preferredProvincePath, allowedProvinceCodes);
    }

    private Set<String> allowedProvinceCodes(List<ProvincePath> provinceCandidatePaths) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (provinceCandidatePaths == null) return result;
        for (ProvincePath provincePath : provinceCandidatePaths) {
            if (provincePath == null || provincePath.provinces() == null) continue;
            result.addAll(provincePath.provinces());
        }
        return result;
    }

    private List<String> provincePathFromCityPath(List<String> cityPath) {
        if (cityPath == null || cityPath.isEmpty()) return List.of();

        List<String> result = new ArrayList<>();
        for (String cityCode : cityPath) {
            String provinceCode = cityRoadGraph.provinceCode(cityCode);
            if (provinceCode.isBlank()) continue;
            if (result.isEmpty() || !result.get(result.size() - 1).equals(provinceCode)) {
                result.add(provinceCode);
            }
        }
        return result;
    }

    private List<double[]> routeCoordinatesFor(
            ExternalOrderRecord.Location from,
            ExternalOrderRecord.Location to,
            List<String> cityPath,
            List<String> districtPath
    ) {
        List<double[]> coordinates = new ArrayList<>();
        if (hasCoords(from == null ? null : from.coords())) {
            coordinates.add(new double[]{from.coords()[0], from.coords()[1]});
        }

        boolean districtWaypointAdded = false;
        if (districtPath != null && districtPath.size() > 2) {
            for (int i = 1; i < districtPath.size() - 1; i++) {
                DistrictRoadGraph.DistrictInfo districtInfo = districtRoadGraph.getDistrictInfo(districtPath.get(i));
                if (districtInfo == null) continue;
                double[] coords = coordinateResolver.resolveDistrictCenter(
                        districtInfo.provinceName(),
                        districtInfo.name()
                );
                if (hasCoords(coords)) {
                    addDistinctCoordinate(coordinates, coords);
                    districtWaypointAdded = true;
                }
            }
        }

        if (!districtWaypointAdded && cityPath != null && cityPath.size() > 2) {
            for (int i = 1; i < cityPath.size() - 1; i++) {
                CityRoadGraph.CityInfo cityInfo = cityRoadGraph.getCityInfo(cityPath.get(i));
                if (cityInfo == null) continue;
                double[] coords = coordinateResolver.resolveCityCenter(cityInfo.provinceName(), cityInfo.name());
                if (hasCoords(coords)) {
                    addDistinctCoordinate(coordinates, coords);
                }
            }
        }

        if (hasCoords(to == null ? null : to.coords())) {
            addDistinctCoordinate(coordinates, to.coords());
        }

        return coordinates.size() >= 2
                ? chinaBoundaryConstraint.constrainRoute(coordinates)
                : List.of();
    }

    private void addDistinctCoordinate(List<double[]> coordinates, double[] coords) {
        if (!hasCoords(coords)) return;
        double[] next = new double[]{coords[0], coords[1]};
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

    private boolean isShortHaul(NormalizedTownRoadOrder order) {
        return provinceRoadGraph.isSameOrAdjacent(
                order.fromProvinceKey(),
                order.toProvinceKey()
        );
    }

    public List<double[]> baselineRouteCoordinates(String instanceId) {
        List<double[]> coordinates = baselineRouteCoordinatesByInstanceId.get(instanceId);
        return coordinates == null ? List.of() : copyCoordinates(coordinates);
    }

    private Map<String, RoutePlanBundle> planOrderLineRoutes(List<ExternalOrderRecord> expandedRawOrders) {
        Map<String, List<ResolvedRouteSeed>> seedsByKey = new LinkedHashMap<>();
        for (ExternalOrderRecord raw : expandedRawOrders) {
            if (!isValidBasic(raw)) continue;
            ExternalOrderRecord.Location from = coordinateResolver.resolveLocation(raw.from());
            ExternalOrderRecord.Location to = coordinateResolver.resolveLocation(raw.to());
            if (!hasCoords(from == null ? null : from.coords()) || !hasCoords(to == null ? null : to.coords())) {
                continue;
            }
            String key = routePlanKey(raw, from, to);
            seedsByKey.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new ResolvedRouteSeed(raw, from.coords(), to.coords()));
        }

        Map<String, RoutePlanBundle> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResolvedRouteSeed>> entry : seedsByKey.entrySet()) {
            ResolvedRouteSeed representative = entry.getValue().get(0);
            RoutePlanningService.PlannedRoute baseline = routePlanningService.plan(
                    representative.fromCoords(), representative.toCoords());
            result.put(entry.getKey(), new RoutePlanBundle(baseline));
            log.info("[RoutePlan] order-line baseline prepared: key={}, provider={}",
                    entry.getKey(), baseline.provider());
        }
        return Map.copyOf(result);
    }

    private String routePlanKey(
            ExternalOrderRecord raw,
            ExternalOrderRecord.Location from,
            ExternalOrderRecord.Location to
    ) {
        return String.join("::",
                firstNonBlank(raw.orderId(), "unknown-order"),
                firstNonBlank(raw.lineId(), "unknown-line"),
                coordinateKey(from == null ? null : from.coords()),
                coordinateKey(to == null ? null : to.coords()));
    }

    private String coordinateKey(double[] coordinate) {
        if (!hasCoords(coordinate)) return "missing";
        return String.format(Locale.ROOT, "%.5f,%.5f", coordinate[0], coordinate[1]);
    }

    private List<double[]> copyCoordinates(List<double[]> coordinates) {
        if (coordinates == null) return List.of();
        return coordinates.stream().filter(this::hasCoords).map(this::copyCoordinate).toList();
    }

    private double[] copyCoordinate(double[] coordinate) {
        return new double[]{coordinate[0], coordinate[1]};
    }

    private record ResolvedRouteSeed(
            ExternalOrderRecord raw,
            double[] fromCoords,
            double[] toCoords
    ) {}

    private record RoutePlanBundle(RoutePlanningService.PlannedRoute baseline) {}

    private NormalizedTownRoadOrder newerOrder(
            NormalizedTownRoadOrder first,
            NormalizedTownRoadOrder second
    ) {
        String firstUpdatedAt = safe(first.updatedAt());
        String secondUpdatedAt = safe(second.updatedAt());
        return secondUpdatedAt.compareTo(firstUpdatedAt) >= 0 ? second : first;
    }

    /** 已完成订单是否超过保留时间窗口（默认30分钟） */
    private boolean isCompletedExpired(String updatedAt) {
        if (updatedAt == null || updatedAt.isBlank()) return true;
        try {
            java.time.LocalDateTime updated = java.time.LocalDateTime.parse(
                    updatedAt.trim().replace(" ", "T"));
            java.time.LocalDateTime deadline = java.time.LocalDateTime.now()
                    .minusMinutes(properties.getCompletedRetentionMinutes());
            return updated.isBefore(deadline);
        } catch (Exception e) {
            return true; // 解析失败，保守过滤
        }
    }

    /**
     * 预过滤：判断一条原始订单是否值得调用百度/高德路线规划API。
     * 在 {@link #processSnapshot} 中先于路线规划执行，避免对
     * 已取消、待装载、已完成超时等注定被丢弃的订单浪费外部API调用。
     */
    private boolean isWorthRoutePlanning(ExternalOrderRecord raw) {
        if (!isValidBasic(raw)) return false;

        ExternalOrderRecord.Location resolvedFrom = coordinateResolver.resolveLocation(raw.from());
        ExternalOrderRecord.Location resolvedTo = coordinateResolver.resolveLocation(raw.to());
        if (!hasCoords(resolvedFrom.coords()) || !hasCoords(resolvedTo.coords())) return false;

        String status = raw.status();
        if (Boolean.TRUE.equals(raw.deleted())) return false;
        if ("已取消".equals(status)) return false;
        if ("待装载".equals(status)) return false;
        if ("已完成".equals(status)) return false;

        return true;
    }

    private boolean orderHasAnyPathAsContinuousSubPath(
            NormalizedTownRoadOrder order,
            List<String> candidateProvincePath
    ) {
        if (order.provincePaths() == null || order.provincePaths().isEmpty()) {
            return false;
        }

        for (List<String> orderPath : order.provincePaths()) {
            if (isContinuousSubPath(candidateProvincePath, orderPath)) {
                return true;
            }
        }

        return false;
    }

    private boolean orderUsesProvinceEdge(NormalizedTownRoadOrder order, String edgeKey) {
        if (order.provincePaths() == null || order.provincePaths().isEmpty()) {
            return false;
        }

        for (List<String> orderPath : order.provincePaths()) {
            if (provinceRoadGraph.edgeKeys(orderPath).contains(edgeKey)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断 smallPath 是否是 fullPath 的连续子路径，方向必须一致。
     */
    private boolean isContinuousSubPath(List<String> fullPath, List<String> smallPath) {
        if (fullPath == null || smallPath == null) return false;
        if (smallPath.isEmpty()) return false;
        if (smallPath.size() > fullPath.size()) return false;

        for (int start = 0; start <= fullPath.size() - smallPath.size(); start++) {
            boolean matched = true;
            for (int i = 0; i < smallPath.size(); i++) {
                if (!fullPath.get(start + i).equals(smallPath.get(i))) {
                    matched = false;
                    break;
                }
            }
            if (matched) return true;
        }

        return false;
    }

    private boolean isRenderable(NormalizedTownRoadOrder order) {
        return hasCoords(order.from() == null ? null : order.from().coords())
                && hasCoords(order.to() == null ? null : order.to().coords());
    }

    private boolean isRoadMapRenderable(NormalizedTownRoadOrder order) {
        return isRenderable(order);
    }

    private boolean isDispatchableStatus(NormalizedTownRoadOrder order) {
        if ("待装载".equals(order.status())) {
            return false;
        }
        if ("已完成".equals(order.status())) {
            return !isCompletedExpired(order.updatedAt());
        }
        return true;
    }

    private boolean isValidBasic(ExternalOrderRecord raw) {
        if (raw == null) return false;
        if (raw.lineId() == null || raw.lineId().isBlank()) return false;
        if (raw.from() == null || raw.to() == null) return false;

        String fromKey = locationKey(raw.from());
        String toKey = locationKey(raw.to());
        if (fromKey.isBlank() || toKey.isBlank()) return false;

        String fromProvinceKey = provinceCodeResolver.provinceKey(raw.from());
        String toProvinceKey = provinceCodeResolver.provinceKey(raw.to());

        if (fromProvinceKey.isBlank() || toProvinceKey.isBlank()) return false;

        return provinceRoadGraph.hasProvince(fromProvinceKey)
                && provinceRoadGraph.hasProvince(toProvinceKey);
    }

    private TownRoadOrder toTownRoadOrder(NormalizedTownRoadOrder order) {
        return new TownRoadOrder(
                order.orderId(),
                order.instanceId(),
                order.lineId(),
                order.instanceId(),
                order.vehicleKey(),
                order.groupId(),
                order.groupName(),
                order.from(),
                order.to(),
                order.vehicle(),
                order.status(),
                order.updatedAt(),
                order.deleted(),
                order.routeCoordinates(),
                order.routeLengthKm(),
                order.speedKmh(),
                order.cityPath(),
                order.cityNames()
        );
    }

    private String locationKey(ExternalOrderRecord.Location location) {
        if (location == null) return "";

        String adcode = safe(location.adcode());
        if (!adcode.isBlank()) return adcode;

        String province = safe(location.province());
        String city = safe(location.city());
        String district = safe(location.district());
        String name = safe(location.name());
        String tail = !district.isBlank() ? district : name;

        List<String> parts = new ArrayList<>();
        if (!province.isBlank()) parts.add(province);
        if (!city.isBlank()) parts.add(city);
        if (!tail.isBlank()) parts.add(tail);

        return String.join("|", parts);
    }

    private List<String> parseProvincePathKey(String provincePathKey) {
        if (provincePathKey == null || provincePathKey.isBlank()) {
            return List.of();
        }

        return List.of(provincePathKey.split(">"))
                .stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<Double> coordsForSignature(double[] coords) {
        if (!hasCoords(coords)) return List.of();
        return List.of(coords[0], coords[1]);
    }

    private boolean hasCoords(double[] coords) {
        return coords != null && coords.length >= 2;
    }

    private double pathLengthKm(List<double[]> coordinates) {
        if (coordinates == null || coordinates.size() < 2) return 0;
        double total = 0;
        for (int i = 1; i < coordinates.size(); i++) {
            total += distanceKm(coordinates.get(i - 1), coordinates.get(i));
        }
        return total;
    }

    private double distanceKm(double[] start, double[] end) {
        if (!hasCoords(start) || !hasCoords(end)) return 0;
        double earthRadiusKm = 6371.0088;
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

    private String signature(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return String.valueOf(data);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String safeValue = safe(value);
            if (!safeValue.isBlank()) return safeValue;
        }
        return "";
    }

    private <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private String vehicleKey(ExternalOrderRecord raw) {
        ExternalOrderRecord.Vehicle vehicle = raw == null ? null : raw.vehicle();
        if (vehicle == null) {
            return "vehicle-" + safeIndex(raw == null ? null : raw.vehicleIndex());
        }

        String carId = safe(vehicle.carId());
        if (!carId.isBlank()) return "car-" + carId;

        String plate = safe(vehicle.plate());
        if (!plate.isBlank()) return "plate-" + plate;

        return "vehicle-" + safeIndex(raw.vehicleIndex());
    }

    private String instanceId(
            String orderId,
            String lineId,
            Integer lineIndex,
            Integer vehicleIndex,
            String vehicleKey
    ) {
        return String.join(
                "::",
                firstNonBlank(orderId, "unknown-order"),
                firstNonBlank(lineId, "unknown-line"),
                "line-" + safeIndex(lineIndex),
                firstNonBlank(vehicleKey, "vehicle-" + safeIndex(vehicleIndex))
        );
    }

    private String safeIndex(Integer index) {
        return index == null ? "0" : String.valueOf(index);
    }

    private Comparator<NormalizedTownRoadOrder> orderComparator() {
        return Comparator.comparing(NormalizedTownRoadOrder::orderId)
                .thenComparing(NormalizedTownRoadOrder::lineId)
                .thenComparing(NormalizedTownRoadOrder::instanceId);
    }

    private List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private record SourceCommandBuildResult(TownRoadRenderCommand command) {
    }

    private static final class ProvinceEdgeAccumulator {
        private final String edgeKey;
        private final String fromProvinceKey;
        private final String fromProvinceName;
        private final String toProvinceKey;
        private final String toProvinceName;
        private final LinkedHashSet<String> routeGroupIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> pathIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> primaryOrderLineIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> alongOrderLineIds = new LinkedHashSet<>();

        private ProvinceEdgeAccumulator(
                String edgeKey,
                String fromProvinceKey,
                String fromProvinceName,
                String toProvinceKey,
                String toProvinceName
        ) {
            this.edgeKey = edgeKey;
            this.fromProvinceKey = fromProvinceKey;
            this.fromProvinceName = fromProvinceName;
            this.toProvinceKey = toProvinceKey;
            this.toProvinceName = toProvinceName;
        }

        private static ProvinceEdgeAccumulator fromEdgeKey(
                String edgeKey,
                ProvinceCodeResolver resolver
        ) {
            String[] parts = edgeKey.split("->");
            String from = parts.length > 0 ? parts[0] : "";
            String to = parts.length > 1 ? parts[1] : "";
            return new ProvinceEdgeAccumulator(
                    edgeKey,
                    from,
                    resolver.shortName(from),
                    to,
                    resolver.shortName(to)
            );
        }

        private ProvinceEdgeView toView() {
            LinkedHashSet<String> allOrderLineIds = new LinkedHashSet<>();
            allOrderLineIds.addAll(primaryOrderLineIds);
            allOrderLineIds.addAll(alongOrderLineIds);

            return new ProvinceEdgeView(
                    edgeKey,
                    fromProvinceKey,
                    fromProvinceName,
                    toProvinceKey,
                    toProvinceName,
                    routeGroupIds.stream().sorted().toList(),
                    pathIds.stream().sorted().toList(),
                    primaryOrderLineIds.stream().sorted().toList(),
                    alongOrderLineIds.stream().sorted().toList(),
                    allOrderLineIds.stream().sorted().toList(),
                    allOrderLineIds.size()
            );
        }
    }
}
