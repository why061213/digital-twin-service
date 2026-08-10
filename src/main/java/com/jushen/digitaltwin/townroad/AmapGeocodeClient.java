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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高德地图 Web API 地理编码客户端。
 * 通过省/市/区/地名查询经纬度，查询成功后自动写入 LocalCoordDb。
 * 支持 SHA256 安全签名。
 *
 * API 文档：https://lbs.amap.com/api/webservice/guide/api/georegeo
 */
@Component
public class AmapGeocodeClient {

    private static final Logger log = LoggerFactory.getLogger(AmapGeocodeClient.class);

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String REGEOCODE_URL = "https://restapi.amap.com/v3/geocode/regeo";

    /** 统计计数器 */
    private int successCount = 0;
    private int failCount = 0;
    private int skipCount = 0;

    private final CoordDbProperties properties;
    private final LocalCoordDb localCoordDb;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    /** 约 5km 网格缓存行政区；坐标始终使用本次 GPS，不能随缓存锚点冻结。 */
    private final Map<String, AdministrativeArea> reverseCache = new ConcurrentHashMap<>();

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
            skipCount++;
            return local;
        }

        // 2. 本地没有，调高德 API
        String key = properties.getAmapKey();
        if (key == null || key.isBlank()) {
            log.warn("[Amap] no API key configured");
            skipCount++;
            return null;
        }

        // 构建地址：name 通常已包含完整地址，避免重复拼接省市区
        String addressStr = buildSmartAddress(province, city, district, name);
        if (addressStr.isBlank()) return null;

        try {
            double[] result = callGeocodeApi(key, addressStr, city);
            if (result != null) {
                successCount++;
                // 回写本地库
                String effDistrict = district != null ? district : name;
                localCoordDb.put(province, city, effDistrict, name, result);
            } else {
                failCount++;
            }
            return result;
        } catch (Exception e) {
            failCount++;
            log.warn("[Amap] geocode exception for '{}': {}", addressStr, e.getMessage());
            return null;
        }
    }

    /**
     * 智能构建地址：如果 name 已经以省/市/区开头，避免重复拼接。
     * 例如 name="云南省红河哈尼族彝族自治州泸西县..." 时直接用 name，
     * 不再拼成 "云南省红河哈尼族彝族自治州泸西县云南省红河哈尼族彝族自治州泸西县..."
     */
    private String buildSmartAddress(String province, String city, String district, String name) {
        String trimmedName = name.trim();

        // 检测 name 是否已包含省市区前缀
        boolean alreadyHasPrefix = false;
        if (province != null && !province.isBlank() && trimmedName.startsWith(province)) {
            alreadyHasPrefix = true;
        } else if (city != null && !city.isBlank() && trimmedName.startsWith(city)) {
            alreadyHasPrefix = true;
        } else if (district != null && !district.isBlank() && trimmedName.startsWith(district)) {
            alreadyHasPrefix = true;
        }

        if (alreadyHasPrefix) {
            // name 已是完整地址，直接使用
            return trimmedName;
        }

        // name 不含省市区前缀，拼接完整地址
        StringBuilder sb = new StringBuilder();
        if (province != null && !province.isBlank()) sb.append(province);
        if (city != null && !city.isBlank()) sb.append(city);
        if (district != null && !district.isBlank()) sb.append(district);
        if (name.equals(district)) {
            // name 就是区名本身，已经拼接过了
        } else {
            sb.append(trimmedName);
        }
        return sb.toString();
    }

    /**
     * 简化的查询接口（只给 name）。
     */
    public double[] geocode(String name) {
        return geocode(null, null, null, name);
    }

    /** 按真实 GPS 反查行政区；失败时返回 null，调用方不得复制目标节点行政区。 */
    public synchronized ExternalOrderRecord.Location reverseGeocode(double[] coordinates) {
        if (coordinates == null || coordinates.length < 2) return null;
        String cacheKey = reverseGridKey(coordinates);
        AdministrativeArea cached = reverseCache.get(cacheKey);
        if (cached != null) return cached.at(coordinates);
        String key = properties.getAmapKey();
        if (key == null || key.isBlank()) return null;
        try {
            rateLimit();
            Map<String, String> params = new TreeMap<>();
            params.put("key", key);
            params.put("location", coordinates[0] + "," + coordinates[1]);
            params.put("extensions", "base");
            String secret = properties.getAmapSecret();
            if (secret != null && !secret.isBlank()) params.put("sig", sign(params, secret));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl(REGEOCODE_URL, params)))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs() > 0
                            ? properties.getRequestTimeoutMs() : 12000))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("status").asInt(0) != 1) return null;
            JsonNode regeocode = root.path("regeocode");
            JsonNode component = regeocode.path("addressComponent");
            String province = text(component.path("province"));
            String city = text(component.path("city"));
            if (city == null) city = text(component.path("province"));
            String district = text(component.path("district"));
            String adcode = text(component.path("adcode"));
            String name = text(regeocode.path("formatted_address"));
            AdministrativeArea area = new AdministrativeArea(
                    name == null ? "车辆当前位置" : name, province, city, district, adcode);
            reverseCache.put(cacheKey, area);
            return area.at(coordinates);
        } catch (Exception exception) {
            log.warn("[Amap] reverse geocode failed for {}: {}", cacheKey, exception.getMessage());
            return null;
        }
    }

    private String reverseGridKey(double[] coordinates) {
        double gridDegrees = 0.05d;
        long lngCell = (long) Math.floor(coordinates[0] / gridDegrees);
        long latCell = (long) Math.floor(coordinates[1] / gridDegrees);
        return lngCell + ":" + latCell;
    }

    private record AdministrativeArea(
            String name,
            String province,
            String city,
            String district,
            String adcode
    ) {
        private ExternalOrderRecord.Location at(double[] coordinates) {
            return new ExternalOrderRecord.Location(
                    name, province, city, district, adcode, coordinates.clone());
        }
    }

    private String text(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String value = node.asText().trim();
        return value.isBlank() ? null : value;
    }

    /** 获取统计信息并重置计数器 */
    public synchronized String getStatsAndReset() {
        String stats = String.format("[Amap] session stats: success=%d fail=%d skip(local)=%d",
                successCount, failCount, skipCount);
        successCount = 0;
        failCount = 0;
        skipCount = 0;
        return stats;
    }

    // ---- 签名 & 请求 ----

    private double[] callGeocodeApi(String key, String address, String city) throws IOException, InterruptedException {
        // 限速：3次/秒
        rateLimit();

        // 构建参数（使用 TreeMap 自动按 key 字典排序）
        Map<String, String> params = new TreeMap<>();
        params.put("key", key);
        params.put("address", address);
        if (city != null && !city.isBlank()) {
            params.put("city", city);
        }

        // 如果配置了安全密钥，计算签名
        String secret = properties.getAmapSecret();
        if (secret != null && !secret.isBlank()) {
            String sig = sign(params, secret);
            params.put("sig", sig);
        }

        String url = buildUrl(GEOCODE_URL, params);

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
            log.warn("[Amap] HTTP {} for '{}': {}", response.statusCode(), address, response.body());
            return null;
        }

        JsonNode root = objectMapper.readTree(response.body());
        int status = root.path("status").asInt(0);
        if (status != 1) {
            String info = root.path("info").asText("UNKNOWN");
            String infocode = root.path("infocode").asText("");
            log.warn("[Amap] API status={} infocode={} info={} for '{}'",
                    status, infocode, info, address);
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

    /**
     * 限速：确保两次调用间隔 >= amapRateLimitMs
     */
    private void rateLimit() {
        long now = System.currentTimeMillis();
        long waitMs = properties.getAmapRateLimitMs() - (now - lastCallAt);
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAt = System.currentTimeMillis();
    }

    /**
     * 构建请求 URL
     */
    private String buildUrl(String baseUrl, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(entry.getKey());
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 高德 V3 签名算法：SHA256(排序后的参数串 + secret)
     * 参数串格式：key1=value1&key2=value2（value 不编码，直接用原始值）
     */
    private String sign(Map<String, String> params, String secret) {
        StringBuilder raw = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) raw.append('&');
            raw.append(entry.getKey());
            raw.append('=');
            raw.append(entry.getValue());
            first = false;
        }
        raw.append(secret);

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        return hex.toString();
    }
}
