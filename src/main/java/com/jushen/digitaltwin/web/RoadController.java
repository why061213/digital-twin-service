package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.service.SimulationDataFactory;
import com.jushen.digitaltwin.service.RoutePushService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/road")
public class RoadController {

    private final SimulationDataFactory dataFactory;
    private final RoutePushService routePushService;

    public RoadController(SimulationDataFactory dataFactory, RoutePushService routePushService) {
        this.dataFactory = dataFactory;
        this.routePushService = routePushService;
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
}
