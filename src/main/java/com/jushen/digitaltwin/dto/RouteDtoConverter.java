package com.jushen.digitaltwin.dto;

import com.jushen.digitaltwin.townroad.ExternalOrderRecord;
import com.jushen.digitaltwin.townroad.NormalizedTownRoadOrder;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.TownRoadOrder;
import com.jushen.digitaltwin.townroad.TownRoadRenderCommand.TownRoadRouteGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 将内部模型（NormalizedTownRoadOrder、TownRoadRenderCommand）转换为统一 DTO。
 */
public final class RouteDtoConverter {

    private RouteDtoConverter() {}

    // ---------------------------------------------------------------
    // NormalizedTownRoadOrder → RenderRouteDTO
    // ---------------------------------------------------------------

    public static RenderRouteDTO fromNormalizedOrder(NormalizedTownRoadOrder order) {
        if (order == null) return null;

        ExternalOrderRecord.Location fromLoc = order.from();
        ExternalOrderRecord.Location toLoc = order.to();
        ExternalOrderRecord.Vehicle vehicle = order.vehicle();

        boolean isShortHaul = order.provincePaths() != null && !order.provincePaths().isEmpty()
                && order.provincePaths().get(0).size() <= 3;
        String scope = isShortHaul ? "rm2" : "rm1";

        String cargo = buildCargo(vehicle);
        String fromAdcode = fromLoc != null ? safe(fromLoc.adcode()) : "000000";
        String toAdcode = toLoc != null ? safe(toLoc.adcode()) : "000000";
        String pathKey = RenderRouteDTO.buildStablePathKey(scope, fromAdcode, toAdcode, order.routeCoordinates());

        Long travelDurationMs = null;
        if (order.routeLengthKm() != null && order.routeLengthKm() > 0
                && order.speedKmh() != null && order.speedKmh() > 0) {
            travelDurationMs = Math.round(order.routeLengthKm() / order.speedKmh() * 3_600_000);
        }

        return new RenderRouteDTO(
                order.instanceId(),
                order.orderId(),
                vehicle != null ? vehicle.plate() : null,
                vehicle != null ? vehicle.carId() : null,
                fromLoc != null ? fromLoc.name() : null,
                toLoc != null ? toLoc.name() : null,
                fromLoc != null ? fromLoc.coords() : null,
                toLoc != null ? toLoc.coords() : null,
                order.routeCoordinates(),
                order.routeLengthKm(),
                order.speedKmh(),
                order.status(),
                cargo,
                travelDurationMs,
                pathKey,
                scope,
                order.groupId(),
                "primary",
                "GCJ02",
                order.updatedAt(),
                order.routeSignature(),  // 直接复用中间层已有的 routeSignature
                buildMeta(order)
        );
    }

    // ---------------------------------------------------------------
    // TownRoadRenderCommand → RouteSnapshotDTO
    // ---------------------------------------------------------------

    public static RouteSnapshotDTO fromRenderCommand(TownRoadRenderCommand command) {
        if (command == null) return null;

        List<RenderRouteDTO> routes = new ArrayList<>();
        if (command.orders() != null) {
            for (TownRoadOrder order : command.orders()) {
                routes.add(fromTownRoadOrder(order));
            }
        }

        List<RenderRouteGroupDTO> groups = new ArrayList<>();
        if (command.displayRouteGroups() != null) {
            int index = 0;
            for (TownRoadRouteGroup group : command.displayRouteGroups()) {
                groups.add(fromTownRoadRouteGroup(group, index++));
            }
        }

        return new RouteSnapshotDTO(
                command.commandId(),
                command.title(),
                command.description(),
                command.renderProvinces() != null ? command.renderProvinces() : List.of(),
                routes,
                groups,
                command.issuedAt(),
                Map.of()
        );
    }

    // ---------------------------------------------------------------
    // TownRoadRouteGroup → RenderRouteGroupDTO
    // ---------------------------------------------------------------

    public static RenderRouteGroupDTO fromTownRoadRouteGroup(TownRoadRouteGroup group, int index) {
        if (group == null) return null;

        List<String> orderLineIds = new ArrayList<>();
        if (group.primaryOrderLineIds() != null) orderLineIds.addAll(group.primaryOrderLineIds());
        if (group.alongOrderLineIds() != null) orderLineIds.addAll(group.alongOrderLineIds());

        String scenario = group.fromProvinceKey() != null
                && group.fromProvinceKey().equals(group.toProvinceKey())
                ? "same_province" : "cross_province";

        return new RenderRouteGroupDTO(
                group.groupId(),
                group.groupName(),
                index,
                1,
                orderLineIds.size(),
                orderLineIds,
                scenario,
                group.absorbedReason(),
                group.absorbed() != null && group.absorbed() ? "absorbed" : "primary",
                (group.fromProvinceName() != null ? group.fromProvinceName() : "")
                        + "→" + (group.toProvinceName() != null ? group.toProvinceName() : ""),
                null
        );
    }

    // ---------------------------------------------------------------
    // 批量转换
    // ---------------------------------------------------------------

    public static List<RenderRouteDTO> shortHaulOrdersToRoutes(List<NormalizedTownRoadOrder> orders) {
        if (orders == null) return List.of();
        return orders.stream()
                .map(RouteDtoConverter::fromNormalizedOrder)
                .filter(r -> r != null)
                .toList();
    }

    public static List<RenderRouteDTO> longHaulOrdersToRoutes(List<NormalizedTownRoadOrder> orders) {
        if (orders == null) return List.of();
        return orders.stream()
                .map(RouteDtoConverter::fromNormalizedOrder)
                .filter(r -> r != null && "rm1".equals(r.scope()))
                .toList();
    }

    // ---------------------------------------------------------------
    // 稳定分组构建（用于 RM2 /groups 接口）
    // ---------------------------------------------------------------

    /**
     * 展示分组 = fromProvince + toProvince，每 maxPerGroup 条一页。
     * 道路复用 key = pathKey（保留在每条路线中，前端用它合并 Mesh）。
     */
    public static List<Rm2RouteGroupDTO> buildStableGroups(
            List<RenderRouteDTO> routes,
            int maxPerGroup
    ) {
        if (routes == null || routes.isEmpty()) return List.of();

        // 展示分组只按 OD 省份，不按 pathKey
        Map<String, List<RenderRouteDTO>> buckets = new LinkedHashMap<>();
        for (RenderRouteDTO route : routes) {
            String fromProv = provinceFromMeta(route.meta(), "fromProvinceKey");
            String toProv = provinceFromMeta(route.meta(), "toProvinceKey");
            String key = safe(fromProv) + ":" + safe(toProv);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(route);
        }

        List<Rm2RouteGroupDTO> groups = new ArrayList<>();
        int globalIndex = 0;

        for (Map.Entry<String, List<RenderRouteDTO>> entry : buckets.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            String fromProv = parts.length > 0 ? parts[0] : "000000";
            String toProv = parts.length > 1 ? parts[1] : "000000";

            List<RenderRouteDTO> bucketRoutes = entry.getValue();
            bucketRoutes.sort(Comparator
                    .comparing(RenderRouteDTO::pathKey, Comparator.nullsLast(String::compareTo))
                    .thenComparing(RenderRouteDTO::lineId, Comparator.nullsLast(String::compareTo)));

            int total = bucketRoutes.size();
            int pageCount = (int) Math.ceil((double) total / maxPerGroup);

            for (int page = 0; page < pageCount; page++) {
                int fromIdx = page * maxPerGroup;
                int toIdx = Math.min(fromIdx + maxPerGroup, total);
                List<RenderRouteDTO> pageRoutes = bucketRoutes.subList(fromIdx, toIdx);

                List<String> lineIds = pageRoutes.stream()
                        .map(RenderRouteDTO::lineId)
                        .toList();

                // groupId = OD + page + 内容 hash
                String contentHash = Integer.toHexString(lineIds.hashCode());
                String groupId = "rm2:" + fromProv + ":" + toProv + ":page-" + (page + 1) + ":" + contentHash;

                String groupLabel = provinceLabel(fromProv) + " → " + provinceLabel(toProv);
                String groupName = pageCount > 1
                        ? groupLabel + " (" + (page + 1) + "/" + pageCount + ")"
                        : groupLabel;

                String scenario = fromProv.equals(toProv) ? "same_province" : "cross_province";

                // 组的 pathKey 用多条路线共同前缀（展示用，前端仍用每条路线自己的 pathKey 合并 Mesh）
                String pathKeyHint = pageRoutes.stream()
                        .map(RenderRouteDTO::pathKey)
                        .filter(k -> k != null)
                        .reduce((a, b) -> commonPrefix(a, b))
                        .orElse(null);

                groups.add(new Rm2RouteGroupDTO(
                        groupId, groupName, globalIndex++, lineIds.size(),
                        lineIds, fromProv, scenario, pathKeyHint
                ));
            }
        }

        return groups;
    }

    private static String commonPrefix(String a, String b) {
        if (a == null || b == null) return a != null ? a : b;
        int len = Math.min(a.length(), b.length());
        int i = 0;
        while (i < len && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }

    /**
     * 兼容旧调用：返回 RenderRouteGroupDTO 版本。
     */
    public static List<RenderRouteGroupDTO> buildStableRenderGroups(
            List<RenderRouteDTO> routes,
            int maxPerGroup
    ) {
        List<Rm2RouteGroupDTO> rm2Groups = buildStableGroups(routes, maxPerGroup);
        List<RenderRouteGroupDTO> result = new ArrayList<>();
        for (Rm2RouteGroupDTO g : rm2Groups) {
            result.add(new RenderRouteGroupDTO(
                    g.groupId(), g.groupName(), g.index(), 1, g.count(),
                    g.lineIds(), g.groupScenario(), null, "primary",
                    g.groupName(), g.pathKey()
            ));
        }
        return result;
    }

    // ---------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------

    private static String buildCargo(ExternalOrderRecord.Vehicle vehicle) {
        if (vehicle == null) return null;
        if (vehicle.cargoWeight() == null) return null;
        String unit = vehicle.cargoUnit() != null ? vehicle.cargoUnit() : "吨";
        return vehicle.cargoWeight() + unit;
    }

    private static Map<String, Object> buildMeta(NormalizedTownRoadOrder order) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("groupName", order.groupName());
        meta.put("fromProvinceKey", order.fromProvinceKey());
        meta.put("toProvinceKey", order.toProvinceKey());
        if (order.provincePathKeys() != null) {
            meta.put("provincePathKeys", order.provincePathKeys());
        }
        if (order.cityNames() != null) {
            meta.put("cityNames", order.cityNames());
        }
        return meta;
    }

    private static String provinceFromMeta(Map<String, Object> meta, String key) {
        if (meta == null) return "000000";
        Object value = meta.get(key);
        return value != null ? value.toString() : "000000";
    }

    private static String provinceLabel(String adcode) {
        if (adcode == null || adcode.isBlank() || "000000".equals(adcode)) return "未知";
        // 简单映射，后续可接入 ProvinceCodeResolver
        return adcode;
    }

    private static RenderRouteDTO fromTownRoadOrder(TownRoadOrder order) {
        if (order == null) return null;
        ExternalOrderRecord.Location fromLoc = order.from();
        ExternalOrderRecord.Location toLoc = order.to();
        ExternalOrderRecord.Vehicle vehicle = order.vehicle();

        String fromAdcode = fromLoc != null ? safe(fromLoc.adcode()) : "000000";
        String toAdcode = toLoc != null ? safe(toLoc.adcode()) : "000000";
        String pathKey = RenderRouteDTO.buildStablePathKey("rm2", fromAdcode, toAdcode, order.coordinates());

        return new RenderRouteDTO(
                order.lineId(),
                order.orderId(),
                vehicle != null ? vehicle.plate() : null,
                vehicle != null ? vehicle.carId() : null,
                fromLoc != null ? fromLoc.name() : null,
                toLoc != null ? toLoc.name() : null,
                fromLoc != null ? fromLoc.coords() : null,
                toLoc != null ? toLoc.coords() : null,
                order.coordinates(),
                order.routeLengthKm(),
                order.speedKmh(),
                order.status(),
                buildCargo(vehicle),
                null,
                pathKey,
                "rm2",
                null,
                "primary",
                "GCJ02",
                order.updatedAt(),
                RenderRouteDTO.hashCoordinates(order.coordinates()),
                Map.of()
        );
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
