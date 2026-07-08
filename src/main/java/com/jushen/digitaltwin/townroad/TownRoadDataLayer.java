package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.townroad.TownRoadModels.Location;
import com.jushen.digitaltwin.townroad.TownRoadModels.LocationNode;
import com.jushen.digitaltwin.townroad.TownRoadModels.NormalizedTownRoadOrder;
import com.jushen.digitaltwin.townroad.TownRoadModels.TownRoadDiff;
import com.jushen.digitaltwin.townroad.TownRoadModels.TownRoadOrder;
import com.jushen.digitaltwin.townroad.TownRoadModels.TownRoadRenderCommand;
import com.jushen.digitaltwin.townroad.TownRoadModels.TownRoadRenderState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TownRoadDataLayer {

    private final ObjectMapper objectMapper;

    private final Map<String, CacheEntry> cacheByLineId = new ConcurrentHashMap<>();
    private final Map<String, NormalizedTownRoadOrder> ordersByLineId = new ConcurrentHashMap<>();

    /**
     * fromKey -> toKey -> lineIds
     */
    private final Map<String, Map<String, LinkedHashSet<String>>> odIndex = new ConcurrentHashMap<>();

    /**
     * provinceAdcode -> lineIds
     * 例如 440000 -> 广东相关订单。
     */
    private final Map<String, LinkedHashSet<String>> provinceIndex = new ConcurrentHashMap<>();

    /**
     * districtKey/adcode -> node
     */
    private final Map<String, LocationNode> locationNodeIndex = new ConcurrentHashMap<>();

    /**
     * 当前 town_road_render 这一轮真正要渲染的 lineId。
     * 注意：这和全局缓存不同。
     */
    private LinkedHashSet<String> activeRenderLineIds = new LinkedHashSet<>();

    private TownRoadRenderState latestState = new TownRoadRenderState(
            null,
            null,
            null,
            List.of(),
            List.of(),
            null
    );

    public TownRoadDataLayer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized TownRoadDiff applyRenderCommand(TownRoadRenderCommand command) {
        List<TownRoadOrder> inputOrders = command == null || command.orders() == null
                ? List.of()
                : command.orders();

        List<NormalizedTownRoadOrder> added = new ArrayList<>();
        List<NormalizedTownRoadOrder> updated = new ArrayList<>();
        List<NormalizedTownRoadOrder> deleted = new ArrayList<>();
        List<NormalizedTownRoadOrder> unchanged = new ArrayList<>();
        List<NormalizedTownRoadOrder> routeChanged = new ArrayList<>();

        LinkedHashSet<String> nextActiveLineIds = new LinkedHashSet<>();

        for (TownRoadOrder rawOrder : inputOrders) {
            if (!isValid(rawOrder)) {
                continue;
            }

            NormalizedTownRoadOrder next = normalize(rawOrder);
            String lineId = next.lineId();

            if (next.deleted() || "已取消".equals(next.status())) {
                CacheEntry old = cacheByLineId.remove(lineId);
                removeIndexes(lineId);

                if (old != null) {
                    deleted.add(old.order());
                } else {
                    deleted.add(next);
                }

                continue;
            }

            nextActiveLineIds.add(lineId);

            CacheEntry old = cacheByLineId.get(lineId);

            if (old == null) {
                CacheEntry entry = new CacheEntry(next);
                cacheByLineId.put(lineId, entry);
                addIndexes(next);
                added.add(next);
                continue;
            }

            boolean dataChanged = !old.dataSignature().equals(next.dataSignature());
            boolean routeHasChanged = !old.routeSignature().equals(next.routeSignature());

            if (!dataChanged && !routeHasChanged) {
                unchanged.add(old.order());
                continue;
            }

            removeIndexes(lineId);
            cacheByLineId.put(lineId, new CacheEntry(next));
            addIndexes(next);

            updated.add(next);

            if (routeHasChanged) {
                routeChanged.add(next);
            }
        }

        List<NormalizedTownRoadOrder> removedFromRender = new ArrayList<>();
        for (String oldActiveLineId : activeRenderLineIds) {
            if (!nextActiveLineIds.contains(oldActiveLineId)) {
                NormalizedTownRoadOrder oldOrder = ordersByLineId.get(oldActiveLineId);
                if (oldOrder != null) {
                    removedFromRender.add(oldOrder);
                }
            }
        }

        activeRenderLineIds = nextActiveLineIds;

        latestState = new TownRoadRenderState(
                command == null ? null : command.commandId(),
                command == null ? null : command.title(),
                command == null ? null : command.description(),
                command == null || command.renderProvinces() == null ? List.of() : command.renderProvinces(),
                currentActiveOrders(),
                command == null ? null : command.issuedAt()
        );

        return new TownRoadDiff(
                added,
                updated,
                deleted,
                unchanged,
                routeChanged,
                removedFromRender
        );
    }

    public synchronized TownRoadRenderState latestState() {
        return latestState;
    }

    public synchronized List<NormalizedTownRoadOrder> allCachedOrders() {
        return new ArrayList<>(ordersByLineId.values());
    }

    public synchronized List<NormalizedTownRoadOrder> currentActiveOrders() {
        List<NormalizedTownRoadOrder> result = new ArrayList<>();

        for (String lineId : activeRenderLineIds) {
            NormalizedTownRoadOrder order = ordersByLineId.get(lineId);
            if (order != null) {
                result.add(order);
            }
        }

        return result;
    }

    public synchronized List<NormalizedTownRoadOrder> findSameOd(String fromKey, String toKey) {
        LinkedHashSet<String> lineIds = odIndex
                .getOrDefault(fromKey, Collections.emptyMap())
                .get(toKey);

        if (lineIds == null || lineIds.isEmpty()) {
            return List.of();
        }

        return lineIds.stream()
                .map(ordersByLineId::get)
                .filter(item -> item != null)
                .toList();
    }

    public synchronized List<NormalizedTownRoadOrder> findByProvince(String provinceKey) {
        LinkedHashSet<String> lineIds = provinceIndex.get(provinceKey);

        if (lineIds == null || lineIds.isEmpty()) {
            return List.of();
        }

        return lineIds.stream()
                .map(ordersByLineId::get)
                .filter(item -> item != null)
                .toList();
    }

    public synchronized List<NormalizedTownRoadOrder> findAlongDistrictRoute(List<String> routeNodeKeys) {
        if (routeNodeKeys == null || routeNodeKeys.size() < 2) {
            return List.of();
        }

        Map<String, Integer> nodeIndex = new LinkedHashMap<>();
        for (int i = 0; i < routeNodeKeys.size(); i++) {
            nodeIndex.put(routeNodeKeys.get(i), i);
        }

        Map<String, NormalizedTownRoadOrder> result = new LinkedHashMap<>();

        for (int i = 0; i < routeNodeKeys.size(); i++) {
            String fromKey = routeNodeKeys.get(i);
            Map<String, LinkedHashSet<String>> toMap = odIndex.get(fromKey);

            if (toMap == null) {
                continue;
            }

            for (Map.Entry<String, LinkedHashSet<String>> entry : toMap.entrySet()) {
                String toKey = entry.getKey();
                Integer toIndex = nodeIndex.get(toKey);

                if (toIndex == null) {
                    continue;
                }

                if (toIndex <= i) {
                    continue;
                }

                for (String lineId : entry.getValue()) {
                    NormalizedTownRoadOrder order = ordersByLineId.get(lineId);
                    if (order != null) {
                        result.put(lineId, order);
                    }
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    public synchronized Map<String, Object> buildBroadcastCommand(
            TownRoadRenderCommand source,
            TownRoadDiff diff
    ) {
        Map<String, Object> command = new LinkedHashMap<>();

        command.put("type", "town_road_render");
        command.put("commandId", source == null ? null : source.commandId());
        command.put("title", source == null ? null : source.title());
        command.put("description", source == null ? null : source.description());
        command.put("renderProvinces", source == null || source.renderProvinces() == null ? List.of() : source.renderProvinces());
        command.put("orders", currentActiveOrders().stream().map(this::toFrontendOrder).toList());
        command.put("diff", diff.summary());
        command.put("issuedAt", source == null ? null : source.issuedAt());

        return command;
    }

    private Map<String, Object> toFrontendOrder(NormalizedTownRoadOrder order) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("orderId", order.orderId());
        result.put("lineId", order.lineId());
        result.put("groupId", order.groupId());
        result.put("groupName", order.groupName());

        result.put("fromKey", order.fromKey());
        result.put("toKey", order.toKey());
        result.put("odKey", order.odKey());

        result.put("from", toFrontendLocation(order.from()));
        result.put("to", toFrontendLocation(order.to()));

        result.put("vehicle", order.vehicle());
        result.put("status", order.status());
        result.put("updatedAt", order.updatedAt());
        result.put("deleted", order.deleted());
        result.put("upToDate", order.upToDate());

        return result;
    }

    private Map<String, Object> toFrontendLocation(LocationNode node) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("key", node.key());
        result.put("name", node.name());
        result.put("province", node.province());
        result.put("city", node.city());
        result.put("district", node.district());
        result.put("adcode", node.adcode());
        result.put("provinceKey", node.provinceKey());
        result.put("coords", node.coords());

        return result;
    }

    private boolean isValid(TownRoadOrder order) {
        if (order == null) {
            return false;
        }

        if (isBlank(order.lineId())) {
            return false;
        }

        if (order.from() == null || order.to() == null) {
            return false;
        }

        String fromKey = locationKey(order.from());
        String toKey = locationKey(order.to());

        return !isBlank(fromKey) && !isBlank(toKey);
    }

    private NormalizedTownRoadOrder normalize(TownRoadOrder order) {
        LocationNode from = normalizeLocation(order.from());
        LocationNode to = normalizeLocation(order.to());

        String fromKey = from.key();
        String toKey = to.key();
        String odKey = fromKey + "->" + toKey;

        String dataSignature = signature(Map.of(
                "orderId", nvl(order.orderId()),
                "lineId", nvl(order.lineId()),
                "groupId", nvl(order.groupId()),
                "groupName", nvl(order.groupName()),
                "fromKey", fromKey,
                "toKey", toKey,
                "vehicle", nvl(order.vehicle()),
                "status", nvl(order.status()),
                "deleted", Boolean.TRUE.equals(order.deleted()),
                "updatedAt", nvl(order.updatedAt())
        ));

        String routeSignature = signature(Map.of(
                "fromKey", fromKey,
                "toKey", toKey,
                "fromCoords", coordsForSignature(from.coords()),
                "toCoords", coordsForSignature(to.coords()),
                "upToDate", Boolean.TRUE.equals(order.upToDate())
        ));

        return new NormalizedTownRoadOrder(
                order.orderId(),
                order.lineId(),
                order.groupId(),
                order.groupName(),
                fromKey,
                toKey,
                odKey,
                from,
                to,
                order.vehicle(),
                emptyToNull(order.status()),
                emptyToNull(order.updatedAt()),
                Boolean.TRUE.equals(order.deleted()),
                Boolean.TRUE.equals(order.upToDate()),
                dataSignature,
                routeSignature
        );
    }

    private LocationNode normalizeLocation(Location location) {
        String key = locationKey(location);
        String provinceKey = provinceKey(location);

        return new LocationNode(
                key,
                locationName(location),
                emptyToNull(location.province()),
                emptyToNull(location.city()),
                emptyToNull(location.district()),
                emptyToNull(location.adcode()),
                provinceKey,
                location.coords()
        );
    }

    private void addIndexes(NormalizedTownRoadOrder order) {
        ordersByLineId.put(order.lineId(), order);

        locationNodeIndex.put(order.fromKey(), order.from());
        locationNodeIndex.put(order.toKey(), order.to());

        odIndex
                .computeIfAbsent(order.fromKey(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(order.toKey(), ignored -> new LinkedHashSet<>())
                .add(order.lineId());

        addProvinceIndex(order.from().provinceKey(), order.lineId());
        addProvinceIndex(order.to().provinceKey(), order.lineId());
    }

    private void removeIndexes(String lineId) {
        NormalizedTownRoadOrder old = ordersByLineId.remove(lineId);

        if (old == null) {
            return;
        }

        Map<String, LinkedHashSet<String>> toMap = odIndex.get(old.fromKey());

        if (toMap != null) {
            LinkedHashSet<String> lineIds = toMap.get(old.toKey());

            if (lineIds != null) {
                lineIds.remove(lineId);

                if (lineIds.isEmpty()) {
                    toMap.remove(old.toKey());
                }
            }

            if (toMap.isEmpty()) {
                odIndex.remove(old.fromKey());
            }
        }

        removeProvinceIndex(old.from().provinceKey(), lineId);
        removeProvinceIndex(old.to().provinceKey(), lineId);
    }

    private void addProvinceIndex(String provinceKey, String lineId) {
        if (isBlank(provinceKey)) {
            return;
        }

        provinceIndex
                .computeIfAbsent(provinceKey, ignored -> new LinkedHashSet<>())
                .add(lineId);
    }

    private void removeProvinceIndex(String provinceKey, String lineId) {
        if (isBlank(provinceKey)) {
            return;
        }

        LinkedHashSet<String> lineIds = provinceIndex.get(provinceKey);

        if (lineIds == null) {
            return;
        }

        lineIds.remove(lineId);

        if (lineIds.isEmpty()) {
            provinceIndex.remove(provinceKey);
        }
    }

    private String locationKey(Location location) {
        if (location == null) {
            return "";
        }

        if (!isBlank(location.adcode())) {
            return location.adcode().trim();
        }

        String province = safe(location.province());
        String city = safe(location.city());
        String district = safe(location.district());
        String name = safe(location.name());

        String tail = !isBlank(district) ? district : name;

        return List.of(province, city, tail)
                .stream()
                .filter(item -> !isBlank(item))
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    private String provinceKey(Location location) {
        if (location == null) {
            return "";
        }

        String adcode = safe(location.adcode());

        if (adcode.length() >= 2) {
            return adcode.substring(0, 2) + "0000";
        }

        return safe(location.province());
    }

    private String locationName(Location location) {
        if (location == null) {
            return "";
        }

        if (!isBlank(location.name())) {
            return location.name().trim();
        }

        return safe(location.province()) + safe(location.city()) + safe(location.district());
    }

    private String signature(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private List<Double> coordsForSignature(double[] coords) {
        if (coords == null || coords.length < 2) {
            return List.of();
        }

        return List.of(coords[0], coords[1]);
    }

    private Object nvl(Object value) {
        return value == null ? "" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String emptyToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CacheEntry(
            NormalizedTownRoadOrder order,
            String dataSignature,
            String routeSignature
    ) {
        private CacheEntry(NormalizedTownRoadOrder order) {
            this(order, order.dataSignature(), order.routeSignature());
        }
    }
}