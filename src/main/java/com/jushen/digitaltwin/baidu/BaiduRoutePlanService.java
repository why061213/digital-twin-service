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
import java.util.stream.Collectors;

/**
 * 百度地图驾车路线规划服务。
 * 调用 directionlite/v1/driving 获取驾车路线，
 * 核心输出：steps[].path 拼接的完整路线坐标 + 导航指令。
 */
@Service
public class BaiduRoutePlanService {

    private static final Logger log = LoggerFactory.getLogger(BaiduRoutePlanService.class);

    private static final String API_URL = "https://api.map.baidu.com/directionlite/v1/driving";

    private final String ak;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BaiduRoutePlanService(
            @Value("${dashboard.route-plan.baidu-ak:}") String ak) {
        this.ak = ak;
    }

    // ================================================================
    // 公开方法
    // ================================================================

    /**
     * 规划驾车路线。
     *
     * @param originLat      起点纬度
     * @param originLng      起点经度
     * @param destinationLat 终点纬度
     * @param destinationLng 终点经度
     * @return 规划结果，包含完整路线坐标和分步信息
     */
    public RoutePlanResult planRoute(double originLat, double originLng,
                                     double destinationLat, double destinationLng) {
        String origin = originLat + "," + originLng;
        String destination = destinationLat + "," + destinationLng;
        return planRoute(origin, destination);
    }

    /**
     * 规划驾车路线。
     *
     * @param origin      "lat,lng" 格式的起点
     * @param destination "lat,lng" 格式的终点
     * @return 规划结果
     */
    public RoutePlanResult planRoute(String origin, String destination) {
        try {
            String url = API_URL + "?origin=" + origin + "&destination=" + destination + "&ak=" + ak;
            log.info("[BaiduRoute] 请求: origin={}, dest={}", origin, destination);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[BaiduRoute] HTTP {}: {}", response.statusCode(), response.body());
                return RoutePlanResult.fail("HTTP " + response.statusCode());
            }

            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            return parseResult(body);

        } catch (Exception e) {
            log.error("[BaiduRoute] 请求失败: {}", e.getMessage());
            return RoutePlanResult.fail(e.getMessage());
        }
    }

    // ================================================================
    // 解析
    // ================================================================

    @SuppressWarnings("unchecked")
    private RoutePlanResult parseResult(Map<String, Object> body) {
        Number status = (Number) body.get("status");
        if (status == null || status.intValue() != 0) {
            return RoutePlanResult.fail("status=" + status + ", " + body.get("message"));
        }

        Map<String, Object> result = (Map<String, Object>) body.get("result");
        if (result == null) return RoutePlanResult.fail("无 result");

        List<Map<String, Object>> routes = (List<Map<String, Object>>) result.get("routes");
        if (routes == null || routes.isEmpty()) return RoutePlanResult.fail("无 routes");

        Map<String, Object> firstRoute = routes.get(0);
        int totalDistance = ((Number) firstRoute.get("distance")).intValue();
        int totalDuration = ((Number) firstRoute.get("duration")).intValue();

        List<Map<String, Object>> steps = (List<Map<String, Object>>) firstRoute.get("steps");
        if (steps == null) steps = List.of();

        // 拼接所有 steps 的 path 坐标
        List<double[]> fullPath = new ArrayList<>();
        List<RouteStep> stepInfos = new ArrayList<>();

        for (Map<String, Object> step : steps) {
            int distance = ((Number) step.get("distance")).intValue();
            int duration = ((Number) step.get("duration")).intValue();
            String instruction = (String) step.getOrDefault("instruction", "");
            String path = (String) step.getOrDefault("path", "");

            // 提取本段 path 坐标
            List<double[]> stepCoords = parsePath(path);
            if (stepCoords.isEmpty()) continue;

            fullPath.addAll(stepCoords);

            stepInfos.add(new RouteStep(
                    distance,
                    duration,
                    cleanInstruction(instruction),
                    stepCoords.get(0),                       // 段起点
                    stepCoords.get(stepCoords.size() - 1)    // 段终点
            ));
        }

        // 去重（连续相同坐标只保留一个）
        List<double[]> dedupedPath = deduplicatePath(fullPath);

        log.info("[BaiduRoute] 规划成功: 总距{}m, 总时{}s, 步数{}, 点数{}",
                totalDistance, totalDuration, steps.size(), dedupedPath.size());

        return RoutePlanResult.success(totalDistance, totalDuration, dedupedPath, stepInfos);
    }

    /** 解析 path 字符串 "lng1,lat1;lng2,lat2;..." */
    private List<double[]> parsePath(String path) {
        if (path == null || path.isBlank()) return List.of();
        List<double[]> coords = new ArrayList<>();
        for (String point : path.split(";")) {
            String[] parts = point.trim().split(",");
            if (parts.length < 2) continue;
            try {
                // 百度 path 格式是 "lng,lat" (经度在前)
                double lng = Double.parseDouble(parts[0]);
                double lat = Double.parseDouble(parts[1]);
                coords.add(new double[]{lng, lat});
            } catch (NumberFormatException ignored) { }
        }
        return coords;
    }

    /** 去除连续重复坐标 */
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

    /** 去除 instruction 中的 HTML 标签 */
    private String cleanInstruction(String instr) {
        if (instr == null) return "";
        return instr.replaceAll("<[^>]+>", "").trim();
    }

    // ================================================================
    // 返回类型
    // ================================================================

    /** 路线规划结果 */
    public static class RoutePlanResult {
        public boolean success;
        public String error;
        public int totalDistance;       // 总距离(米)
        public int totalDuration;       // 总时长(秒)
        public List<double[]> path;     // 完整路线坐标 [[lng,lat], ...]
        public List<RouteStep> steps;   // 分步信息

        static RoutePlanResult success(int distance, int duration, List<double[]> path, List<RouteStep> steps) {
            RoutePlanResult r = new RoutePlanResult();
            r.success = true;
            r.totalDistance = distance;
            r.totalDuration = duration;
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
                    ? String.format("成功: %dm/%ds, %d步, %d坐标点", totalDistance, totalDuration,
                            steps.size(), path.size())
                    : "失败: " + error;
        }
    }

    /** 单步路段信息 */
    public static class RouteStep {
        public int distance;            // 本段距离(米)
        public int duration;            // 本段时长(秒)
        public String instruction;      // 导航文本（已去标签）
        public double[] startLocation;  // 段起点 [lng, lat]
        public double[] endLocation;    // 段终点 [lng, lat]

        RouteStep(int distance, int duration, String instruction,
                  double[] startLocation, double[] endLocation) {
            this.distance = distance;
            this.duration = duration;
            this.instruction = instruction;
            this.startLocation = startLocation;
            this.endLocation = endLocation;
        }

        @Override
        public String toString() {
            return String.format("[%dm/%ds] %s", distance, duration, instruction);
        }
    }

    // ================================================================
    // 测试用 main
    // ================================================================
    public static void main(String[] args) {
        BaiduRoutePlanService service = new BaiduRoutePlanService("your-baidu-ak");
        // 北京天安门 → 北京西站
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
