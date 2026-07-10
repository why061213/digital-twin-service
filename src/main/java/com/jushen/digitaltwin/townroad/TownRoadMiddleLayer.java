package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.townroad.ProvinceRoadGraph.ProvincePath;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.ProvinceEdgeView;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.ProvincePathCandidate;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.ProvinceRef;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.TownRoadOrder;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.TownRoadRouteGroup;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TownRoadMiddleLayer {

    /**
     * 短途阈值：省份路径中最多允许出现 3 个省。
     * 例如广东->福建->浙江是 3 个省，算短途。
     */
    private static final int MAX_SHORT_HAUL_PROVINCE_COUNT = 3;

    /**
     * 两点之间最多保留多少条等长最短路径，避免某些节点组合出现过多等价路径。
     */
    private static final int MAX_CANDIDATE_PATHS_PER_PAIR = 12;

    private final ObjectMapper objectMapper;
    private final ProvinceRoadGraph provinceRoadGraph;
    private final ProvinceCodeResolver provinceCodeResolver;
    private final TownRoadExternalOrderProperties properties;
    private final TownRoadCoordinateResolver coordinateResolver;

    private final Map<String, NormalizedTownRoadOrder> ordersByLineId = new ConcurrentHashMap<>();

    /**
     * 区县/地点 OD MapMap：fromKey -> toKey -> lineIds。
     */
    private final Map<String, Map<String, Set<String>>> odIndex = new ConcurrentHashMap<>();

    /**
     * 省份 OD MapMap：fromProvince -> toProvince -> lineIds。
     */
    private final Map<String, Map<String, Set<String>>> provincePairIndex = new ConcurrentHashMap<>();

    /**
     * 精确省份路径索引：provincePathKey -> lineIds。
     * 如果一个订单有多条等长最短路径，会被放到多个 pathKey 下。
     */
    private final Map<String, Set<String>> provincePathIndex = new ConcurrentHashMap<>();

    /**
     * 起点省份索引：sourceProvinceKey -> lineIds。
     */
    private final Map<String, Set<String>> sourceProvinceIndex = new ConcurrentHashMap<>();

    public TownRoadMiddleLayer(
            ObjectMapper objectMapper,
            ProvinceRoadGraph provinceRoadGraph,
            ProvinceCodeResolver provinceCodeResolver,
            TownRoadExternalOrderProperties properties,
            TownRoadCoordinateResolver coordinateResolver
    ) {
        this.objectMapper = objectMapper;
        this.provinceRoadGraph = provinceRoadGraph;
        this.provinceCodeResolver = provinceCodeResolver;
        this.properties = properties;
        this.coordinateResolver = coordinateResolver;
    }

    public synchronized ExternalOrderSnapshotResult processSnapshot(List<ExternalOrderRecord> rawOrders) {
        List<ExternalOrderRecord> safeRawOrders = rawOrders == null ? List.of() : rawOrders;
        Map<String, NormalizedTownRoadOrder> previous = new LinkedHashMap<>(ordersByLineId);

        List<NormalizedTownRoadOrder> normalized = new ArrayList<>();
        int skippedInvalid = 0;
        int skippedNotRenderable = 0;
        int skippedLongHaul = 0;

        for (ExternalOrderRecord raw : safeRawOrders) {
            if (!isValidBasic(raw)) {
                skippedInvalid++;
                continue;
            }

            NormalizedTownRoadOrder order = normalize(raw);

            if (order.deleted() || "已取消".equals(order.status())) {
                continue;
            }

            normalized.add(order);
        }

        rebuildIndexes(normalized);

        List<NormalizedTownRoadOrder> shortHaulOrders = new ArrayList<>();
        for (NormalizedTownRoadOrder order : normalized) {
            if (properties.isRequireRenderableCoords() && !isRenderable(order)) {
                skippedNotRenderable++;
                continue;
            }

            if (!isShortHaul(order)) {
                skippedLongHaul++;
                continue;
            }

            shortHaulOrders.add(order);
        }

        OrderSnapshotDiff diff = buildDiff(
                previous,
                ordersByLineId,
                skippedInvalid,
                skippedNotRenderable,
                skippedLongHaul
        );

        List<TownRoadRenderCommand> commands = buildTownRoadCommands(shortHaulOrders);

        return new ExternalOrderSnapshotResult(
                safeRawOrders.size(),
                normalized.size(),
                shortHaulOrders.size(),
                commands,
                diff
        );
    }

    public List<NormalizedTownRoadOrder> findSameOd(String fromKey, String toKey) {
        Set<String> lineIds = odIndex
                .getOrDefault(fromKey, Map.of())
                .getOrDefault(toKey, Set.of());

        return lineIds.stream()
                .map(ordersByLineId::get)
                .filter(item -> item != null)
                .sorted(Comparator.comparing(NormalizedTownRoadOrder::lineId))
                .toList();
    }

    public List<NormalizedTownRoadOrder> findByProvincePath(String provincePathKey) {
        Set<String> lineIds = provincePathIndex.getOrDefault(provincePathKey, Set.of());

        return lineIds.stream()
                .map(ordersByLineId::get)
                .filter(item -> item != null)
                .sorted(Comparator.comparing(NormalizedTownRoadOrder::lineId))
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
                result.put(order.lineId(), order);
            }
        }

        return result.values()
                .stream()
                .sorted(Comparator.comparing(NormalizedTownRoadOrder::lineId))
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
        return ordersByLineId.values()
                .stream()
                .sorted(Comparator.comparing(NormalizedTownRoadOrder::lineId))
                .toList();
    }

    private void rebuildIndexes(List<NormalizedTownRoadOrder> normalizedOrders) {
        ordersByLineId.clear();
        odIndex.clear();
        provincePairIndex.clear();
        provincePathIndex.clear();
        sourceProvinceIndex.clear();

        for (NormalizedTownRoadOrder order : normalizedOrders) {
            ordersByLineId.put(order.lineId(), order);

            odIndex
                    .computeIfAbsent(order.fromKey(), ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(order.toKey(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(order.lineId());

            provincePairIndex
                    .computeIfAbsent(order.fromProvinceKey(), ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(order.toProvinceKey(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(order.lineId());

            sourceProvinceIndex
                    .computeIfAbsent(order.fromProvinceKey(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(order.lineId());

            for (String provincePathKey : order.provincePathKeys()) {
                if (provincePathKey.isBlank()) continue;
                provincePathIndex
                        .computeIfAbsent(provincePathKey, ignored -> ConcurrentHashMap.newKeySet())
                        .add(order.lineId());
            }
        }
    }

    private OrderSnapshotDiff buildDiff(
            Map<String, NormalizedTownRoadOrder> previous,
            Map<String, NormalizedTownRoadOrder> current,
            int skippedInvalid,
            int skippedNotRenderable,
            int skippedLongHaul
    ) {
        int added = 0;
        int updated = 0;
        int deleted = 0;
        int unchanged = 0;
        int routeChanged = 0;

        for (Map.Entry<String, NormalizedTownRoadOrder> entry : current.entrySet()) {
            String lineId = entry.getKey();
            NormalizedTownRoadOrder next = entry.getValue();
            NormalizedTownRoadOrder old = previous.get(lineId);

            if (old == null) {
                added++;
                continue;
            }

            boolean dataChanged = !old.dataSignature().equals(next.dataSignature());
            boolean routeHasChanged = !old.routeSignature().equals(next.routeSignature());

            if (!dataChanged && !routeHasChanged) {
                unchanged++;
            } else {
                updated++;
                if (routeHasChanged) {
                    routeChanged++;
                }
            }
        }

        for (String oldLineId : previous.keySet()) {
            if (!current.containsKey(oldLineId)) {
                deleted++;
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
                skippedLongHaul
        );
    }

    private NormalizedTownRoadOrder normalize(ExternalOrderRecord raw) {
        // 补全缺失的经纬度：从地址名称中解析省市区并查本地坐标库
        ExternalOrderRecord.Location resolvedFrom = coordinateResolver.resolveLocation(raw.from());
        ExternalOrderRecord.Location resolvedTo = coordinateResolver.resolveLocation(raw.to());

        String fromKey = locationKey(resolvedFrom);
        String toKey = locationKey(resolvedTo);
        String odKey = fromKey + "->" + toKey;

        String fromProvinceKey = provinceCodeResolver.provinceKey(resolvedFrom);
        String toProvinceKey = provinceCodeResolver.provinceKey(resolvedTo);

        List<ProvincePath> candidatePaths = candidateProvincePaths(fromProvinceKey, toProvinceKey);

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

        String dataSignature = signature(Map.of(
                "orderId", safe(orderId),
                "lineId", safe(raw.lineId()),
                "fromKey", safe(fromKey),
                "toKey", safe(toKey),
                "vehicle", raw.vehicle() == null ? "" : raw.vehicle(),
                "status", safe(raw.status()),
                "updatedAt", safe(raw.updatedAt()),
                "deleted", Boolean.TRUE.equals(raw.deleted())
        ));

        String routeSignature = signature(Map.of(
                "fromKey", safe(fromKey),
                "toKey", safe(toKey),
                "fromCoords", coordsForSignature(resolvedFrom.coords()),
                "toCoords", coordsForSignature(resolvedTo.coords()),
                "provincePathKeys", provincePathKeys,
                "upToDate", Boolean.TRUE.equals(raw.upToDate())
        ));

        return new NormalizedTownRoadOrder(
                orderId,
                raw.lineId(),

                fromKey,
                toKey,
                odKey,

                fromProvinceKey,
                toProvinceKey,

                provincePaths,
                provincePathKeys,
                provincePathCosts,

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
                        .thenComparing(NormalizedTownRoadOrder::lineId))
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
                .sorted(Comparator.comparing(NormalizedTownRoadOrder::lineId))
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
                .map(NormalizedTownRoadOrder::lineId)
                .distinct()
                .sorted()
                .toList();

        primaryOrders.forEach(order -> flatOrderMap.put(order.lineId(), order));

        List<ProvincePath> candidateProvincePaths = candidateProvincePaths(sourceProvinceKey, targetProvinceKey);

        List<ProvincePathCandidate> candidates = new ArrayList<>();
        LinkedHashSet<String> routeGroupAlongLineIds = new LinkedHashSet<>();
        Integer bestPathCost = candidateProvincePaths.isEmpty() ? null : candidateProvincePaths.get(0).cost();

        for (ProvincePath candidateProvincePath : candidateProvincePaths) {
            List<String> provincePath = candidateProvincePath.provinces();

            provincePath.forEach(renderProvinceSet::add);

            String pathId = candidateProvincePath.pathKey();
            List<String> edgeKeys = candidateProvincePath.edgeKeys();

            LinkedHashSet<String> candidateAlongLineIds = new LinkedHashSet<>();

            for (NormalizedTownRoadOrder candidateOrder : allShortHaulOrders) {
                if (primaryLineIds.contains(candidateOrder.lineId())) {
                    continue;
                }

                if (orderHasAnyPathAsContinuousSubPath(candidateOrder, provincePath)) {
                    candidateAlongLineIds.add(candidateOrder.lineId());
                    routeGroupAlongLineIds.add(candidateOrder.lineId());
                    flatOrderMap.put(candidateOrder.lineId(), candidateOrder);
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
                    NormalizedTownRoadOrder alongOrder = ordersByLineId.get(alongLineId);
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
                    sortedCandidateAlongLineIds
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

    private boolean isShortHaul(NormalizedTownRoadOrder order) {
        return order.provincePaths() != null && !order.provincePaths().isEmpty();
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
                order.lineId(),
                order.groupId(),
                order.groupName(),
                order.from(),
                order.to(),
                order.vehicle(),
                order.status(),
                order.updatedAt(),
                order.deleted()
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
