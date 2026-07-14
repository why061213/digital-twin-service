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
 * 这是解耦的关键层：不再把内部数据结构直接暴露给前端。
 */
public final class RouteDtoConverter {

    private RouteDtoConverter() {}

    // ---------------------------------------------------------------
    // NormalizedTownRoadOrder → RenderRouteDTO
    // ---------------------------------------------------------------

    /**
     * 将内部标准化订单转为渲染 DTO。
     * scope: 通过 provincePaths 长度判断（≤3 = town，>3 = road）。
     */
    public static RenderRouteDTO fromNormalizedOrder(NormalizedTownRoadOrder order) {
        if (order == null) return null;

        ExternalOrderRecord.Location from = order.from();
        ExternalOrderRecord.Location to = order.to();
        ExternalOrderRecord.Vehicle vehicle = order.vehicle();

        boolean isShortHaul = isShortHaul(order);
        String scope = isShortHaul ? "town" : "road";

        String cargo = buildCargo(vehicle);
        String pathKey = buildPathKey(from, to, order.routeCoordinates());

        // 计算预计行程时长
        Long travelDurationMs = null;
        if (order.routeLengthKm() != null && order.routeLengthKm() > 0
                && order.speedKmh() != null && order.speedKmh() > 0) {
            travelDurationMs = Math.round(order.routeLengthKm() / order.speedKmh() * 3_600_000);
        }

        return new RenderRouteDTO(
                order.instanceId(),           // lineId
                order.orderId(),
                vehicle != null ? vehicle.plate() : null,
                vehicle != null ? vehicle.carId() : null,
                from != null ? from.name() : null,
                to != null ? to.name() : null,
                from != null ? from.coords() : null,
                to != null ? to.coords() : null,
                order.routeCoordinates(),
                order.routeLengthKm(),
                order.speedKmh(),
                order.status(),
                cargo,
                travelDurationMs,
                pathKey,
                scope,
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
    // 列表转换
    // ---------------------------------------------------------------

    /**
     * 批量转换短途订单列表。
     */
    public static List<RenderRouteDTO> shortHaulOrdersToRoutes(List<NormalizedTownRoadOrder> orders) {
        if (orders == null) return List.of();
        return orders.stream()
                .map(RouteDtoConverter::fromNormalizedOrder)
                .filter(r -> r != null)
                .toList();
    }

    /**
     * 批量转换长途订单列表。
     */
    public static List<RenderRouteDTO> longHaulOrdersToRoutes(List<NormalizedTownRoadOrder> orders) {
        if (orders == null) return List.of();
        return orders.stream()
                .map(RouteDtoConverter::fromNormalizedOrder)
                .filter(r -> r != null)
                .toList();
    }

    // ---------------------------------------------------------------
    // 分组构建
    // ---------------------------------------------------------------

    /**
     * 按起始省份→目的省份稳定分组，每组最多 maxPerGroup 条。
     */
    public static List<RenderRouteGroupDTO> buildStableGroups(
            List<RenderRouteDTO> routes,
            int maxPerGroup
    ) {
        if (routes == null || routes.isEmpty()) return List.of();

        // 按 scope + fromName + toName 分组
        Map<String, List<RenderRouteDTO>> buckets = new LinkedHashMap<>();
        for (RenderRouteDTO route : routes) {
            String key = route.scope() + ":" + safe(route.fromName()) + ":" + safe(route.toName());
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(route);
        }

        List<RenderRouteGroupDTO> groups = new ArrayList<>();
        int globalIndex = 0;

        for (Map.Entry<String, List<RenderRouteDTO>> entry : buckets.entrySet()) {
            List<RenderRouteDTO> bucketRoutes = entry.getValue();
            // 按 pathKey 排序以保持稳定
            bucketRoutes.sort(Comparator.comparing(r -> safe(r.pathKey())));

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
                String groupId = first.scope() + ":"
                        + safe(first.fromName()) + ":"
                        + safe(first.toName()) + ":page-" + (page + 1);

                groups.add(new RenderRouteGroupDTO(
                        groupId,
                        safe(first.fromName()) + " → " + safe(first.toName())
                                + (pageCount > 1 ? " (" + (page + 1) + "/" + pageCount + ")" : ""),
                        globalIndex++,
                        page + 1,
                        lineIds.size(),
                        lineIds,
                        "cross_province",
                        pageCount > 1 ? "分页 " + (page + 1) + "/" + pageCount : null,
                        "primary",
                        safe(first.fromName()) + "→" + safe(first.toName()),
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
            String strategy
    ) {
        List<RenderRouteGroupDTO> groups = buildStableGroups(routes, maxPerGroup);
        return new RouteGroupListDTO(
                groups,
                routes != null ? routes.size() : 0,
                maxPerGroup,
                strategy,
                null,
                null
        );
    }

    // ---------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------

    private static boolean isShortHaul(NormalizedTownRoadOrder order) {
        if (order.provincePaths() == null || order.provincePaths().isEmpty()) return false;
        List<String> shortestPath = order.provincePaths().get(0);
        return shortestPath.size() <= 3;
    }

    private static String buildCargo(ExternalOrderRecord.Vehicle vehicle) {
        if (vehicle == null) return null;
        if (vehicle.cargoWeight() == null) return null;
        String unit = vehicle.cargoUnit() != null ? vehicle.cargoUnit() : "吨";
        return vehicle.cargoWeight() + unit;
    }

    private static String buildPathKey(
            ExternalOrderRecord.Location from,
            ExternalOrderRecord.Location to,
            List<double[]> coordinates
    ) {
        if (from == null || to == null) return "";
        String key = safe(from.name()) + "->" + safe(to.name());
        if (coordinates != null && !coordinates.isEmpty()) {
            key += "-" + Integer.toHexString(coordinates.hashCode());
        }
        return key;
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

    private static RenderRouteDTO fromTownRoadOrder(TownRoadOrder order) {
        if (order == null) return null;
        ExternalOrderRecord.Location from = order.from();
        ExternalOrderRecord.Location to = order.to();
        ExternalOrderRecord.Vehicle vehicle = order.vehicle();

        return RenderRouteDTO.builder()
                .lineId(order.lineId())
                .orderId(order.orderId())
                .plate(vehicle != null ? vehicle.plate() : null)
                .carId(vehicle != null ? vehicle.carId() : null)
                .fromName(from != null ? from.name() : null)
                .toName(to != null ? to.name() : null)
                .fromCoords(from != null ? from.coords() : null)
                .toCoords(to != null ? to.coords() : null)
                .coordinates(order.coordinates())
                .routeLengthKm(order.routeLengthKm())
                .speedKmh(order.speedKmh())
                .status(order.status())
                .cargo(buildCargo(vehicle))
                .pathKey(order.lineId())
                .scope("town")
                .build();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
