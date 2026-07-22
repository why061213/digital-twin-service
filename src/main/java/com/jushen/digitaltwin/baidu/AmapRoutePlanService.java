package com.jushen.digitaltwin.baidu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 高德地图驾车路线规划服务。
 * 调用 v5/direction/driving 获取驾车路线，
 * 核心输出：steps[].polyline 拼接的完整路线坐标 + 导航指令。
 */
@Service
public class AmapRoutePlanService {

    private static final Logger log = LoggerFactory.getLogger(AmapRoutePlanService.class);

    private static final String API_URL = "https://restapi.amap.com/v5/direction/driving";
    private static final String TRUCK_API_URL = "https://restapi.amap.com/v4/direction/truck";

    private final String key;
    private final boolean useTruckRouting;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AmapRoutePlanService(
            @Value("${dashboard.route-plan.amap-key:}") String key,
            @Value("${dashboard.route-plan.use-truck-routing:false}") boolean useTruckRouting) {
        this.key = key;
        this.useTruckRouting = useTruckRouting;
    }

    // ================================================================
    // 公开方法
    // ================================================================

    /**
     * 规划驾车路线。（高德坐标系 GCJ-02）
     *
     * @param originLat      起点纬度
     * @param originLng      起点经度
     * @param destinationLat 终点纬度
     * @param destinationLng 终点经度
     */
    public RoutePlanResult planRoute(double originLat, double originLng,
                                     double destinationLat, double destinationLng) {
        return planRoute(originLat, originLng, destinationLat, destinationLng, List.of());
    }

    public RoutePlanResult planRoute(
            double originLat,
            double originLng,
            double destinationLat,
            double destinationLng,
            List<double[]> waypoints
    ) {
        // 高德参数格式：lng,lat（经度在前）
        String origin = originLng + "," + originLat;
        String destination = destinationLng + "," + destinationLat;
        String waypointText = waypointText(waypoints);
        return planRoute(origin, destination, waypointText);
    }

    public RoutePlanResult planRoute(String origin, String destination) {
        return planRoute(origin, destination, "");
    }

    private RoutePlanResult planRoute(String origin, String destination, String waypoints) {
        if (key == null || key.isBlank()) {
            return RoutePlanResult.fail("高德 Key 未配置");
        }
        try {
            String baseUrl = useTruckRouting ? TRUCK_API_URL : API_URL;
            String url = baseUrl + "?origin=" + origin + "&destination=" + destination
                    + "&key=" + key;
            if (useTruckRouting) {
                // v4 货车 API 参数：show_fields 不支持，使用默认返回
            } else {
                url += "&show_fields=polyline,cost";
            }
            if (!waypoints.isBlank()) url += "&waypoints=" + waypoints;
            log.info("[AmapRoute] 请求{}: origin={}, dest={}, waypointCount={}",
                    useTruckRouting ? "(货车)" : "", origin, destination,
                    waypoints.isBlank() ? 0 : waypoints.split(";").length);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[AmapRoute] HTTP {}: {}", response.statusCode(), response.body());
                return RoutePlanResult.fail("HTTP " + response.statusCode());
            }

            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            return useTruckRouting ? parseResultV4(body) : parseResult(body);

        } catch (Exception e) {
            log.error("[AmapRoute] 请求失败: {}", e.getMessage());
            return RoutePlanResult.fail(e.getMessage());
        }
    }

    private String waypointText(List<double[]> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) return "";
        return waypoints.stream()
                .filter(point -> point != null && point.length >= 2
                        && Double.isFinite(point[0]) && Double.isFinite(point[1]))
                .limit(10)
                .map(point -> point[0] + "," + point[1])
                .collect(java.util.stream.Collectors.joining(";"));
    }

    // ================================================================
    // 解析
    // ================================================================

    @SuppressWarnings("unchecked")
    private RoutePlanResult parseResult(Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", ""));
        if (!"1".equals(status)) {
            return RoutePlanResult.fail("status=" + status + ", " + body.get("info"));
        }

        Map<String, Object> route = (Map<String, Object>) body.get("route");
        if (route == null) return RoutePlanResult.fail("无 route");

        List<Map<String, Object>> paths = (List<Map<String, Object>>) route.get("paths");
        if (paths == null || paths.isEmpty()) return RoutePlanResult.fail("无 paths");

        Map<String, Object> firstPath = paths.get(0);
        // v5: distance 在外层，duration/tolls 在 cost 子对象
        int totalDistance = Integer.parseInt(String.valueOf(firstPath.get("distance")));
        Map<String, Object> pathCost = (Map<String, Object>) firstPath.get("cost");
        int totalDuration = pathCost != null ? parseInt(pathCost.get("duration")) : 0;
        double toll = pathCost != null ? parseDouble(pathCost.get("tolls")) : 0;

        List<Map<String, Object>> steps = (List<Map<String, Object>>) firstPath.get("steps");
        if (steps == null) steps = List.of();

        List<double[]> fullPath = new ArrayList<>();
        List<RouteStep> stepInfos = new ArrayList<>();

        for (Map<String, Object> step : steps) {
            // v5: step_distance, road_name, cost.duration
            int distance = parseInt(step.get("step_distance"));
            Map<String, Object> stepCost = (Map<String, Object>) step.get("cost");
            int duration = stepCost != null ? parseInt(stepCost.get("duration")) : 0;

            String instruction = (String) step.getOrDefault("instruction", "");
            String polyline = (String) step.getOrDefault("polyline", "");
            String road = (String) step.getOrDefault("road_name", "");
            String action = (String) step.getOrDefault("action", "");
            String orientation = (String) step.getOrDefault("orientation", "");

            List<double[]> stepCoords = parsePolyline(polyline);
            if (stepCoords.isEmpty()) continue;

            fullPath.addAll(stepCoords);

            stepInfos.add(new RouteStep(
                    distance, duration, instruction,
                    road, action, orientation,
                    stepCoords.get(0),
                    stepCoords.get(stepCoords.size() - 1)
            ));
        }

        List<double[]> dedupedPath = deduplicatePath(fullPath);

        log.info("[AmapRoute] 规划成功: 总距{}m, 总时{}s, 路费{}元, 步数{}, 点数{}",
                totalDistance, totalDuration, toll, steps.size(), dedupedPath.size());

        return RoutePlanResult.success(totalDistance, totalDuration, toll, dedupedPath, stepInfos);
    }

    /** 解析 v4 货车 API 返回（distance/duration 在 path 顶层，不在 cost 子对象中） */
    @SuppressWarnings("unchecked")
    private RoutePlanResult parseResultV4(Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", ""));
        if (!"1".equals(status)) {
            return RoutePlanResult.fail("status=" + status + ", " + body.get("info"));
        }

        Map<String, Object> route = (Map<String, Object>) body.get("route");
        if (route == null) return RoutePlanResult.fail("无 route");

        List<Map<String, Object>> paths = (List<Map<String, Object>>) route.get("paths");
        if (paths == null || paths.isEmpty()) return RoutePlanResult.fail("无 paths");

        Map<String, Object> firstPath = paths.get(0);
        // v4: distance/duration 直接在 path 顶层
        int totalDistance = Integer.parseInt(String.valueOf(firstPath.get("distance")));
        int totalDuration = Integer.parseInt(String.valueOf(firstPath.get("duration")));

        List<Map<String, Object>> steps = (List<Map<String, Object>>) firstPath.get("steps");
        if (steps == null) steps = List.of();

        List<double[]> fullPath = new ArrayList<>();
        List<RouteStep> stepInfos = new ArrayList<>();

        for (Map<String, Object> step : steps) {
            // v4: distance/duration 直接在 step 顶层
            int distance = Integer.parseInt(String.valueOf(step.get("distance")));
            int duration = Integer.parseInt(String.valueOf(step.get("duration")));

            String instruction = (String) step.getOrDefault("instruction", "");
            String polyline = (String) step.getOrDefault("polyline", "");
            String road = (String) step.getOrDefault("road", "");
            String action = (String) step.getOrDefault("action", "");
            String orientation = (String) step.getOrDefault("orientation", "");

            List<double[]> stepCoords = parsePolyline(polyline);
            if (stepCoords.isEmpty()) continue;

            fullPath.addAll(stepCoords);

            stepInfos.add(new RouteStep(
                    distance, duration, instruction,
                    road, action, orientation,
                    stepCoords.get(0),
                    stepCoords.get(stepCoords.size() - 1)
            ));
        }

        List<double[]> dedupedPath = deduplicatePath(fullPath);

        log.info("[AmapRoute] 货车规划成功: 总距{}m, 总时{}s, 步数{}, 点数{}",
                totalDistance, totalDuration, steps.size(), dedupedPath.size());

        return RoutePlanResult.success(totalDistance, totalDuration, 0, dedupedPath, stepInfos);
    }

    /** 解析 polyline 字符串 "lng1,lat1;lng2,lat2;..." */
    private List<double[]> parsePolyline(String polyline) {
        if (polyline == null || polyline.isBlank()) return List.of();
        List<double[]> coords = new ArrayList<>();
        for (String point : polyline.split(";")) {
            String[] parts = point.trim().split(",");
            if (parts.length < 2) continue;
            try {
                // 高德 polyline 格式是 "lng,lat" (经度在前，和百度一样)
                double lng = Double.parseDouble(parts[0]);
                double lat = Double.parseDouble(parts[1]);
                coords.add(new double[]{lng, lat});
            } catch (NumberFormatException ignored) { }
        }
        return coords;
    }

    private List<double[]> deduplicatePath(List<double[]> path) {
        List<double[]> result = new ArrayList<>();
        double[] last = null;
        for (double[] coord : path) {
            if (last != null && last[0] == coord[0] && last[1] == coord[1]) continue;
            result.add(coord);
            last = coord;
        }
        return result;
    }

    private int parseInt(Object val) {
        if (val == null) return 0;
        try { return Integer.parseInt(String.valueOf(val)); }
        catch (NumberFormatException e) { return 0; }
    }

    private double parseDouble(Object val) {
        if (val == null) return 0;
        try { return Double.parseDouble(String.valueOf(val)); }
        catch (NumberFormatException e) { return 0; }
    }

    // ================================================================
    // 返回类型
    // ================================================================

    public static class RoutePlanResult {
        public boolean success;
        public String error;
        public int totalDistance;
        public int totalDuration;
        public double toll;                 // 预估路费(元)
        public List<double[]> path;
        public List<RouteStep> steps;

        static RoutePlanResult success(int distance, int duration, double toll,
                                       List<double[]> path, List<RouteStep> steps) {
            RoutePlanResult r = new RoutePlanResult();
            r.success = true;
            r.totalDistance = distance;
            r.totalDuration = duration;
            r.toll = toll;
            r.path = path;
            r.steps = steps;
            return r;
        }

        static RoutePlanResult fail(String error) {
            RoutePlanResult r = new RoutePlanResult();
            r.success = false;
            r.error = error;
            r.path = List.of();
            r.steps = List.of();
            return r;
        }

        @Override
        public String toString() {
            return success
                    ? String.format("成功: %dm/%ds, 路费%.0f元, %d步, %d坐标点",
                            totalDistance, totalDuration, toll, steps.size(), path.size())
                    : "失败: " + error;
        }
    }

    public static class RouteStep {
        public int distance;
        public int duration;
        public String instruction;      // 导航文本
        public String road;             // 道路名称
        public String action;           // 动作（右转/左转/直行等）
        public String orientation;      // 方向（西南/西北等）
        public double[] startLocation;
        public double[] endLocation;

        RouteStep(int distance, int duration, String instruction,
                  String road, String action, String orientation,
                  double[] startLocation, double[] endLocation) {
            this.distance = distance;
            this.duration = duration;
            this.instruction = instruction;
            this.road = road;
            this.action = action;
            this.orientation = orientation;
            this.startLocation = startLocation;
            this.endLocation = endLocation;
        }

        @Override
        public String toString() {
            return String.format("[%dm/%ds] %s → %s (%s)", distance, duration, instruction, road, action);
        }
    }

    // ================================================================
    // 测试
    // ================================================================
    public static void main(String[] args) {
        AmapRoutePlanService service = new AmapRoutePlanService("your-amap-key", false);
        // 北京天安门 → 北京西站（经纬度用 GCJ-02）
        RoutePlanResult result = service.planRoute(39.908823, 116.397470, 39.894962, 116.322200);
        System.out.println("结果: " + result);
        if (result.success) {
            System.out.println("前3步:");
            result.steps.stream().limit(3).forEach(System.out::println);
            System.out.println("前5个坐标点:");
            result.path.stream().limit(5).forEach(p -> System.out.printf("  [%.6f, %.6f]%n", p[0], p[1]));
        }
    }
}
