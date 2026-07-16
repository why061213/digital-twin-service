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

    private static final double MAX_ROUTE_SPEED_KMH = 140.0;
    private static final double DEFAULT_SIMULATION_SPEED_KMH = 80.0;

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

        Double speedKmh = normalizedRouteSpeed(order.speedKmh());
        Long travelDurationMs = travelDurationMs(order.routeLengthKm(), speedKmh);

        return new RenderRouteDTO(
                order.instanceId(),
                order.orderId(),
                businessLineId(order.instanceId(), order.orderId(), order.lineId()),
                vehicle != null ? vehicle.plate() : null,
                vehicle != null ? vehicle.carId() : null,
                fromLoc != null ? fromLoc.name() : null,
                toLoc != null ? toLoc.name() : null,
                fromLoc != null ? fromLoc.coords() : null,
                toLoc != null ? toLoc.coords() : null,
                order.routeCoordinates(),
                order.routeLengthKm(),
                speedKmh,
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

        // 展示分组只按 OD 省份，TreeMap 保证 key 排序稳定
        Map<String, List<RenderRouteDTO>> buckets = new java.util.TreeMap<>();
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

            Map<String, List<RenderRouteDTO>> routesByBusinessLine = new java.util.TreeMap<>();
            for (RenderRouteDTO route : entry.getValue()) {
                routesByBusinessLine
                        .computeIfAbsent(businessLineId(route), ignored -> new ArrayList<>())
                        .add(route);
            }
            routesByBusinessLine.values().forEach(lineRoutes -> lineRoutes.sort(Comparator
                    .comparing(RenderRouteDTO::pathKey, Comparator.nullsLast(String::compareTo))
                    .thenComparing(RenderRouteDTO::lineId, Comparator.nullsLast(String::compareTo))));

            List<Map.Entry<String, List<RenderRouteDTO>>> businessLines =
                    new ArrayList<>(routesByBusinessLine.entrySet());
            int total = businessLines.size();
            int pageCount = (int) Math.ceil((double) total / maxPerGroup);

            for (int page = 0; page < pageCount; page++) {
                int fromIdx = page * maxPerGroup;
                int toIdx = Math.min(fromIdx + maxPerGroup, total);
                List<Map.Entry<String, List<RenderRouteDTO>>> pageBusinessLines =
                        businessLines.subList(fromIdx, toIdx);
                List<String> orderLineIds = pageBusinessLines.stream()
                        .map(Map.Entry::getKey)
                        .toList();
                Map<String, List<String>> vehicleLineIdsByOrderLineId = new LinkedHashMap<>();
                List<RenderRouteDTO> pageRoutes = new ArrayList<>();
                for (Map.Entry<String, List<RenderRouteDTO>> businessLine : pageBusinessLines) {
                    List<String> vehicleLineIds = businessLine.getValue().stream()
                            .map(RenderRouteDTO::lineId)
                            .toList();
                    vehicleLineIdsByOrderLineId.put(businessLine.getKey(), vehicleLineIds);
                    pageRoutes.addAll(businessLine.getValue());
                }
                List<String> vehicleLineIds = pageRoutes.stream().map(RenderRouteDTO::lineId).toList();

                // 叶节点身份只由 OD + page 决定；内容变化由 snapshotVersion 表达。
                String groupId = "rm2:" + fromProv + ":" + toProv + ":page-" + (page + 1);

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
                        groupId, groupName, globalIndex++, orderLineIds.size(),
                        orderLineIds, vehicleLineIds, Map.copyOf(vehicleLineIdsByOrderLineId),
                        vehicleLineIds.size(), fromProv, fromProv, toProv,
                        fromProv + ":" + toProv, page,
                        scenario, pathKeyHint
                ));
            }
        }

        return groups;
    }

    private static String businessLineId(RenderRouteDTO route) {
        if (route.businessLineId() != null && !route.businessLineId().isBlank()) {
            return route.businessLineId();
        }
        return businessLineId(route.lineId(), route.orderId(), route.lineId());
    }

    private static String businessLineId(String instanceId, String orderId, String sourceLineId) {
        if (instanceId != null) {
            int vehicleSeparator = instanceId.lastIndexOf("::");
            if (vehicleSeparator > 0) return instanceId.substring(0, vehicleSeparator);
        }
        return firstNonBlank(orderId, "unknown-order")
                + "::" + firstNonBlank(sourceLineId, instanceId, "unknown-line");
    }

    /** Builds the province -> direction -> leaf-group rings used by RM2 playback. */
    public static Rm2ChainStructureDTO buildRm2ChainStructure(List<Rm2RouteGroupDTO> groups) {
        if (groups == null || groups.isEmpty()) return Rm2ChainStructureDTO.empty();

        Map<String, Map<String, List<Rm2RouteGroupDTO>>> provinces = new java.util.TreeMap<>();
        for (Rm2RouteGroupDTO group : groups) {
            provinces
                    .computeIfAbsent(group.fromProvinceKey(), ignored -> new java.util.TreeMap<>())
                    .computeIfAbsent(group.toProvinceKey(), ignored -> new ArrayList<>())
                    .add(group);
        }

        List<String> provinceIds = provinces.keySet().stream()
                .map(key -> "rm2:province:" + key)
                .toList();
        List<Rm2ChainNodeDTO> nodes = new ArrayList<>();
        List<String> leafGroupIds = new ArrayList<>();
        int provinceIndex = 0;

        for (Map.Entry<String, Map<String, List<Rm2RouteGroupDTO>>> provinceEntry : provinces.entrySet()) {
            String fromProvince = provinceEntry.getKey();
            String provinceId = "rm2:province:" + fromProvince;
            List<String> directionIds = provinceEntry.getValue().keySet().stream()
                    .map(toProvince -> "rm2:direction:" + fromProvince + ":" + toProvince)
                    .toList();
            nodes.add(new Rm2ChainNodeDTO(
                    provinceId, "province", "rm2:root", fromProvince,
                    provinceLabel(fromProvince), provinceIndex,
                    provinceIds.get((provinceIndex + 1) % provinceIds.size()),
                    directionIds, null
            ));
            provinceIndex++;

            int directionIndex = 0;
            for (Map.Entry<String, List<Rm2RouteGroupDTO>> directionEntry : provinceEntry.getValue().entrySet()) {
                String toProvince = directionEntry.getKey();
                String directionId = "rm2:direction:" + fromProvince + ":" + toProvince;
                List<Rm2RouteGroupDTO> directionGroups = directionEntry.getValue().stream()
                        .sorted(Comparator.comparingInt(Rm2RouteGroupDTO::pageIndex)
                                .thenComparing(Rm2RouteGroupDTO::groupId))
                        .toList();
                List<String> groupIds = directionGroups.stream().map(Rm2RouteGroupDTO::groupId).toList();
                nodes.add(new Rm2ChainNodeDTO(
                        directionId, "direction", provinceId,
                        fromProvince + ":" + toProvince,
                        provinceLabel(fromProvince) + " → " + provinceLabel(toProvince),
                        directionIndex,
                        directionIds.get((directionIndex + 1) % directionIds.size()),
                        groupIds, null
                ));
                directionIndex++;

                for (int groupIndex = 0; groupIndex < directionGroups.size(); groupIndex++) {
                    Rm2RouteGroupDTO group = directionGroups.get(groupIndex);
                    nodes.add(new Rm2ChainNodeDTO(
                            group.groupId(), "group", directionId, group.groupId(),
                            group.groupName(), groupIndex,
                            groupIds.get((groupIndex + 1) % groupIds.size()),
                            List.of(), group.groupId()
                    ));
                    leafGroupIds.add(group.groupId());
                }
            }
        }

        return new Rm2ChainStructureDTO(provinceIds.get(0), List.copyOf(nodes), List.copyOf(leafGroupIds));
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
                    g.orderLineIds(), g.groupScenario(), null, "primary",
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
        Double speedKmh = normalizedRouteSpeed(order.speedKmh());

        return new RenderRouteDTO(
                order.lineId(),
                order.orderId(),
                businessLineId(order.lineId(), order.orderId(), order.lineId()),
                vehicle != null ? vehicle.plate() : null,
                vehicle != null ? vehicle.carId() : null,
                fromLoc != null ? fromLoc.name() : null,
                toLoc != null ? toLoc.name() : null,
                fromLoc != null ? fromLoc.coords() : null,
                toLoc != null ? toLoc.coords() : null,
                order.coordinates(),
                order.routeLengthKm(),
                speedKmh,
                order.status(),
                buildCargo(vehicle),
                travelDurationMs(order.routeLengthKm(), speedKmh),
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static Double normalizedRouteSpeed(Double speedKmh) {
        if (speedKmh == null || !Double.isFinite(speedKmh) || speedKmh < 0) {
            // 与 RoutePushService 的运行模型一致：缺少外部速度时仍要给前端
            // 一个可预测的默认模拟速度和对应时长，不能把 null 传进运动层。
            return DEFAULT_SIMULATION_SPEED_KMH;
        }
        return speedKmh <= MAX_ROUTE_SPEED_KMH ? speedKmh : DEFAULT_SIMULATION_SPEED_KMH;
    }

    private static Long travelDurationMs(Double routeLengthKm, Double speedKmh) {
        if (routeLengthKm == null || routeLengthKm <= 0 || speedKmh == null) {
            return null;
        }
        return Math.max(60_000L,
                Math.round(routeLengthKm / Math.max(1, speedKmh) * 3_600_000));
    }
}
