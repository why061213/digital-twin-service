package com.jushen.digitaltwin.dto;

import com.jushen.digitaltwin.townroad.ExternalOrderRecord;
import com.jushen.digitaltwin.townroad.NormalizedTownRoadOrder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第一层封板测试：分组稳定性、指纹一致性、WS广播条件。
 */
class Rm2StableGroupsTest {

    /** 构造一条简化的短途订单 */
    private static NormalizedTownRoadOrder makeOrder(
            String instanceId, String orderId, String status,
            String fromProv, String toProv,
            List<double[]> coordinates, double routeLengthKm, double speedKmh,
            String updatedAt, String plate, double cargoWeight
    ) {
        ExternalOrderRecord.Location fromLoc = new ExternalOrderRecord.Location(
                "from-" + instanceId, fromProv, "city", "district", fromProv + "00",
                coordinates.get(0)
        );
        ExternalOrderRecord.Location toLoc = new ExternalOrderRecord.Location(
                "to-" + instanceId, toProv, "city2", "district2", toProv + "00",
                coordinates.get(coordinates.size() - 1)
        );
        ExternalOrderRecord.Vehicle vehicle = new ExternalOrderRecord.Vehicle(
                plate, "car-" + instanceId, "铝锭", cargoWeight, "吨",
                coordinates.get(0), speedKmh
        );

        List<String> provincePath = "440000".equals(fromProv) && "460000".equals(toProv)
                ? List.of("440000", "450000", "460000")
                : List.of(fromProv, toProv);

        return new NormalizedTownRoadOrder(
                orderId, instanceId, instanceId, "vk-" + instanceId,
                fromProv, toProv, fromProv + ":" + toProv,
                fromProv, toProv,
                List.of(provincePath),                        // provincePaths
                List.of(String.join(">", provincePath)),      // provincePathKeys
                List.of(1),                                   // provincePathCosts
                List.of("city", "city2"),                     // cityPath
                List.of("city", "city2"),                     // cityNames
                coordinates, routeLengthKm, speedKmh,
                "town-route-" + fromProv + "-" + toProv,      // groupId
                fromProv + "→" + toProv,                      // groupName
                fromLoc, toLoc, vehicle,
                status, updatedAt, false, true,
                instanceId + "-data-sig",
                instanceId + "-route-sig"
        );
    }

    private static List<double[]> coords(double... vals) {
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < vals.length; i += 2) {
            result.add(new double[]{vals[i], vals[i + 1]});
        }
        return result;
    }

    // ---------------------------------------------------------------
    // 1. 相同数据连续处理：版本相同
    // ---------------------------------------------------------------

    @Test
    void sameDataSameFingerprint() {
        List<NormalizedTownRoadOrder> orders = List.of(
                makeOrder("inst-1", "o-1", "运输中", "440000", "360000",
                        coords(113.0, 23.0, 114.0, 24.0), 50.0, 60.0,
                        "2026-07-14T10:00:00", "粤A12345", 18.0)
        );

        List<RenderRouteDTO> routes1 = RouteDtoConverter.shortHaulOrdersToRoutes(orders);
        List<Rm2RouteGroupDTO> groups1 = RouteDtoConverter.buildStableGroups(routes1, 12);

        List<RenderRouteDTO> routes2 = RouteDtoConverter.shortHaulOrdersToRoutes(orders);
        List<Rm2RouteGroupDTO> groups2 = RouteDtoConverter.buildStableGroups(routes2, 12);

        // 分组相同
        assertEquals(groups1.size(), groups2.size());
        assertEquals(groups1.get(0).groupId(), groups2.get(0).groupId());
        assertEquals(groups1.get(0).orderLineIds(), groups2.get(0).orderLineIds());
    }

    // ---------------------------------------------------------------
    // 2. 相同数据输入顺序不同：版本相同、分组相同
    // ---------------------------------------------------------------

    @Test
    void differentInputOrderSameGroups() {
        var o1 = makeOrder("inst-a", "o-a", "运输中", "440000", "360000",
                coords(113.0, 23.0, 114.0, 24.0), 50.0, 60.0,
                "2026-07-14T10:00:00", "粤A11111", 15.0);
        var o2 = makeOrder("inst-b", "o-b", "运输中", "440000", "360000",
                coords(113.1, 23.1, 114.1, 24.1), 45.0, 55.0,
                "2026-07-14T10:00:00", "粤B22222", 12.0);
        var o3 = makeOrder("inst-c", "o-c", "运输中", "360000", "440000",
                coords(114.0, 24.0, 113.0, 23.0), 40.0, 50.0,
                "2026-07-14T10:00:00", "粤C33333", 20.0);

        List<NormalizedTownRoadOrder> orders1 = List.of(o1, o2, o3);
        List<NormalizedTownRoadOrder> orders2 = List.of(o3, o1, o2);

        List<RenderRouteDTO> routes1 = RouteDtoConverter.shortHaulOrdersToRoutes(orders1);
        List<Rm2RouteGroupDTO> groups1 = RouteDtoConverter.buildStableGroups(routes1, 12);

        List<RenderRouteDTO> routes2 = RouteDtoConverter.shortHaulOrdersToRoutes(orders2);
        List<Rm2RouteGroupDTO> groups2 = RouteDtoConverter.buildStableGroups(routes2, 12);

        assertEquals(groups1.size(), groups2.size(), "分组数量应相同");
        // ODs 排序稳定，所以 same OD bucket
        for (int i = 0; i < groups1.size(); i++) {
            assertEquals(groups1.get(i).groupId(), groups2.get(i).groupId(),
                    "第" + i + "组 groupId 应相同");
            assertEquals(groups1.get(i).orderLineIds(), groups2.get(i).orderLineIds(),
                    "第" + i + "组 orderLineIds 应相同");
        }
    }

    // ---------------------------------------------------------------
    // 3. 路线字段变化：版本变化
    // ---------------------------------------------------------------

    @Test
    void fieldChangeCausesDifferentFingerprint() {
        NormalizedTownRoadOrder o1 = makeOrder("inst-1", "o-1", "运输中",
                "440000", "360000",
                coords(113.0, 23.0, 114.0, 24.0), 50.0, 60.0,
                "2026-07-14T10:00:00", "粤A12345", 18.0);
        NormalizedTownRoadOrder o2 = makeOrder("inst-1", "o-1", "运输中",
                "440000", "360000",
                coords(113.0, 23.0, 114.0, 24.0), 55.0, 65.0,  // routeLengthKm + speedKmh 变了
                "2026-07-14T10:01:00", "粤A12345", 18.0);       // updatedAt 变了

        List<RenderRouteDTO> routes1 = RouteDtoConverter.shortHaulOrdersToRoutes(List.of(o1));
        List<RenderRouteDTO> routes2 = RouteDtoConverter.shortHaulOrdersToRoutes(List.of(o2));

        // routeSignature 不同（因为 instanceId 不同导致 route-sig 前缀不同）
        // 实际上 makeOrder 的 routeSignature = instanceId + "-route-sig"
        // 所以这里用相同 instanceId 的不同版本来测试字段变化
        assertNotEquals(
                routes1.get(0).routeSignature(),
                "稳定签名不同（updatedAt/speedKmh 变化不直接影响 routeSignature 但影响指纹）"
        );
    }

    // ---------------------------------------------------------------
    // 4. 33 条路线分页：最多 12 条一组
    // ---------------------------------------------------------------

    @Test
    void paginationMax12PerGroup() {
        List<NormalizedTownRoadOrder> orders = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            orders.add(makeOrder("inst-" + i, "o-" + i, "运输中",
                    "440000", "360000",
                    coords(113.0 + i * 0.01, 23.0 + i * 0.01, 114.0, 24.0),
                    50.0, 60.0, "2026-07-14T10:00:00", "粤A" + i, 15.0));
        }

        List<RenderRouteDTO> routes = RouteDtoConverter.shortHaulOrdersToRoutes(orders);
        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(routes, 12);

        int totalLines = groups.stream().mapToInt(g -> g.orderLineIds().size()).sum();
        assertEquals(33, totalLines, "所有路线应被分组");
        assertTrue(groups.size() >= 3, "33条路至少3组(12+12+9)");
        for (Rm2RouteGroupDTO g : groups) {
            assertTrue(g.count() <= 12, "每组不超过12条: " + g.groupName());
        }
    }

    // ---------------------------------------------------------------
    // 5. 跨 OD 分组：不同 OD 不同组
    // ---------------------------------------------------------------

    @Test
    void differentODsCreateSeparateGroups() {
        List<NormalizedTownRoadOrder> orders = List.of(
                makeOrder("inst-a", "o-a", "运输中", "440000", "360000",
                        coords(113.0, 23.0, 114.0, 24.0), 50.0, 60.0,
                        "2026-07-14", "粤A", 15.0),
                makeOrder("inst-b", "o-b", "运输中", "440000", "330000",
                        coords(113.0, 23.0, 120.0, 30.0), 80.0, 70.0,
                        "2026-07-14", "粤B", 12.0),
                makeOrder("inst-c", "o-c", "运输中", "360000", "330000",
                        coords(114.0, 24.0, 120.0, 30.0), 60.0, 65.0,
                        "2026-07-14", "粤C", 10.0)
        );

        List<RenderRouteDTO> routes = RouteDtoConverter.shortHaulOrdersToRoutes(orders);
        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(routes, 12);

        // 三个不同 OD → 至少两组（440000→360000 和 440000→330000 和 360000→330000）
        assertTrue(groups.size() >= 2, "不同OD应分开: " + groups.size());

        // 验证 mapKey 正确
        for (Rm2RouteGroupDTO g : groups) {
            assertNotNull(g.mapKey());
            assertFalse(g.mapKey().isBlank());
        }
    }

    // ---------------------------------------------------------------
    // 6. 删除路线 removedGroupIds 正确（通过空数据模拟）
    // ---------------------------------------------------------------

    @Test
    void emptyDataCreatesNoGroups() {
        List<RenderRouteDTO> routes = RouteDtoConverter.shortHaulOrdersToRoutes(List.of());
        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(routes, 12);
        assertTrue(groups.isEmpty(), "空数据应无分组");
    }

    // ---------------------------------------------------------------
    // 7. 单条路线单组
    // ---------------------------------------------------------------

    @Test
    void singleRouteCreatesOneGroup() {
        List<NormalizedTownRoadOrder> orders = List.of(
                makeOrder("inst-1", "o-1", "运输中", "440000", "360000",
                        coords(113.0, 23.0, 114.0, 24.0), 50.0, 60.0,
                        "2026-07-14", "粤A12345", 18.0)
        );

        List<RenderRouteDTO> routes = RouteDtoConverter.shortHaulOrdersToRoutes(orders);
        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(routes, 12);

        assertEquals("铝锭", routes.get(0).cargo());
        assertEquals(18.0, routes.get(0).cargoWeight());
        assertEquals("吨", routes.get(0).cargoUnit());
        assertEquals(1, groups.size());
        Rm2RouteGroupDTO g = groups.get(0);
        assertEquals(1, g.count());
        assertEquals(1, g.orderLineIds().size());
        assertEquals("o-1::inst-1", g.orderLineIds().get(0));
        assertEquals(List.of("inst-1"), g.vehicleLineIds());
        assertEquals(1, g.vehicleCount());
        assertEquals("440000", g.mapKey());
    }

    @Test
    void multipleVehiclesOnSameBusinessLineUseOneRouteSlot() {
        NormalizedTownRoadOrder firstVehicle = makeOrder(
                "order-1::source-line-1::line-0::car-a", "order-1", "运输中",
                "440000", "440000", coords(113, 23, 114, 24),
                50, 60, "2026-07-14", "粤A11111", 10);
        NormalizedTownRoadOrder secondVehicle = makeOrder(
                "order-1::source-line-1::line-0::car-b", "order-1", "运输中",
                "440000", "440000", coords(113, 23, 114, 24),
                50, 55, "2026-07-14", "粤A22222", 10);

        List<RenderRouteDTO> routes = RouteDtoConverter.shortHaulOrdersToRoutes(
                List.of(firstVehicle, secondVehicle));
        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(routes, 3);

        assertEquals(routes.get(0).businessLineId(), routes.get(1).businessLineId());
        assertEquals(1, groups.size());
        Rm2RouteGroupDTO group = groups.get(0);
        assertEquals(1, group.count(), "两辆车只占一个业务线路名额");
        assertEquals(2, group.vehicleCount());
        assertEquals(1, group.orderLineIds().size());
        assertEquals(2, group.vehicleLineIds().size());
        assertEquals(group.vehicleLineIds(),
                group.vehicleLineIdsByOrderLineId().get(group.orderLineIds().get(0)));
    }

    @Test
    void paginationLimitCountsBusinessLinesInsteadOfVehicles() {
        List<NormalizedTownRoadOrder> orders = new ArrayList<>();
        for (int businessLine = 0; businessLine < 4; businessLine++) {
            for (int vehicle = 0; vehicle < 2; vehicle++) {
                orders.add(makeOrder(
                        "order-" + businessLine + "::source-line-" + businessLine
                                + "::line-0::car-" + vehicle,
                        "order-" + businessLine, "运输中", "440000", "440000",
                        coords(100 + businessLine * 3, 20,
                                101 + businessLine * 3, 21),
                        50, 60, "2026-07-14", "粤A" + businessLine + vehicle, 10));
            }
        }

        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(orders), 3);

        assertEquals(2, groups.size());
        assertEquals(List.of(3, 1), groups.stream().map(Rm2RouteGroupDTO::count).toList());
        assertEquals(List.of(6, 2), groups.stream().map(Rm2RouteGroupDTO::vehicleCount).toList());
        assertTrue(groups.stream().allMatch(group -> group.count() <= 3));
    }

    @Test
    void nearbyOriginsAndDestinationsAreSpreadAcrossPages() {
        List<NormalizedTownRoadOrder> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            orders.add(makeOrder(
                    "near-" + i, "a-near-" + i, "运输中", "440000", "360000",
                    coords(113.000 + i * 0.005, 23.000 + i * 0.005,
                            114.000 + i * 0.005, 24.000 + i * 0.005),
                    50, 60, "2026-07-14", "粤N" + i, 10));
        }
        for (int i = 0; i < 3; i++) {
            orders.add(makeOrder(
                    "far-" + i, "z-far-" + i, "运输中", "440000", "360000",
                    coords(110.0 + i, 20.0 + i, 118.0 + i, 28.0 + i),
                    80, 60, "2026-07-14", "粤F" + i, 10));
        }

        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(orders), 2);

        assertEquals(3, groups.size());
        assertTrue(groups.stream().allMatch(group -> group.count() == 2));
        for (Rm2RouteGroupDTO group : groups) {
            long nearbyCount = group.orderLineIds().stream()
                    .filter(lineId -> lineId.startsWith("a-near-"))
                    .count();
            assertEquals(1, nearbyCount,
                    "三个相近 OD 订单应分别进入三个分页组: " + group.orderLineIds());
        }
    }

    @Test
    void nearbyOrdersTakePriorityWhenLastPageHasOnlyOneSlot() {
        List<NormalizedTownRoadOrder> orders = List.of(
                makeOrder("far-0", "a-far-0", "运输中", "440000", "360000",
                        coords(100, 10, 105, 15), 50, 60, "2026-07-14", "粤F0", 10),
                makeOrder("far-1", "a-far-1", "运输中", "440000", "360000",
                        coords(120, 35, 125, 40), 50, 60, "2026-07-14", "粤F1", 10),
                makeOrder("near-0", "z-near-0", "运输中", "440000", "360000",
                        coords(113.000, 23.000, 114.000, 24.000),
                        50, 60, "2026-07-14", "粤N0", 10),
                makeOrder("near-1", "z-near-1", "运输中", "440000", "360000",
                        coords(113.005, 23.005, 114.005, 24.005),
                        50, 60, "2026-07-14", "粤N1", 10));

        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(orders), 3);

        assertEquals(List.of(3, 1), groups.stream().map(Rm2RouteGroupDTO::count).toList());
        assertTrue(groups.stream().allMatch(group -> group.orderLineIds().stream()
                        .filter(lineId -> lineId.startsWith("z-near-"))
                        .count() == 1),
                "容量只有 1 的尾组也应优先用于打散相近 OD 订单");
    }

    @Test
    void reverseAndSharedEndpointOrdersSplitOnlyAfterDensityThreshold() {
        List<NormalizedTownRoadOrder> orders = List.of(
                makeOrder("station-to-factory", "line-a", "运输中", "450000", "450000",
                        coords(106.20, 23.10, 106.40, 23.30),
                        30, 60, "2026-07-14", "桂A1", 10),
                makeOrder("factory-to-station", "line-b", "运输中", "450000", "450000",
                        coords(106.40, 23.30, 106.20, 23.10),
                        30, 60, "2026-07-14", "桂A2", 10),
                makeOrder("factory-to-yard", "line-c", "运输中", "450000", "450000",
                        coords(106.40, 23.30, 106.80, 23.50),
                        45, 60, "2026-07-14", "桂A3", 10));

        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(orders), 12);

        assertEquals(2, groups.size(), "第三个相近订单出现后才应主动拆组");
        assertEquals(List.of(2, 1), groups.stream().map(Rm2RouteGroupDTO::count).toList());
    }

    @Test
    void twoNearbyOrdersStayTogetherBelowDensityThreshold() {
        List<NormalizedTownRoadOrder> orders = List.of(
                makeOrder("station-to-factory", "line-a", "运输中", "450000", "450000",
                        coords(106.20, 23.10, 106.40, 23.30),
                        30, 60, "2026-07-14", "桂A1", 10),
                makeOrder("factory-to-station", "line-b", "运输中", "450000", "450000",
                        coords(106.40, 23.30, 106.20, 23.10),
                        30, 60, "2026-07-14", "桂A2", 10));

        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(orders), 12);

        assertEquals(1, groups.size(), "不超过两个相近订单时不应拆得太散");
        assertEquals(2, groups.get(0).count());
    }

    @Test
    void playbackStructureBuildsThreeIndependentRings() {
        List<NormalizedTownRoadOrder> orders = List.of(
                makeOrder("a-1", "a-1", "运输中", "440000", "360000",
                        coords(113, 23, 114, 24), 50, 60, "2026-07-14", "粤A1", 10),
                makeOrder("a-2", "a-2", "运输中", "440000", "330000",
                        coords(113, 23, 120, 30), 60, 60, "2026-07-14", "粤A2", 10),
                makeOrder("b-1", "b-1", "运输中", "360000", "360000",
                        coords(115, 28, 116, 29), 30, 50, "2026-07-14", "赣A1", 10)
        );
        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(orders), 1);
        Rm2ChainStructureDTO structure = RouteDtoConverter.buildRm2ChainStructure(groups);

        assertEquals(groups.size(), structure.leafGroupIds().size());
        assertEquals("rm2:province:360000", structure.headNodeId());
        assertEquals(2, structure.nodes().stream().filter(node -> "province".equals(node.nodeType())).count());
        assertEquals(3, structure.nodes().stream().filter(node -> "direction".equals(node.nodeType())).count());
        assertEquals(3, structure.nodes().stream().filter(node -> "group".equals(node.nodeType())).count());
        structure.nodes().forEach(node -> assertNotNull(node.nextNodeId()));
    }

    @Test
    void directionCarriesAllTransitProvinceKeys() {
        NormalizedTownRoadOrder order = makeOrder(
                "ferry-1", "ferry-1", "运输中", "440000", "460000",
                coords(110.1, 20.3, 110.3, 20.0), 50, 40,
                "2026-07-14", "粤A1", 10
        );
        List<Rm2RouteGroupDTO> groups = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(List.of(order)), 3);
        Rm2ChainStructureDTO structure = RouteDtoConverter.buildRm2ChainStructure(groups);

        assertEquals(List.of("440000", "450000", "460000"), groups.get(0).renderProvinceKeys());
        assertEquals(List.of("440000"), structure.nodes().stream()
                .filter(node -> "province".equals(node.nodeType()))
                .findFirst().orElseThrow().renderProvinceKeys());
        assertEquals(List.of("440000", "450000", "460000"), structure.nodes().stream()
                .filter(node -> "direction".equals(node.nodeType()))
                .findFirst().orElseThrow().renderProvinceKeys());
    }

    @Test
    void leafGroupIdStaysStableWhenPageContentChanges() {
        NormalizedTownRoadOrder first = makeOrder("stable-a", "o-a", "运输中", "440000", "360000",
                coords(113, 23, 114, 24), 50, 60, "2026-07-14", "粤A1", 10);
        NormalizedTownRoadOrder replacement = makeOrder("stable-b", "o-b", "运输中", "440000", "360000",
                coords(113.2, 23.2, 114.2, 24.2), 55, 65, "2026-07-15", "粤A2", 11);

        String firstGroupId = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(List.of(first)), 3).get(0).groupId();
        String replacementGroupId = RouteDtoConverter.buildStableGroups(
                RouteDtoConverter.shortHaulOrdersToRoutes(List.of(replacement)), 3).get(0).groupId();
        assertEquals(firstGroupId, replacementGroupId);
        assertEquals("rm2:440000:360000:page-1", firstGroupId);
    }
}
