package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.service.RoutePushService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 供应商历史轨迹接口。每辆待分析车辆在一个最长 48 小时的窗口内只请求一次。 */
@Service
public class ProviderTrajectoryClient {
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final RoutePushService routePushService;
    private final String externalPositionUrl;
    private final Duration timeout;
    private final HttpClient httpClient;

    public ProviderTrajectoryClient(
            ObjectMapper objectMapper,
            RoutePushService routePushService,
            @Value("${dashboard.route.external-position-url:}") String externalPositionUrl,
            @Value("${dashboard.route.position-refresh.request-timeout-ms:10000}") int requestTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.routePushService = routePushService;
        this.externalPositionUrl = externalPositionUrl == null ? "" : externalPositionUrl.trim();
        this.timeout = Duration.ofMillis(Math.max(1000, requestTimeoutMs));
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public TrajectoryResult fetch(String vehicleId, Instant start, Instant end) {
        if (vehicleId == null || vehicleId.isBlank() || start == null || end == null || !start.isBefore(end)) {
            return new TrajectoryResult(false, "invalid-request", List.of());
        }
        if (externalPositionUrl.isBlank()) {
            return new TrajectoryResult(false, "provider-url-missing", List.of());
        }
        String token = routePushService.externalAccessTokenForOrderAnalysis();
        if (token == null || token.isBlank()) {
            return new TrajectoryResult(false, "provider-token-missing", List.of());
        }
        try {
            ZoneId zone = ZoneId.systemDefault();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("car_id", vehicleId);
            body.put("start_time", REQUEST_TIME.format(LocalDateTime.ofInstant(start, zone)));
            body.put("end_time", REQUEST_TIME.format(LocalDateTime.ofInstant(end, zone)));
            body.put("effective", 1);
            body.put("replacement", 1);
            body.put("order_no", 1);
            body.put("lbs", 0);
            body.put("parking_point", 0);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(externalPositionUrl + "/video/webapi/location/get-trajectory-use-id"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", token)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new TrajectoryResult(false, "http-" + response.statusCode(), List.of());
            }
            return parseResponse(response.body());
        } catch (Exception exception) {
            return new TrajectoryResult(false, exception.getClass().getSimpleName(), List.of());
        }
    }

    TrajectoryResult parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.path("code").asInt(-1) != 200) {
                return new TrajectoryResult(false, "provider-code-" + root.path("code").asText(), List.of());
            }
            JsonNode data = root.path("data");
            if (data.isObject() && data.path("data").isArray()) data = data.path("data");
            if (!data.isArray()) return new TrajectoryResult(false, "provider-data-not-array", List.of());
            List<TrackPoint> points = new ArrayList<>();
            for (JsonNode node : data) {
                Instant time = parseTime(firstText(node, "gpstime", "recvtime"));
                Double lng = number(node.get("lng"));
                Double lat = number(node.get("lat"));
                if (time == null || lng == null || lat == null) continue;
                Double glngOffset = number(node.get("glng"));
                Double glatOffset = number(node.get("glat"));
                if (glngOffset != null && Math.abs(glngOffset) < 1) lng += glngOffset;
                if (glatOffset != null && Math.abs(glatOffset) < 1) lat += glatOffset;
                if (!validCoordinate(lng, lat)) continue;
                points.add(new TrackPoint(time, lng, lat));
            }
            points.sort(Comparator.comparing(TrackPoint::time));
            return new TrajectoryResult(true, "ok", List.copyOf(points));
        } catch (Exception exception) {
            return new TrajectoryResult(false, "invalid-json", List.of());
        }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.asText().isBlank()) return value.asText().trim();
        }
        return null;
    }

    private Instant parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value.trim().replace(' ', 'T'))
                        .atZone(ZoneId.systemDefault()).toInstant();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private Double number(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try {
            double parsed = Double.parseDouble(value.asText());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean validCoordinate(double lng, double lat) {
        return lng >= -180 && lng <= 180 && lat >= -90 && lat <= 90
                && !(Math.abs(lng) < 0.000001 && Math.abs(lat) < 0.000001);
    }

    public record TrackPoint(Instant time, double lng, double lat) {}

    public record TrajectoryResult(boolean success, String reason, List<TrackPoint> points) {}
}
