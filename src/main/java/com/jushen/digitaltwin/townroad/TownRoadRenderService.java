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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TownRoadRenderService {

    private static final Logger log = LoggerFactory.getLogger(TownRoadRenderService.class);

    private final TownRoadExternalOrderClient townRoadExternalOrderClient;
    private final TownRoadMiddleLayer middleLayer;
    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final AmapGeocodeClient amapGeocodeClient;
    private final TownRoadCoordinateResolver coordinateResolver;
    private final RoutePushService routePushService;
    private Map<String, Object> lastResult = Map.of();
    /** RM2 原子快照 */
    private volatile Rm2Snapshot latestRm2Snapshot = new Rm2Snapshot("0", Instant.now(), List.of(), List.of(), Map.of(), Map.of());
    /** 上一版 groupId 集合，用于计算 changed/removed */
    private volatile Set<String> previousRm2GroupIds = Set.of();

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
            RoutePushService routePushService
    ) {
        this.townRoadExternalOrderClient = townRoadExternalOrderClient;
        this.middleLayer = middleLayer;
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
        this.amapGeocodeClient = amapGeocodeClient;
        this.coordinateResolver = coordinateResolver;
        this.routePushService = routePushService;
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

        long middleLayerStartedAt = System.currentTimeMillis();
        ExternalOrderSnapshotResult result = middleLayer.processSnapshot(rawOrders);
        long middleLayerFinishedAt = System.currentTimeMillis();
        if (traceEnabled) {
            pipeline.put("middleLayer", middleLayerSummary(result, middleLayerFinishedAt - middleLayerStartedAt));
        }

        // 构造 RM2 快照：用稳定分组的 orderLineIds 建立索引，替换路线 groupId
        List<RenderRouteDTO> rm2Routes = RouteDtoConverter.shortHaulOrdersToRoutes(result.shortHaulOrders());
        List<Rm2RouteGroupDTO> rm2Groups = RouteDtoConverter.buildStableGroups(rm2Routes, 12);

        Map<String, RenderRouteDTO> routeByLineId = new LinkedHashMap<>();
        for (RenderRouteDTO r : rm2Routes) routeByLineId.put(r.lineId(), r);

        Map<String, List<RenderRouteDTO>> routesByGroupId = new LinkedHashMap<>();
        Map<String, String> groupIdByLineId = new LinkedHashMap<>();
        for (Rm2RouteGroupDTO group : rm2Groups) {
            List<RenderRouteDTO> groupRoutes = new ArrayList<>();
            for (String lineId : group.orderLineIds()) {
                RenderRouteDTO route = routeByLineId.get(lineId);
                if (route == null) continue;
                // 替换为正式展示 groupId
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

        String version = "snapshot-" + System.currentTimeMillis();
        this.latestRm2Snapshot = new Rm2Snapshot(version, Instant.now(),
                List.copyOf(rm2Routes), List.copyOf(rm2Groups),
                immutableRoutesByGroupId, immutableGroupIdByLineId);

        // 检测是否有实质变化：订单 diff 非空 或 groupId 集合变化
        boolean hasOrderChanges = result.diff().added() > 0 || result.diff().updated() > 0
                || result.diff().deleted() > 0 || result.diff().routeChanged() > 0;
        long broadcastStartedAt = System.currentTimeMillis();
        Set<String> currentGroupIds = new LinkedHashSet<>();
        for (Rm2RouteGroupDTO g : rm2Groups) currentGroupIds.add(g.groupId());
        Set<String> changedGroupIds = new LinkedHashSet<>(currentGroupIds);
        changedGroupIds.removeAll(previousRm2GroupIds);
        Set<String> removedGroupIds = new LinkedHashSet<>(previousRm2GroupIds);
        removedGroupIds.removeAll(currentGroupIds);
        this.previousRm2GroupIds = currentGroupIds;

        if (hasOrderChanges || !changedGroupIds.isEmpty() || !removedGroupIds.isEmpty()) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "route_snapshot_changed");
            event.put("scope", "rm2");
            event.put("snapshotVersion", version);
            event.put("changedGroupIds", new ArrayList<>(changedGroupIds));
            event.put("removedGroupIds", new ArrayList<>(removedGroupIds));
            event.put("serverTime", Instant.now().toString());
            realtimeWebSocketHandler.broadcast(event);
        }
        long broadcastFinishedAt = System.currentTimeMillis();

        int deletedOrCancelled = result.diff().deletedOrCancelled();
        int rawCount = result.rawCount();
        int normalizedCount = result.normalizedCount();
        int shortHaulCount = result.shortHaulCount();
        int longHaulCount = result.longHaulCount();
        int skippedInvalid = result.diff().skippedInvalid();
        int skippedNotRenderable = result.diff().skippedNotRenderable();
        int skippedLongHaul = result.diff().skippedLongHaul();
        long roadMapStartedAt = System.currentTimeMillis();
        int roadMapRouteCount = dispatchLongHaulRoutesToRoadMap(result.longHaulOrders());
        long roadMapFinishedAt = System.currentTimeMillis();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("type","town_road_render");
        response.put("message", "town_road_render commands broadcasted");
        response.put("rawCount", rawCount);
        response.put("normalizedCount", normalizedCount);
        response.put("shortHaulCount", shortHaulCount);
        response.put("longHaulCount", longHaulCount);
        response.put("roadMapRouteCount", roadMapRouteCount);
        response.put("commandCount", result.commands().size());
        response.put("displayMode", result.commands().size() > 1 ? "multi_source_rotation" : "single_source");
        response.put("diff", result.diff().toMap());
        response.put("snapshotVersion", version);
        response.put("rm2Routes", rm2Routes.size());
        response.put("rm2Groups", rm2Groups.size());

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

        long townRouteStartedAt = System.currentTimeMillis();
        int townRouteDispatchCount = 0;
        for (TownRoadRenderCommand command : result.commands()) {
            if (command.orders() == null) continue;
            for (TownRoadRenderCommand.TownRoadOrder order : command.orders()) {
                if (!"运输中".equals(order.status())) continue;
                if (order.from() == null || order.to() == null) continue;
                double[] fc = order.from().coords();
                double[] tc = order.to().coords();
                if (fc == null || tc == null || fc.length < 2 || tc.length < 2) continue;
                routePushService.dispatchTownRoute(
                        order.lineId(), order.orderId() != null ? order.orderId() : order.lineId(),
                        order.from().name(), order.to().name(),
                        fc, tc, order.coordinates(),
                        order.vehicle() == null ? null : order.vehicle().currentCoords(),
                        order.vehicle() == null ? null : order.vehicle().plate(),
                        order.vehicle() == null ? null : order.vehicle().carId(),
                        order.vehicle() == null ? order.speedKmh() : order.vehicle().speedKmh(),
                        order.updatedAt(), order.status()
                );
                townRouteDispatchCount++;
            }
        }
        long townRouteFinishedAt = System.currentTimeMillis();

        if (traceEnabled) {
            pipeline.put("output", outputSummary(result, roadMapRouteCount, townRouteDispatchCount));
            Map<String, Object> timings = new LinkedHashMap<>();
            timings.put("inputRawCount", inputRawCount);
            timings.put("middleLayerMs", middleLayerFinishedAt - middleLayerStartedAt);
            timings.put("broadcastCommandsMs", broadcastFinishedAt - broadcastStartedAt);
            timings.put("roadMapDispatchMs", roadMapFinishedAt - roadMapStartedAt);
            timings.put("townRouteDispatchMs", townRouteFinishedAt - townRouteStartedAt);
            timings.put("totalMs", System.currentTimeMillis() - startedAt);
            pipeline.put("timings", timings);
            response.put("pipeline", pipeline);
        }

        this.lastResult = response;
        return response;
    }

    // ... (helper methods unchanged)
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

    public TownRoadMiddleLayer middleLayer() {
        return middleLayer;
    }
}
