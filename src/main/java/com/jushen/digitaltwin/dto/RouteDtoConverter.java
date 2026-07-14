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
     * 按 fromProvinceKey + toProvinceKey + pathKey 稳定分组，每组最多 maxPerGroup 条。
     * 使用 lineId 做第二排序保证确定性。
     */
    public static List<RenderRouteGroupDTO> buildStableGroups(
            List<RenderRouteDTO> routes,
            int maxPerGroup
    ) {
        if (routes == null || routes.isEmpty()) return List.of();

        // 按 fromProvince -> toProvince -> pathKey 桶分组
        Map<String, List<RenderRouteDTO>> buckets = new LinkedHashMap<>();
        for (RenderRouteDTO route : routes) {
            String fromProv = provinceFromMeta(route.meta(), "fromProvinceKey");
            String toProv = provinceFromMeta(route.meta(), "toProvinceKey");
            String key = safe(fromProv) + ":" + safe(toProv) + ":" + safe(route.pathKey());
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(route);
        }

        List<RenderRouteGroupDTO> groups = new ArrayList<>();
        int globalIndex = 0;

        for (Map.Entry<String, List<RenderRouteDTO>> entry : buckets.entrySet()) {
            String[] parts = entry.getKey().split(":", 3);
            String fromProv = parts.length > 0 ? parts[0] : "";
            String toProv = parts.length > 1 ? parts[1] : "";

            List<RenderRouteDTO> bucketRoutes = entry.getValue();
            // 稳定排序：pathKey + lineId
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

                RenderRouteDTO first = pageRoutes.get(0);
                String pathHash = first.pathKey() != null && first.pathKey().contains(":")
                        ? first.pathKey().substring(first.pathKey().lastIndexOf(':') + 1)
                        : "0000000000000000";

                String groupId = "rm2:" + fromProv + ":" + toProv + ":" + pathHash + ":page-" + (page + 1);

                String groupLabel = provinceLabel(fromProv) + " → " + provinceLabel(toProv);
                String groupName = pageCount > 1
                        ? groupLabel + " (" + (page + 1) + "/" + pageCount + ")"
                        : groupLabel;

                String scenario = fromProv.equals(toProv) ? "same_province" : "cross_province";

                groups.add(new RenderRouteGroupDTO(
                        groupId,
                        groupName,
                        globalIndex++,
                        page + 1,
                        lineIds.size(),
                        lineIds,
                        scenario,
                        pageCount > 1 ? "分页 " + (page + 1) + "/" + pageCount : null,
                        "primary",
                        groupLabel,
                        null
                ));
            }
        }

        return groups;
    }

    /**
     * 生成 RouteGroupListDTO。
     */
    public static RouteGroupListDTO buildGroupList(
            List<RenderRouteDTO> routes,
            int maxPerGroup,
            String strategy,
            String displayMode
    ) {
        List<RenderRouteGroupDTO> groups = buildStableGroups(routes, maxPerGroup);
        return new RouteGroupListDTO(
                groups,
                routes != null ? routes.size() : 0,
                maxPerGroup,
                strategy,
                displayMode,
                null
        );
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
