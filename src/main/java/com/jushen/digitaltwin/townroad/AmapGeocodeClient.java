package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 高德地图 Web API 地理编码客户端。
 * 通过省/市/区/地名查询经纬度，查询成功后自动写入 LocalCoordDb。
 *
 * API 文档：https://lbs.amap.com/api/webservice/guide/api/georegeo
 */
@Component
public class AmapGeocodeClient {

    private static final Logger log = LoggerFactory.getLogger(AmapGeocodeClient.class);

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";

    private final CoordDbProperties properties;
    private final LocalCoordDb localCoordDb;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 上一次调用 API 的时间，用于限速 */
    private long lastCallAt = 0;

    public AmapGeocodeClient(CoordDbProperties properties, LocalCoordDb localCoordDb, ObjectMapper objectMapper) {
        this.properties = properties;
        this.localCoordDb = localCoordDb;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
    }

    /**
     * 根据省/市/区/名查询经纬度。先查本地库，本地没有再调高德 API。
     *
     * @return [lng, lat] 或 null
     */
    public double[] geocode(String province, String city, String district, String name) {
        if (name == null || name.isBlank()) return null;

        // 1. 先查本地库
        double[] local = localCoordDb.resolve(province, city, district, name);
        if (local != null) {
            log.debug("[Amap] local hit: {}/{}/{}/{}", province, city, district, name);
            return local;
        }

        // 2. 本地没有，调高德 API
        String key = properties.getAmapKey();
        if (key == null || key.isBlank()) {
            log.warn("[Amap] no API key configured, skip geocode for: {}", name);
            return null;
        }

        // 构建地址：省+市+区+名
        StringBuilder address = new StringBuilder();
        if (province != null && !province.isBlank()) address.append(province);
        if (city != null && !city.isBlank()) address.append(city);
        if (district != null && !district.isBlank()) address.append(district);
        String detailName = name.equals(district) ? "" : name;
        if (!detailName.isBlank()) address.append(detailName);

        String addressStr = address.toString();
        if (addressStr.isBlank()) return null;

        try {
            double[] result = callGeocodeApi(key, addressStr, city);
            if (result != null) {
                // 回写本地库
                String effDistrict = district != null ? district : name;
                localCoordDb.put(province, city, effDistrict, name, result);
            }
            return result;
        } catch (Exception e) {
            log.warn("[Amap] geocode failed for '{}': {}", addressStr, e.getMessage());
            return null;
        }
    }

    /**
     * 简化的查询接口（只给 name）。
     */
    public double[] geocode(String name) {
        return geocode(null, null, null, name);
    }

    private double[] callGeocodeApi(String key, String address, String city) throws IOException, InterruptedException {
        // 限速
        long now = System.currentTimeMillis();
        long waitMs = properties.getAmapRateLimitMs() - (now - lastCallAt);
        if (waitMs > 0) {
            Thread.sleep(waitMs);
        }
        lastCallAt = System.currentTimeMillis();

        String encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8);
        String url = GEOCODE_URL + "?key=" + key + "&address=" + encodedAddress;
        if (city != null && !city.isBlank()) {
            url += "&city=" + URLEncoder.encode(city, StandardCharsets.UTF_8);
        }

        log.debug("[Amap] calling geocode: {}", address);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs() > 0
                        ? properties.getRequestTimeoutMs()
                        : 12000))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("[Amap] HTTP {} for address: {}", response.statusCode(), address);
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        int status = root.path("status").asInt(0);
        if (status != 1) {
            String info = root.path("info").asText("unknown");
            log.warn("[Amap] API status={} info={} for address: {}", status, info, address);
            return null;
        }

        JsonNode geocodes = root.path("geocodes");
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            log.debug("[Amap] no geocodes for address: {}", address);
            return null;
        }

        // 取第一个结果
        String location = geocodes.get(0).path("location").asText("");
        if (location.isBlank()) return null;

        String[] parts = location.split(",");
        if (parts.length != 2) return null;

        double lng = Double.parseDouble(parts[0].trim());
        double lat = Double.parseDouble(parts[1].trim());

        log.info("[Amap] geocode success: {} -> [{}, {}]", address, lng, lat);
        return new double[]{lng, lat};
    }
}
