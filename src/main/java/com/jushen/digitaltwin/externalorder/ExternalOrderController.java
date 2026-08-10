package com.jushen.digitaltwin.externalorder;

import com.jushen.digitaltwin.grouping.GroupSummary;
import com.jushen.digitaltwin.grouping.GroupingContext;
import com.jushen.digitaltwin.grouping.RouteGroupingEngine;
import com.jushen.digitaltwin.grouping.RouteGroupingResult;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/road/external-orders")
public class ExternalOrderController {

    private final ExternalOrderSyncService syncService;
    private final ExternalOrderStore store;
    private final RouteGroupingEngine groupingEngine;

    public ExternalOrderController(
            ExternalOrderSyncService syncService,
            ExternalOrderStore store,
            RouteGroupingEngine groupingEngine
    ) {
        this.syncService = syncService;
        this.store = store;
        this.groupingEngine = groupingEngine;
    }

    /**
     * 手动触发一次：
     * 后端 POST 调外部接口 -> 本地 diff -> 更新缓存 -> 可选 WebSocket 推送。
     */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestBody(required = false) Map<String, Object> payload) {
        return syncService.sync(payload == null ? Map.of() : payload);
    }

    /**
     * 查看当前后端缓存里的所有外部订单路线。
     */
    @GetMapping
    public Map<String, Object> listRoutes() {
        List<ExternalOrderRoute> routes = syncService.allRoutes();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", routes.size());
        response.put("routes", routes.stream().map(this::routeMessage).toList());

        return response;
    }

    /**
     * 复用现有 grouping 策略，按真实订单路线分组。
     */
    @GetMapping("/groups")
    public Map<String, Object> groups(
            @RequestParam(defaultValue = "business-priority") String strategy,
            @RequestParam(defaultValue = "5") int groupSize
    ) {
        List<ExternalOrderRoute> routes = syncService.allRoutes();

        RouteGroupingResult result = groupingEngine.group(
                routes,
                GroupingContext.defaults(strategy, Math.max(1, groupSize))
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("strategy", result.getStrategy());
        response.put("groupSize", result.getGroupSize());
        response.put("totalRoutes", result.getTotalRoutes());
        response.put("groups", result.getGroups().stream().map(this::groupMessage).toList());

        return response;
    }

    @GetMapping("/groups/{groupId}/routes")
    public Map<String, Object> routesByGroup(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "business-priority") String strategy,
            @RequestParam(defaultValue = "5") int groupSize
    ) {
        List<ExternalOrderRoute> allRoutes = syncService.allRoutes();

        List<?> routes = groupingEngine.routesByGroup(
                allRoutes,
                GroupingContext.defaults(strategy, Math.max(1, groupSize)),
                groupId
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groupId", groupId);
        response.put("strategy", strategy);
        response.put("groupSize", groupSize);
        response.put("routes", routes.stream()
                .filter(item -> item instanceof ExternalOrderRoute)
                .map(item -> routeMessage((ExternalOrderRoute) item))
                .toList());

        return response;
    }

    /**
     * 查询相同起点目的地订单。
     */
    @GetMapping("/same-od")
    public Map<String, Object> sameOd(
            @RequestParam String fromKey,
            @RequestParam String toKey
    ) {
        List<ExternalOrderRoute> routes = syncService.findSameOdOrders(fromKey, toKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fromKey", fromKey);
        response.put("toKey", toKey);
        response.put("count", routes.size());
        response.put("routes", routes.stream().map(this::routeMessage).toList());

        return response;
    }

    /**
     * 沿途订单查询。
     * 请求体：
     * {
     *   "routeNodeKeys": ["440605", "440113", "310115"]
     * }
     */
    @PostMapping("/along-route")
    public Map<String, Object> alongRoute(@RequestBody Map<String, List<String>> body) {
        List<String> routeNodeKeys = body.getOrDefault("routeNodeKeys", List.of());
        List<ExternalOrderRoute> routes = syncService.findOrdersAlongRoute(routeNodeKeys);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("routeNodeKeys", routeNodeKeys);
        response.put("count", routes.size());
        response.put("routes", routes.stream().map(this::routeMessage).toList());

        return response;
    }

    private Map<String, Object> groupMessage(GroupSummary summary) {
        Map<String, Object> message = new LinkedHashMap<>();

        message.put("groupId", summary.getGroupId());
        message.put("groupKey", summary.getGroupKey());
        message.put("groupType", summary.getGroupType());
        message.put("groupScenario", summary.getGroupScenario());
        message.put("scenarioReason", summary.getScenarioReason());
        message.put("displayTemplate", summary.getDisplayTemplate());
        message.put("styleHint", summary.getStyleHint());
        message.put("orderIds", summary.getOrderIds());
        message.put("routeKey", summary.getRouteKey());
        message.put("pathKey", summary.getPathKey());
        message.put("segmentKeys", summary.getSegmentKeys());
        message.put("count", summary.getCount());

        return message;
    }

    private Map<String, Object> routeMessage(ExternalOrderRoute route) {
        Map<String, Object> message = new LinkedHashMap<>();

        message.put("type", "road_path");
        message.put("lineId", route.lineId());
        message.put("orderId", route.orderId());
        message.put("orderFamilyId", route.orderFamilyId());
        message.put("pathKey", route.pathKey());
        message.put("fromKey", route.fromKey());
        message.put("toKey", route.toKey());
        message.put("from", route.from());
        message.put("to", route.to());
        message.put("fromCoords", route.fromCoords());
        message.put("toCoords", route.toCoords());
        message.put("coordinates", route.coordinates());
        message.put("routeLengthKm", route.routeLengthKm());
        message.put("speedKmh", route.speedKmh());
        message.put("travelDurationMs", route.travelDurationMs());
        message.put("plate", route.plate());
        message.put("carId", route.carId());
        message.put("cargoWeight", route.cargoWeight());
        message.put("cargoUnit", route.cargoUnit());
        message.put("status", route.status());
        message.put("updatedAt", route.updatedAt());

        return message;
    }
}