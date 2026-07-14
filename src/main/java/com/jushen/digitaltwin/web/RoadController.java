package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.service.SimulationDataFactory;
import com.jushen.digitaltwin.service.RoutePushService;
import com.jushen.digitaltwin.service.VehiclePositionCacheService;
import com.jushen.digitaltwin.service.PositionSnapshot;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
@RestController
@RequestMapping("/api/road")
public class RoadController {

    private final SimulationDataFactory dataFactory;
    private final RoutePushService routePushService;
    private final VehiclePositionCacheService positionCache;
    private final RealtimeWebSocketHandler webSocketHandler;

    public RoadController(SimulationDataFactory dataFactory,
                          RoutePushService routePushService,
                          VehiclePositionCacheService positionCache,
                          RealtimeWebSocketHandler webSocketHandler) {
        this.dataFactory = dataFactory;
        this.routePushService = routePushService;
        this.positionCache = positionCache;
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

    /**
     * 批量查询车辆位置（只读缓存，不穿透外部接口）。
     */
    @PostMapping("/vehicles/positions/query")
    public Map<String, Object> queryPositions(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> lineIds = (List<String>) body.get("lineIds");
        if (lineIds == null || lineIds.isEmpty()) {
            return Map.of("serverTime", java.time.Instant.now().toString(),
                    "positions", List.of(),
                    "missingLineIds", List.of(),
                    "staleLineIds", List.of());
        }

        // 去重
        Set<String> deduped = new LinkedHashSet<>(lineIds);
        List<Map<String, Object>> positions = new ArrayList<>();
        List<String> missingLineIds = new ArrayList<>();
        List<String> staleLineIds = new ArrayList<>();

        for (String lineId : deduped) {
            if (lineId == null || lineId.isBlank()) continue;
            PositionSnapshot snapshot = positionCache.getPosition(lineId);
            if (snapshot == null) {
                // 缓存没有，回退到 getPosition（可能触发模拟位置）
                Map<String, Object> pos = routePushService.getPosition(lineId);
                if (pos != null && pos.containsKey("position")) {
                    positions.add(pos);
                } else {
                    missingLineIds.add(lineId);
                }
            } else {
                if (snapshot.stale()) {
                    staleLineIds.add(lineId);
                }
                Map<String, Object> pos = new LinkedHashMap<>();
                pos.put("lineId", lineId);
                pos.put("type", "truck_position");
                pos.put("position", snapshot.position());
                pos.put("speedKmh", snapshot.speedKmh());
                pos.put("source", snapshot.source());
                pos.put("stale", snapshot.stale());
                pos.put("fetchedAt", snapshot.fetchedAt().toString());
                if (snapshot.vehicleId() != null) pos.put("vehicleId", snapshot.vehicleId());
                positions.add(pos);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serverTime", java.time.Instant.now().toString());
        response.put("cacheAgeMs", 0);
        response.put("positions", positions);
        response.put("missingLineIds", missingLineIds);
        response.put("staleLineIds", staleLineIds);
        return response;
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
        String vehicleKey = firstPresent(body.get("plate"), body.get("carId"), body.get("query"));
        if (vehicleKey == null || vehicleKey.isBlank()) {
            return Map.of("error", "plate or carId is required");
        }

        Map<String, Object> posMap = routePushService.queryPositionByVehicleKey(vehicleKey);
        if (posMap == null) {
            return Map.of("found", false);
        }

        double lng = (double) posMap.get("lng");
        double lat = (double) posMap.get("lat");
        double speed = (double) posMap.get("speedKmh");

        // 广播临时位置消息
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "truck_position");
        message.put("lineId", "manual-" + vehicleKey);
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

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
