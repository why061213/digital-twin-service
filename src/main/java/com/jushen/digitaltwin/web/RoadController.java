package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.service.SimulationDataFactory;
import com.jushen.digitaltwin.service.RoutePushService;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/road")
public class RoadController {

    private final SimulationDataFactory dataFactory;
    private final RoutePushService routePushService;

    private final RealtimeWebSocketHandler webSocketHandler;

    public RoadController(SimulationDataFactory dataFactory,
                          RoutePushService routePushService,
                          RealtimeWebSocketHandler webSocketHandler) {
        this.dataFactory = dataFactory;
        this.routePushService = routePushService;
        this.webSocketHandler = webSocketHandler;
    }
    @PostMapping("/dispatch")
    public Map<String, Object> dispatchRoute() {
        return routePushService.dispatchRandomRoute();
    }

    @PostMapping("/dispatch/bulk")
    public Map<String, Object> dispatchBulkOrder(
            @RequestParam(defaultValue = "24") int vehicleCount
    ) {
        return routePushService.dispatchBulkOrder(vehicleCount);
    }

    @GetMapping("/groups")
    public Map<String, Object> listRouteGroups(@RequestParam(required = false) String strategy) {
        return routePushService.listRouteGroups(strategy);
    }

    @GetMapping("/groups/{groupId}/routes")
    public Map<String, Object> listRoutesByGroup(
            @PathVariable String groupId,
            @RequestParam(required = false) String strategy
    ) {
        return routePushService.listRoutesByGroup(groupId, strategy);
    }

    @GetMapping("/routes/{lineId}/position")
    public Map<String, Object> getTruckPosition(@PathVariable String lineId) {
        return routePushService.getPosition(lineId);
    }

    // 原有直线接口保持不变
    @GetMapping("/path")
    public Map<String, Object> getRoadPath(
            @RequestParam double fromLng,
            @RequestParam double fromLat,
            @RequestParam double toLng,
            @RequestParam double toLat,
            @RequestParam(defaultValue = "20") int points
    ) {
        List<double[]> path = dataFactory.simulateRoadPath(fromLng, fromLat, toLng, toLat, points);
        return Map.of("coordinates", path);
    }




    // 新增多航点接口（POST）

    @PostMapping("/path")
    public Map<String, Object> getMultiPointPath(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<List<?>> rawPoints = (List<List<?>>) body.get("points");
        if (rawPoints == null || rawPoints.size() < 2) {
            throw new IllegalArgumentException("points 参数至少需要两个坐标");
        }

        int totalPoints = 200;  // 默认值
        if (body.containsKey("totalPoints")) {
            totalPoints = ((Number) body.get("totalPoints")).intValue();
        }

        List<double[]> waypoints = new ArrayList<>();
        for (List<?> point : rawPoints) {
            double lng = ((Number) point.get(0)).doubleValue();
            double lat = ((Number) point.get(1)).doubleValue();
            waypoints.add(new double[]{lng, lat});
        }

        List<double[]> path = dataFactory.simulateMultiPointPath(waypoints, totalPoints);
        return Map.of("coordinates", path);
    }

    @PostMapping("/routes/query-position")
    public Map<String, Object> queryPositionManually(@RequestBody Map<String, String> body) {
        String carId = body.get("carId");
        if (carId == null || carId.isBlank()) {
            return Map.of("error", "carId is required");
        }

        Map<String, Object> posMap = routePushService.queryPositionByCarId(carId);
        if (posMap == null) {
            return Map.of("found", false);
        }

        double lng = (double) posMap.get("lng");
        double lat = (double) posMap.get("lat");
        double speed = (double) posMap.get("speedKmh");

        // 广播临时位置消息
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "truck_position");
        message.put("lineId", "manual-" + carId);
        message.put("position", new double[]{lng, lat});
        message.put("speedKmh", speed);
        message.put("status", "running");
        webSocketHandler.broadcast(message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("found", true);
        response.put("lng", lng);
        response.put("lat", lat);
        response.put("speedKmh", speed);
        return response;
    }
}
