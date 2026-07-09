package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TownRoadMiddleLayer {

    private final ObjectMapper objectMapper;
    private final ProvinceRoadGraph provinceRoadGraph;
    private final ProvinceCodeResolver provinceCodeResolver;
    private final TownRoadExternalOrderProperties properties;

    private final Map<String, NormalizedTownRoadOrder> ordersByLineId = new ConcurrentHashMap<>();

    /**
     * 你说的 MapMap：
     * fromKey -> toKey -> lineIds
     */
    private final Map<String, Map<String, Set<String>>> odIndex = new ConcurrentHashMap<>();

    /**
     * provincePathKey -> lineIds
     * 例如：440000>350000 -> [line-1, line-2]
     */
    private final Map<String, Set<String>> provincePathIndex = new ConcurrentHashMap<>();

    public TownRoadMiddleLayer(
            ObjectMapper objectMapper,
            ProvinceRoadGraph provinceRoadGraph,
            ProvinceCodeResolver provinceCodeResolver,
            TownRoadExternalOrderProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.provinceRoadGraph = provinceRoadGraph;
        this.provinceCodeResolver = provinceCodeResolver;
        this.properties = properties;
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

        OrderSnapshotDiff diff = buildDiff(previous, ordersByLineId, skippedInvalid, skippedNotRenderable, skippedLongHaul);

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
                .toList();
    }

    public List<NormalizedTownRoadOrder> findByProvincePath(String provincePathKey) {
        Set<String> lineIds = provincePathIndex.getOrDefault(provincePathKey, Set.of());

        return lineIds.stream()
                .map(ordersByLineId::get)
                .filter(item -> item != null)
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
        provincePathIndex.clear();

        for (NormalizedTownRoadOrder order : normalizedOrders) {
            ordersByLineId.put(order.lineId(), order);

            odIndex
                    .computeIfAbsent(order.fromKey(), ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(order.toKey(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(order.lineId());

            if (!order.provincePathKey().isBlank()) {
                provincePathIndex
                        .computeIfAbsent(order.provincePathKey(), ignored -> ConcurrentHashMap.newKeySet())
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
        String fromKey = locationKey(raw.from());
        String toKey = locationKey(raw.to());
        String odKey = fromKey + "->" + toKey;

        String fromProvinceKey = provinceCodeResolver.provinceKey(raw.from());
        String toProvinceKey = provinceCodeResolver.provinceKey(raw.to());

        List<String> provincePath = provinceRoadGraph.shortestPath(fromProvinceKey, toProvinceKey);
        String provincePathKey = String.join(">", provincePath);

        String groupId = provincePathKey.isBlank()
                ? "town-short-haul-unknown"
                : "town-short-haul-" + provincePathKey.replace(">", "-");

        String groupName = buildGroupName(provincePath);

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
                "fromCoords", coordsForSignature(raw.from() == null ? null : raw.from().coords()),
                "toCoords", coordsForSignature(raw.to() == null ? null : raw.to().coords()),
                "provincePathKey", provincePathKey,
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

                provincePath,
                provincePathKey,

                groupId,
                groupName,

                raw.from(),
                raw.to(),
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
        Map<String, List<NormalizedTownRoadOrder>> grouped = new LinkedHashMap<>();

        for (NormalizedTownRoadOrder order : shortHaulOrders) {
            grouped
                    .computeIfAbsent(order.provincePathKey(), ignored -> new ArrayList<>())
                    .add(order);
        }

        List<TownRoadRenderCommand> commands = new ArrayList<>();
        String issuedAt = Instant.now().toString();

        for (Map.Entry<String, List<NormalizedTownRoadOrder>> entry : grouped.entrySet()) {
            List<NormalizedTownRoadOrder> orders = entry.getValue();
            if (orders.isEmpty()) continue;

            List<String> renderProvinces = orders.get(0).provincePath();
            String pathKey = orders.get(0).provincePathKey();

            commands.add(new TownRoadRenderCommand(
                    "town_road_render",
                    "town-short-haul-" + pathKey.replace(">", "-") + "-" + System.currentTimeMillis(),
                    buildTitle(renderProvinces),
                    "后端根据外部订单、OD MapMap 和省份路网筛选出的短途区县运输展示",
                    renderProvinces,
                    orders.stream().map(this::toTownRoadOrder).toList(),
                    issuedAt
            ));
        }

        return commands;
    }

    private TownRoadRenderCommand.TownRoadOrder toTownRoadOrder(NormalizedTownRoadOrder order) {
        return new TownRoadRenderCommand.TownRoadOrder(
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

    private boolean isShortHaul(NormalizedTownRoadOrder order) {
        if (order.provincePath() == null || order.provincePath().isEmpty()) {
            return false;
        }

        return order.provincePath().size() <= 3;
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

        return !fromProvinceKey.isBlank() && !toProvinceKey.isBlank();
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

    private String buildTitle(List<String> renderProvinces) {
        if (renderProvinces == null || renderProvinces.isEmpty()) {
            return "短途区县展示";
        }

        List<String> names = renderProvinces.stream()
                .map(provinceCodeResolver::shortName)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        return String.join(" / ", names) + " 短途区县展示";
    }

    private String buildGroupName(List<String> provincePath) {
        if (provincePath == null || provincePath.isEmpty()) {
            return "短途运输";
        }

        List<String> names = provincePath.stream()
                .map(provinceCodeResolver::shortName)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        return String.join(" / ", names) + " 短途运输";
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
}