package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.dto.RenderRouteDTO;
import com.jushen.digitaltwin.dto.Rm2RouteGroupDTO;
import com.jushen.digitaltwin.dto.Rm2Snapshot;
import com.jushen.digitaltwin.dto.RouteDtoConverter;
import com.jushen.digitaltwin.service.RoutePushService;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TownRoadRenderService {

    private static final Logger log = LoggerFactory.getLogger(TownRoadRenderService.class);
    public static final int RM2_GROUP_SIZE = 3;

    private final TownRoadExternalOrderClient townRoadExternalOrderClient;
    private final TownRoadMiddleLayer middleLayer;
    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final AmapGeocodeClient amapGeocodeClient;
    private final TownRoadCoordinateResolver coordinateResolver;
    private final RoutePushService routePushService;
    private final TownRoadExternalOrderProperties externalOrderProperties;
    private Map<String, Object> lastResult = Map.of();
    /** RM2 原子快照 */
    private volatile Rm2Snapshot latestRm2Snapshot = new Rm2Snapshot(
            "0", Instant.now(), List.of(), List.of(),
            com.jushen.digitaltwin.dto.Rm2ChainStructureDTO.empty(), Map.of(), Map.of()
    );
    /** 上一版 groupId 集合，用于计算 removed */
    private volatile Set<String> previousRm2GroupIds = Set.of();
    /** 上一版 lineId→groupId 索引，用于反查 changedGroupIds */
    private volatile Map<String, String> previousGroupIdByLineId = Map.of();
    /** 上一版快照内容指纹 */
    private volatile String previousFingerprint = "";

    public Rm2Snapshot getLatestRm2Snapshot() { return latestRm2Snapshot; }

    public Map<String, Object> latestResult() {
        return lastResult;
    }

    public TownRoadRenderService(
            TownRoadExternalOrderClient townRoadExternalOrderClient,
            TownRoadMiddleLayer middleLayer,
            RealtimeWebSocketHandler realtimeWebSocketHandler,
            AmapGeocodeClient amapGeocodeClient,
            TownRoadCoordinateResolver coordinateResolver,
            RoutePushService routePushService,
            TownRoadExternalOrderProperties externalOrderProperties
    ) {
        this.townRoadExternalOrderClient = townRoadExternalOrderClient;
        this.middleLayer = middleLayer;
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
        this.amapGeocodeClient = amapGeocodeClient;
        this.coordinateResolver = coordinateResolver;
        this.routePushService = routePushService;
        this.externalOrderProperties = externalOrderProperties;
    }

    public Map<String, Object> fetchProcessAndBroadcast() {
        List<ExternalOrderRecord> rawOrders = townRoadExternalOrderClient.fetchOrders();
        return processAndBroadcast(rawOrders);
    }

    public Map<String, Object> processAndBroadcast(List<ExternalOrderRecord> rawOrders) {
        return processAndBroadcastInternal(rawOrders, false);
    }

    public Map<String, Object> processAndBroadcastWithTrace(List<ExternalOrderRecord> rawOrders) {
        return processAndBroadcastInternal(rawOrders, true);
    }

    private Map<String, Object> processAndBroadcastInternal(List<ExternalOrderRecord> rawOrders, boolean traceEnabled) {
        long startedAt = System.currentTimeMillis();
        int inputRawCount = rawOrders == null ? 0 : rawOrders.size();
        Map<String, Object> pipeline = traceEnabled ? new LinkedHashMap<>() : null;
        if (traceEnabled) {
            pipeline.put("input", inputSummary(rawOrders));
        }

        // 入口去重：同订单+同线路+同车牌号，保留 updatedAt 最晚的记录
        DeduplicationResult deduplication = deduplicateOrders(rawOrders);
        List<ExternalOrderRecord> dedupedOrders = deduplication.orders();
        if (deduplication.removedCount() > 0) {
            log.info("入口去重: input={}, unique={}, removed={}, duplicateKeys={}, samples={}",
                    deduplication.inputCount(), dedupedOrders.size(), deduplication.removedCount(),
                    deduplication.duplicateKeyCount(), deduplication.sampleDuplicateKeys());
        }
        if (traceEnabled) {
            pipeline.put("deduplication", deduplication.toMap());
        }

        long middleLayerStartedAt = System.currentTimeMillis();
        ExternalOrderSnapshotResult result = middleLayer.processSnapshot(dedupedOrders);
        long middleLayerFinishedAt = System.currentTimeMillis();
        if (traceEnabled) {
            pipeline.put("middleLayer", middleLayerSummary(result, middleLayerFinishedAt - middleLayerStartedAt));
        }

        // 构造 RM2 分组和索引
        List<NormalizedTownRoadOrder> eligibleShortHaulOrders = result.shortHaulOrders().stream()
                .filter(this::shouldIncludeOrderForRealPositionMode)
                .toList();
        // 先注册运行路线，后续 routes 快照直接使用同一运行池计算出的速度、距离和时长。
        int townRouteDispatchCount = registerShortHaulRoutesForPositions(eligibleShortHaulOrders);
        Set<String> eligibleShortHaulLineIds = eligibleShortHaulOrders.stream()
                .map(NormalizedTownRoadOrder::instanceId)
                .filter(lineId -> lineId != null && !lineId.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> positionWarmup = routePushService.warmPositionCacheForLineIds(eligibleShortHaulLineIds);
        List<RenderRouteDTO> rm2Routes = RouteDtoConverter.shortHaulOrdersToRoutes(eligibleShortHaulOrders).stream()
                .map(this::withRuntimeMetrics)
                .filter(route -> route != null)
                .filter(this::isPublishableRm2Route)
                .toList();
        List<Rm2RouteGroupDTO> rm2Groups = RouteDtoConverter.buildStableGroups(rm2Routes, RM2_GROUP_SIZE);
        com.jushen.digitaltwin.dto.Rm2ChainStructureDTO rm2ChainStructure =
                RouteDtoConverter.buildRm2ChainStructure(rm2Groups);

        Map<String, RenderRouteDTO> routeByLineId = new LinkedHashMap<>();
        for (RenderRouteDTO r : rm2Routes) routeByLineId.put(r.lineId(), r);

        Map<String, List<RenderRouteDTO>> routesByGroupId = new LinkedHashMap<>();
        Map<String, String> groupIdByLineId = new LinkedHashMap<>();
        for (Rm2RouteGroupDTO group : rm2Groups) {
            List<RenderRouteDTO> groupRoutes = new ArrayList<>();
            for (String lineId : group.orderLineIds()) {
                RenderRouteDTO route = routeByLineId.get(lineId);
                if (route == null) continue;
                RenderRouteDTO withGroupId = new RenderRouteDTO(
                        route.lineId(), route.orderId(), route.plate(), route.vehicleId(),
                        route.from(), route.to(), route.fromCoords(), route.toCoords(),
                        route.coordinates(), route.routeLengthKm(), route.speedKmh(),
                        route.status(), route.cargo(), route.travelDurationMs(),
                        route.pathKey(), route.scope(), group.groupId(), route.role(),
                        route.coordinateSystem(), route.updatedAt(), route.routeSignature(),
                        route.meta()
                );
                groupRoutes.add(withGroupId);
                groupIdByLineId.put(lineId, group.groupId());
            }
            routesByGroupId.put(group.groupId(), List.copyOf(groupRoutes));
        }
        Map<String, List<RenderRouteDTO>> immutableRoutesByGroupId = Map.copyOf(routesByGroupId);
        Map<String, String> immutableGroupIdByLineId = Map.copyOf(groupIdByLineId);

        // SHA-256 内容指纹，只有 RM2 变化才更新快照和广播
        String version = rm2Fingerprint(rm2Groups, routeByLineId);
        boolean rm2SnapshotChanged = !version.equals(previousFingerprint);

        if (rm2SnapshotChanged) {
            this.previousFingerprint = version;

            List<RenderRouteDTO> assignedRoutes = routesByGroupId.values().stream()
                    .flatMap(List::stream).toList();
            this.latestRm2Snapshot = new Rm2Snapshot(version, Instant.now(),
                    List.copyOf(assignedRoutes), List.copyOf(rm2Groups), rm2ChainStructure,
                    immutableRoutesByGroupId, immutableGroupIdByLineId);
            routePushService.syncRm2PositionGroups(immutableGroupIdByLineId, version);

            Set<String> currentGroupIds = new LinkedHashSet<>();
            for (Rm2RouteGroupDTO g : rm2Groups) currentGroupIds.add(g.groupId());
            Set<String> changedGroupIds = collectChangedGroupIds(result.diff(), groupIdByLineId, previousGroupIdByLineId);
            Set<String> removedGroupIds = new LinkedHashSet<>(previousRm2GroupIds);
            removedGroupIds.removeAll(currentGroupIds);
            this.previousRm2GroupIds = currentGroupIds;
            this.previousGroupIdByLineId = immutableGroupIdByLineId;

            // 快照变了就必须广播：changedGroupIds 为空时兜底为全部 currentGroupIds
            if (changedGroupIds.isEmpty() && removedGroupIds.isEmpty()) {
                changedGroupIds = new LinkedHashSet<>(currentGroupIds);
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "route_snapshot_changed");
            event.put("scope", "rm2");
            event.put("snapshotVersion", version);
            event.put("changedGroupIds", new ArrayList<>(changedGroupIds));
            event.put("removedGroupIds", new ArrayList<>(removedGroupIds));
            event.put("serverTime", Instant.now().toString());
            realtimeWebSocketHandler.broadcastToScopeSubscribers("rm2", event);
        }

        // RM1 长途 + RM2 短途车辆注册。RM2 必须从与 REST groups/routes
        // 完全相同的 eligibleShortHaulOrders 来源注册，保证 lineId 可被位置接口查到。
        int deletedOrCancelled = result.diff().deletedOrCancelled();
        int rawCount = result.rawCount();
        int normalizedCount = result.normalizedCount();
        int shortHaulCount = result.shortHaulCount();
        int longHaulCount = result.longHaulCount();
        int skippedInvalid = result.diff().skippedInvalid();
        int skippedNotRenderable = result.diff().skippedNotRenderable();
        int skippedLongHaul = result.diff().skippedLongHaul();
        int roadMapRouteCount = dispatchLongHaulRoutesToRoadMap(result.longHaulOrders());

        String snapshotVersion = latestRm2Snapshot.snapshotVersion();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("type", "town_road_render");
        response.put("message", "town_road_render commands broadcasted");
        response.put("rawCount", rawCount);
        response.put("normalizedCount", normalizedCount);
        response.put("shortHaulCount", shortHaulCount);
        response.put("longHaulCount", longHaulCount);
        response.put("roadMapRouteCount", roadMapRouteCount);
        response.put("commandCount", result.commands().size());
        response.put("displayMode", result.commands().size() > 1 ? "multi_source_rotation" : "single_source");
        response.put("diff", result.diff().toMap());
        response.put("snapshotVersion", snapshotVersion);
        response.put("rm2SnapshotChanged", rm2SnapshotChanged);
        response.put("rm2Routes", rm2Routes.size());
        response.put("rm2Groups", rm2Groups.size());
        response.put("deduplication", deduplication.toMap());
        response.put("rm2PositionWarmup", positionWarmup);

        int skippedByStatus = normalizedCount - shortHaulCount - skippedNotRenderable - skippedLongHaul;
        Map<String, Object> accounting = new LinkedHashMap<>();
        accounting.put("rawCount", rawCount);
        accounting.put("    ─ skippedInvalid (基础校验失败)", skippedInvalid);
        accounting.put("    ─ deletedOrCancelled (已删除/已取消)", deletedOrCancelled);
        accounting.put("    = normalizedCount (有效订单)", normalizedCount);
        accounting.put("        ─ skippedNotRenderable (缺坐标)", skippedNotRenderable);
        accounting.put("        ─ skippedLongHaul (非短途)", skippedLongHaul);
        accounting.put("            └ roadMapRouteCount (发送 RoadMap)", roadMapRouteCount);
        accounting.put("        ─ skippedByStatus (已完成/待装载)", skippedByStatus);
        accounting.put("        = shortHaulCount (最终渲染)", shortHaulCount);
        accounting.put("校验", rawCount + " = " + skippedInvalid + " + " + deletedOrCancelled + " + " + normalizedCount
                + (rawCount == skippedInvalid + deletedOrCancelled + normalizedCount ? " ✅" : " ❌ 对不上!"));
        accounting.put("渲染过滤", normalizedCount + " = " + skippedNotRenderable + " + " + skippedLongHaul + " + " + skippedByStatus + " + " + shortHaulCount
                + (normalizedCount == skippedNotRenderable + skippedLongHaul + skippedByStatus + shortHaulCount ? " ✅" : " ❌ 对不上!"));
        response.put("accounting", accounting);

        log.info(coordinateResolver.getStatsAndReset());
        log.info(amapGeocodeClient.getStatsAndReset());

        response.put("rm2PositionRouteCount", townRouteDispatchCount);

        if (traceEnabled) {
            pipeline.put("output", outputSummary(result, roadMapRouteCount, townRouteDispatchCount));
            Map<String, Object> timings = new LinkedHashMap<>();
            timings.put("inputRawCount", inputRawCount);
            timings.put("middleLayerMs", middleLayerFinishedAt - middleLayerStartedAt);
            timings.put("totalMs", System.currentTimeMillis() - startedAt);
            pipeline.put("timings", timings);
            response.put("pipeline", pipeline);
        }

        this.lastResult = response;
        return response;
    }

    // ---------------------------------------------------------------
    // 指纹与变化检测
    // ---------------------------------------------------------------

    /**
     * 完整渲染字段 SHA-256，取前 16 位 hex。
     * 包含：groupId, lineId, routeSignature, status, updatedAt,
     * plate, vehicleId, cargo, speedKmh, routeLengthKm, from, to, role, coordinateSystem
     */
    private String rm2Fingerprint(List<Rm2RouteGroupDTO> groups, Map<String, RenderRouteDTO> routeByLineId) {
        StringBuilder sb = new StringBuilder();
        for (Rm2RouteGroupDTO g : groups) {
            sb.append(g.groupId()).append('|');
            for (String lineId : g.orderLineIds()) {
                RenderRouteDTO r = routeByLineId.get(lineId);
                if (r == null) continue;
                sb.append(r.lineId()).append('|')
                        .append(r.routeSignature()).append('|')
                        .append(r.status()).append('|')
                        .append(r.updatedAt()).append('|')
                        .append(r.plate()).append('|')
                        .append(r.vehicleId()).append('|')
                        .append(r.cargo()).append('|')
                        .append(r.speedKmh()).append('|')
                        .append(r.routeLengthKm()).append('|')
                        .append(r.from()).append('|')
                        .append(r.to()).append('|')
                        .append(r.role()).append('|')
                        .append(r.coordinateSystem()).append('|');
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) { // 前 16 位 hex
                hex.append(String.format("%02x", digest[i]));
            }
            return "rm2-" + hex;
        } catch (NoSuchAlgorithmException e) {
            // fallback to hashCode
            return "rm2-" + Integer.toHexString(sb.toString().hashCode());
        }
    }

    /**
     * 从 diff 的 changed lineIds 反查 groupId。
     * 新增/更新/路线变化用新 groupIdByLineId，删除用旧 previousGroupIdByLineId。
     */
    private Set<String> collectChangedGroupIds(
            OrderSnapshotDiff diff,
            Map<String, String> groupIdByLineId,
            Map<String, String> previousGroupIdByLineId
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (String lineId : diff.addedLineIds()) {
            String gid = groupIdByLineId.get(lineId);
            if (gid != null) result.add(gid);
        }
        for (String lineId : diff.updatedLineIds()) {
            String gid = groupIdByLineId.get(lineId);
            if (gid != null) result.add(gid);
        }
        for (String lineId : diff.routeChangedLineIds()) {
            String gid = groupIdByLineId.get(lineId);
            if (gid != null) result.add(gid);
        }
        for (String lineId : diff.deletedLineIds()) {
            String gid = previousGroupIdByLineId.get(lineId);
            if (gid != null) result.add(gid);
        }
        return result;
    }

    // ---------------------------------------------------------------
    // 入口去重
    // ---------------------------------------------------------------

    /**
     * 同订单(orderId)+同线路(lineId)+同车牌号(vehicle.plate) → 保留 updatedAt 最晚的记录。
     */
    private DeduplicationResult deduplicateOrders(List<ExternalOrderRecord> orders) {
        if (orders == null || orders.isEmpty()) {
            return new DeduplicationResult(0, List.of(), 0, 0, List.of());
        }

        List<ExternalOrderRecord> sorted = new ArrayList<>(orders);
        sorted.sort(Comparator.comparing(
                r -> r.updatedAt() != null ? r.updatedAt() : "",
                Comparator.reverseOrder()
        ));

        Map<String, ExternalOrderRecord> seen = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ExternalOrderRecord r : sorted) {
            String key = dedupeKey(r);
            counts.merge(key, 1, Integer::sum);
            seen.putIfAbsent(key, r);
        }
        List<String> duplicateSamples = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .limit(8)
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .toList();
        int duplicateKeyCount = (int) counts.values().stream().filter(count -> count > 1).count();
        List<ExternalOrderRecord> deduped = List.copyOf(seen.values());
        return new DeduplicationResult(
                orders.size(),
                deduped,
                orders.size() - deduped.size(),
                duplicateKeyCount,
                duplicateSamples
        );
    }

    private String dedupeKey(ExternalOrderRecord record) {
        if (record == null) return "null|null|null";
        ExternalOrderRecord.Vehicle vehicle = record.vehicle();
        String plateCandidate = vehicle == null ? "" : firstNonBlank(vehicle.plate(), vehicle.carId());
        return safeDedupePart(record.orderId())
                + "|" + safeDedupePart(record.lineId())
                + "|" + normalizePlateForDedupe(plateCandidate);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private String safeDedupePart(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String normalizePlateForDedupe(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        int separatorIndex = normalized.indexOf('-');
        return separatorIndex > 0 ? normalized.substring(0, separatorIndex) : normalized;
    }

    // ---------------------------------------------------------------
    // helpers (unchanged)
    // ---------------------------------------------------------------

    private Map<String, Object> inputSummary(List<ExternalOrderRecord> rawOrders) {
        List<ExternalOrderRecord> safeRawOrders = rawOrders == null ? List.of() : rawOrders;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rawCount", safeRawOrders.size());
        summary.put("samples", safeRawOrders.stream().limit(5).map(this::rawOrderSample).toList());
        return summary;
    }

    private Map<String, Object> rawOrderSample(ExternalOrderRecord order) {
        Map<String, Object> sample = new LinkedHashMap<>();
        if (order == null) { sample.put("null", true); return sample; }
        sample.put("orderId", order.orderId());
        sample.put("lineId", order.lineId());
        sample.put("status", order.status());
        sample.put("from", locationSample(order.from()));
        sample.put("to", locationSample(order.to()));
        sample.put("vehicle", vehicleSample(order.vehicle()));
        sample.put("lineCount", order.lines() == null ? 0 : order.lines().size());
        return sample;
    }

    private Map<String, Object> locationSample(ExternalOrderRecord.Location location) {
        Map<String, Object> sample = new LinkedHashMap<>();
        if (location == null) { sample.put("null", true); return sample; }
        sample.put("name", location.name());
        sample.put("province", location.province());
        sample.put("city", location.city());
        sample.put("district", location.district());
        sample.put("adcode", location.adcode());
        sample.put("hasCoords", location.coords() != null && location.coords().length >= 2);
        return sample;
    }

    private Map<String, Object> vehicleSample(ExternalOrderRecord.Vehicle vehicle) {
        Map<String, Object> sample = new LinkedHashMap<>();
        if (vehicle == null) { sample.put("null", true); return sample; }
        sample.put("plate", vehicle.plate());
        sample.put("carId", vehicle.carId());
        sample.put("hasCurrentCoords", vehicle.currentCoords() != null && vehicle.currentCoords().length >= 2);
        sample.put("speedKmh", vehicle.speedKmh());
        return sample;
    }

    private Map<String, Object> middleLayerSummary(ExternalOrderSnapshotResult result, long elapsedMs) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("elapsedMs", elapsedMs);
        summary.put("rawCount", result.rawCount());
        summary.put("normalizedCount", result.normalizedCount());
        summary.put("shortHaulCount", result.shortHaulCount());
        summary.put("longHaulCount", result.longHaulCount());
        summary.put("diff", result.diff().toMap());
        summary.put("longHaulSamples", result.longHaulOrders() == null ? List.of()
                : result.longHaulOrders().stream().limit(5).map(this::normalizedOrderSample).toList());
        return summary;
    }

    private Map<String, Object> normalizedOrderSample(NormalizedTownRoadOrder order) {
        Map<String, Object> sample = new LinkedHashMap<>();
        if (order == null) { sample.put("null", true); return sample; }
        sample.put("instanceId", order.instanceId());
        sample.put("orderId", order.orderId());
        sample.put("lineId", order.lineId());
        sample.put("status", order.status());
        sample.put("fromProvinceKey", order.fromProvinceKey());
        sample.put("toProvinceKey", order.toProvinceKey());
        sample.put("provincePathKeys", order.provincePathKeys());
        sample.put("cityPath", order.cityPath());
        sample.put("cityNames", order.cityNames());
        sample.put("routeCoordinateCount", order.routeCoordinates() == null ? 0 : order.routeCoordinates().size());
        sample.put("routeLengthKm", order.routeLengthKm());
        return sample;
    }

    private Map<String, Object> outputSummary(ExternalOrderSnapshotResult result, int roadMapRouteCount, int townRouteDispatchCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("broadcastCommandCount", result.commands().size());
        summary.put("roadMapRouteCount", roadMapRouteCount);
        summary.put("townRouteDispatchCount", townRouteDispatchCount);
        summary.put("commands", result.commands().stream().map(this::commandSample).toList());
        return summary;
    }

    private Map<String, Object> commandSample(TownRoadRenderCommand command) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("commandId", command.commandId());
        sample.put("sourceProvince", command.sourceProvince());
        sample.put("renderProvinces", command.renderProvinces());
        sample.put("routeGroupCount", command.routeGroups() == null ? 0 : command.routeGroups().size());
        sample.put("displayRouteGroupCount", command.displayRouteGroups() == null ? 0 : command.displayRouteGroups().size());
        sample.put("provinceEdgeCount", command.provinceEdges() == null ? 0 : command.provinceEdges().size());
        sample.put("orderCount", command.orders() == null ? 0 : command.orders().size());
        sample.put("routeGroups", command.routeGroups() == null ? List.of()
                : command.routeGroups().stream().limit(5).map(this::routeGroupSample).toList());
        return sample;
    }

    private Map<String, Object> routeGroupSample(TownRoadRenderCommand.TownRoadRouteGroup group) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("groupId", group.groupId());
        sample.put("fromProvinceKey", group.fromProvinceKey());
        sample.put("toProvinceKey", group.toProvinceKey());
        sample.put("primaryOrderLineIds", group.primaryOrderLineIds());
        sample.put("alongOrderLineIds", group.alongOrderLineIds());
        sample.put("candidatePathCount", group.candidatePaths() == null ? 0 : group.candidatePaths().size());
        sample.put("candidatePaths", group.candidatePaths() == null ? List.of()
                : group.candidatePaths().stream().limit(3).map(this::candidatePathSample).toList());
        return sample;
    }

    private Map<String, Object> candidatePathSample(TownRoadRenderCommand.ProvincePathCandidate candidate) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("pathId", candidate.pathId());
        sample.put("provincePath", candidate.provincePath());
        sample.put("cityNames", candidate.cityNames());
        sample.put("cityCoordinateCount", candidate.cityCoordinates() == null ? 0 : candidate.cityCoordinates().size());
        sample.put("pathCost", candidate.pathCost());
        sample.put("bestPath", candidate.bestPath());
        return sample;
    }

    private int dispatchLongHaulRoutesToRoadMap(List<NormalizedTownRoadOrder> longHaulOrders) {
        if (longHaulOrders == null || longHaulOrders.isEmpty()) return 0;
        int dispatched = 0;
        for (NormalizedTownRoadOrder order : longHaulOrders) {
            if (order == null || order.from() == null || order.to() == null) continue;
            if (!shouldIncludeOrderForRealPositionMode(order)) continue;
            double[] fc = order.from().coords();
            double[] tc = order.to().coords();
            if (fc == null || tc == null || fc.length < 2 || tc.length < 2) continue;
            ExternalOrderRecord.Vehicle vehicle = order.vehicle();
            Double cargoWeight = vehicle == null ? null : vehicle.cargoWeight();
            Integer orderTotalTons = cargoWeight == null ? null : Math.max(0, (int) Math.round(cargoWeight));
            routePushService.dispatchExternalOrderRoute(
                    order.instanceId(), order.orderId() != null ? order.orderId() : order.instanceId(),
                    order.groupName(), orderTotalTons,
                    order.from().name(), order.to().name(),
                    order.from().province(), order.to().province(),
                    fc, tc, order.routeCoordinates(),
                    vehicle == null ? null : vehicle.currentCoords(),
                    vehicle == null ? null : vehicle.plate(),
                    vehicle == null ? null : vehicle.carId(),
                    vehicle == null ? null : vehicle.speedKmh(),
                    order.updatedAt(), order.status()
            );
            dispatched++;
        }
        return dispatched;
    }

    private RenderRouteDTO withRuntimeMetrics(RenderRouteDTO route) {
        RoutePushService.RouteRuntimeMetrics metrics = routePushService.routeRuntimeMetrics(route.lineId());
        if (metrics == null) {
            log.info("[TownRoad] filtered RM2 route without registered runtime: lineId={}", route.lineId());
            return null;
        }
        return new RenderRouteDTO(
                route.lineId(), route.orderId(), coalesce(metrics.plate(), route.plate()), metrics.vehicleId(),
                route.from(), route.to(), route.fromCoords(), route.toCoords(), route.coordinates(),
                metrics.routeLengthKm(), metrics.speedKmh(), route.status(), route.cargo(),
                metrics.travelDurationMs(), route.pathKey(), route.scope(), route.groupId(), route.role(),
                route.coordinateSystem(), route.updatedAt(), route.routeSignature(), route.meta()
        );
    }

    private boolean isPublishableRm2Route(RenderRouteDTO route) {
        if (route == null || !"rm2".equals(route.scope())) return false;
        if (route.speedKmh() == null || route.travelDurationMs() == null || route.routeLengthKm() == null) {
            log.info("[TownRoad] filtered RM2 route without runtime metrics: lineId={}", route == null ? null : route.lineId());
            return false;
        }
        if (!externalOrderProperties.isIgnoreOrdersWithoutRealPosition()) {
            return true;
        }
        boolean publishable = route.vehicleId() != null && !route.vehicleId().isBlank()
                && routePushService.hasFreshProviderPosition(route.lineId());
        if (!publishable) {
            log.info("[TownRoad] filtered RM2 route before publish: lineId={}, plate={}, vehicleId={}, hasProviderVehicleId={}, hasFreshProviderPosition={}",
                    route.lineId(), route.plate(), route.vehicleId(),
                    routePushService.hasProviderVehicleId(route.lineId()),
                    routePushService.hasFreshProviderPosition(route.lineId()));
        }
        return publishable;
    }

    private String coalesce(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /**
     * RM2 groups/routes 与位置运行池使用同一批短途订单和同一个 instanceId。
     * 不能再从 render commands 二次取数，否则两套 lineId 可能产生分叉。
     */
    private int registerShortHaulRoutesForPositions(List<NormalizedTownRoadOrder> shortHaulOrders) {
        if (shortHaulOrders == null || shortHaulOrders.isEmpty()) return 0;
        int registered = 0;
        for (NormalizedTownRoadOrder order : shortHaulOrders) {
            if (order == null || !"运输中".equals(order.status()) || order.from() == null || order.to() == null) continue;
            double[] fromCoords = order.from().coords();
            double[] toCoords = order.to().coords();
            if (fromCoords == null || toCoords == null || fromCoords.length < 2 || toCoords.length < 2) continue;

            ExternalOrderRecord.Vehicle vehicle = order.vehicle();
            routePushService.dispatchTownRoute(
                    order.instanceId(),
                    order.orderId() != null ? order.orderId() : order.instanceId(),
                    order.from().name(), order.to().name(),
                    fromCoords, toCoords, order.routeCoordinates(),
                    vehicle == null ? null : vehicle.currentCoords(),
                    vehicle == null ? null : vehicle.plate(),
                    vehicle == null ? null : vehicle.carId(),
                    vehicle == null ? order.speedKmh() : vehicle.speedKmh(),
                    order.updatedAt(), order.status()
            );
            registered++;
        }
        return registered;
    }

    private boolean shouldIncludeOrderForRealPositionMode(NormalizedTownRoadOrder order) {
        return order != null && shouldIncludeOrderForRealPositionMode(order.instanceId(), order.vehicle());
    }

    private boolean shouldIncludeOrderForRealPositionMode(String lineId, ExternalOrderRecord.Vehicle vehicle) {
        if (!externalOrderProperties.isIgnoreOrdersWithoutRealPosition()) {
            return true;
        }
        boolean eligible = routePushService.prepareProviderPositionVehicle(
                lineId,
                vehicle == null ? null : vehicle.plate(),
                vehicle == null ? null : vehicle.carId()
        );
        if (!eligible) {
            log.info("[TownRoad] ignored order without provider position capability: lineId={}, plate={}",
                    lineId, vehicle == null ? null : vehicle.plate());
        }
        return eligible;
    }

    public TownRoadMiddleLayer middleLayer() {
        return middleLayer;
    }

    private record DeduplicationResult(
            int inputCount,
            List<ExternalOrderRecord> orders,
            int removedCount,
            int duplicateKeyCount,
            List<String> sampleDuplicateKeys
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("inputCount", inputCount);
            map.put("uniqueCount", orders.size());
            map.put("removedCount", removedCount);
            map.put("duplicateKeyCount", duplicateKeyCount);
            map.put("sampleDuplicateKeys", sampleDuplicateKeys);
            return map;
        }
    }
}
