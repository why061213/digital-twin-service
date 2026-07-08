package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.townroad.ExternalOrderRecord;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;
import com.jushen.digitaltwin.townroad.TownRoadRenderService;

/**
 * 开发阶段 TownRoadMap 模拟命令控制器。
 *
 * 用途：
 * 1. 不依赖真实外部运输服务。
 * 2. 手动触发后端向前端 WebSocket 广播 town_road_render 命令。
 * 3. 让前端验证 TownRoadMap 省/市/区县 3D 渲染链路。
 */
@RestController
@RequestMapping("/api/town-road/mock")
public class TownRoadMockController {

    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final TownRoadRenderService townRoadRenderService;

    public TownRoadMockController(RealtimeWebSocketHandler realtimeWebSocketHandler, TownRoadRenderService townRoadRenderService) {
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
        this.townRoadRenderService = townRoadRenderService;
    }

    /**
     * 健康检查：确认 controller 已经被 Spring 扫描到。
     * 调用：GET http://localhost:8080/api/town-road/mock/ping
     */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("module", "town-road-mock");
        response.put("time", Instant.now().toString());
        return response;
    }

    /**
     * 开发阶段模拟后端下发 TownRoadMap 渲染命令。
     * 调用：POST http://localhost:8080/api/town-road/mock/provinces
     */

    //正式接口
    @PostMapping("/provinces")
    public Map<String, Object> pushProvinceRenderCommand(
            @RequestBody(required = false) Map<String, Object> payload
    ) {
        return townRoadRenderService.fetchProcessAndBroadcast(
                payload == null ? Map.of() : payload
        );
    }
//      模拟接口
    @PostMapping("/provinces")
    public Map<String, Object> pushProvinceRenderCommand() {
        List<ExternalOrderRecord> rawOrders = buildMockExternalOrders();
        return townRoadRenderService.processAndBroadcast(rawOrders);
    }

    private List<ExternalOrderRecord> buildMockExternalOrders() {
        String now = java.time.Instant.now().toString();

        return List.of(
                new ExternalOrderRecord(
                        "TOWN-MOCK-001",
                        "town-backend-gd-001",
                        new ExternalOrderRecord.Location(
                                "广东省广州市番禺区",
                                "广东省",
                                "广州市",
                                "番禺区",
                                "440113",
                                new double[]{113.383917, 22.93756}
                        ),
                        new ExternalOrderRecord.Location(
                                "广东省佛山市南海区",
                                "广东省",
                                "佛山市",
                                "南海区",
                                "440605",
                                new double[]{113.145577, 23.031562}
                        ),
                        new ExternalOrderRecord.Vehicle(
                                "粤A-B001",
                                "TOWN-BACKEND-GD-001",
                                12.0,
                                "吨",
                                null,
                                null
                        ),
                        "运输中",
                        now,
                        false,
                        true
                ),

                new ExternalOrderRecord(
                        "TOWN-MOCK-002",
                        "town-backend-fj-001",
                        new ExternalOrderRecord.Location(
                                "福建省厦门市集美区",
                                "福建省",
                                "厦门市",
                                "集美区",
                                "350211",
                                new double[]{118.100869, 24.572874}
                        ),
                        new ExternalOrderRecord.Location(
                                "福建省泉州市晋江市",
                                "福建省",
                                "泉州市",
                                "晋江市",
                                "350582",
                                new double[]{118.552365, 24.781681}
                        ),
                        new ExternalOrderRecord.Vehicle(
                                "闽D-B002",
                                "TOWN-BACKEND-FJ-001",
                                8.0,
                                "吨",
                                null,
                                null
                        ),
                        "装载中",
                        now,
                        false,
                        true
                ),

                new ExternalOrderRecord(
                        "TOWN-MOCK-003",
                        "town-backend-hn-001",
                        new ExternalOrderRecord.Location(
                                "湖南省长沙市岳麓区",
                                "湖南省",
                                "长沙市",
                                "岳麓区",
                                "430104",
                                new double[]{112.931375, 28.235193}
                        ),
                        new ExternalOrderRecord.Location(
                                "湖南省株洲市天元区",
                                "湖南省",
                                "株洲市",
                                "天元区",
                                "430211",
                                new double[]{113.136252, 27.826909}
                        ),
                        new ExternalOrderRecord.Vehicle(
                                "湘A-B003",
                                "TOWN-BACKEND-HN-001",
                                10.0,
                                "吨",
                                null,
                                null
                        ),
                        "运输中",
                        now,
                        false,
                        true
                ),

                new ExternalOrderRecord(
                        "TOWN-MOCK-004",
                        "town-backend-jx-001",
                        new ExternalOrderRecord.Location(
                                "江西省南昌市青山湖区",
                                "江西省",
                                "南昌市",
                                "青山湖区",
                                "360111",
                                new double[]{115.962144, 28.682985}
                        ),
                        new ExternalOrderRecord.Location(
                                "江西省九江市浔阳区",
                                "江西省",
                                "九江市",
                                "浔阳区",
                                "360403",
                                new double[]{116.00193, 29.705077}
                        ),
                        new ExternalOrderRecord.Vehicle(
                                "赣A-B004",
                                "TOWN-BACKEND-JX-001",
                                6.0,
                                "吨",
                                null,
                                null
                        ),
                        "运输中",
                        now,
                        false,
                        true
                ),

                // 广东 -> 福建，测试省份大网最短路径。
                // ProvinceRoadGraph 里已经写了 440000 <-> 350000，所以这里应该是广东 / 福建 两省短途。
                new ExternalOrderRecord(
                        "TOWN-MOCK-005",
                        "town-backend-gd-fj-001",
                        new ExternalOrderRecord.Location(
                                "广东省梅州市梅江区",
                                "广东省",
                                "梅州市",
                                "梅江区",
                                "441402",
                                new double[]{116.116686, 24.31065}
                        ),
                        new ExternalOrderRecord.Location(
                                "福建省龙岩市新罗区",
                                "福建省",
                                "龙岩市",
                                "新罗区",
                                "350802",
                                new double[]{117.036816, 25.098942}
                        ),
                        new ExternalOrderRecord.Vehicle(
                                "粤M-B005",
                                "TOWN-BACKEND-GD-FJ-001",
                                9.0,
                                "吨",
                                null,
                                null
                        ),
                        "运输中",
                        now,
                        false,
                        true
                )
        );
    }

    private Map<String, Object> buildProvinceDistrictCommand() {
        String now = Instant.now().toString();
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "town_road_render");
        command.put("commandId", "mock-province-district-" + System.currentTimeMillis());
        command.put("title", "广东 / 福建 / 湖南 / 江西 短途区县展示");
        command.put("description", "后端模拟命令：只下发目标省份列表和后端筛选后的订单列表");
        command.put("renderProvinces", Arrays.asList("440000", "350000", "430000", "360000"));
        command.put("orders", buildOrders(now));
        command.put("issuedAt", now);
        return command;
    }

    private List<Map<String, Object>> buildOrders(String now) {
        List<Map<String, Object>> orders = new ArrayList<>();

        orders.add(order(
            "TOWN-MOCK-001",
            "town-backend-gd-001",
            endpoint("广东省广州市番禺区", "广东省", "广州市", "番禺区", "440113", 113.383917, 22.937560),
            endpoint("广东省佛山市南海区", "广东省", "佛山市", "南海区", "440605", 113.145577, 23.031562),
            "粤A-B001",
            "TOWN-BACKEND-GD-001",
            "运输中",
            12,
            now
        ));

        orders.add(order(
            "TOWN-MOCK-002",
            "town-backend-fj-001",
            endpoint("福建省厦门市集美区", "福建省", "厦门市", "集美区", "350211", 118.100869, 24.572874),
            endpoint("福建省泉州市晋江市", "福建省", "泉州市", "晋江市", "350582", 118.552365, 24.781681),
            "闽D-B002",
            "TOWN-BACKEND-FJ-001",
            "装载中",
            8,
            now
        ));

        orders.add(order(
            "TOWN-MOCK-003",
            "town-backend-hn-001",
            endpoint("湖南省长沙市岳麓区", "湖南省", "长沙市", "岳麓区", "430104", 112.931375, 28.235193),
            endpoint("湖南省株洲市天元区", "湖南省", "株洲市", "天元区", "430211", 113.136252, 27.826909),
            "湘A-B003",
            "TOWN-BACKEND-HN-001",
            "运输中",
            10,
            now
        ));

        orders.add(order(
            "TOWN-MOCK-004",
            "town-backend-jx-001",
            endpoint("江西省南昌市青山湖区", "江西省", "南昌市", "青山湖区", "360111", 115.962144, 28.682985),
            endpoint("江西省九江市浔阳区", "江西省", "九江市", "浔阳区", "360403", 116.001930, 29.705077),
            "赣A-B004",
            "TOWN-BACKEND-JX-001",
            "运输中",
            6,
            now
        ));

        return orders;
    }

    private Map<String, Object> order(
        String orderId,
        String lineId,
        Map<String, Object> from,
        Map<String, Object> to,
        String plate,
        String carId,
        String status,
        Number cargoWeight,
        String updatedAt
    ) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", orderId);
        order.put("lineId", lineId);
        order.put("groupId", "town-province-district-demo");
        order.put("groupName", "四省区县短途渲染演示");
        order.put("from", from);
        order.put("to", to);
        order.put("vehicle", vehicle(plate, carId, cargoWeight));
        order.put("status", status);
        order.put("updatedAt", updatedAt);
        order.put("deleted", false);
        return order;
    }

    private Map<String, Object> endpoint(
        String name,
        String province,
        String city,
        String district,
        String adcode,
        double lng,
        double lat
    ) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("name", name);
        endpoint.put("province", province);
        endpoint.put("city", city);
        endpoint.put("district", district);
        endpoint.put("adcode", adcode);
        endpoint.put("coords", Arrays.asList(lng, lat));
        return endpoint;
    }

    private Map<String, Object> vehicle(String plate, String carId, Number cargoWeight) {
        Map<String, Object> vehicle = new LinkedHashMap<>();
        vehicle.put("plate", plate);
        vehicle.put("carId", carId);
        vehicle.put("cargoWeight", cargoWeight);
        vehicle.put("cargoUnit", "吨");
        return vehicle;
    }
}
