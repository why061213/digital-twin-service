package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从外部订单的 province/city/district/name 字段中补全经纬度。
 *
 * 查找优先级：LocalCoordDb（分层库） → district-coordinates.json（旧扁平库） → AmapGeocodeClient（高德API）
 */
@Component
public class TownRoadCoordinateResolver {

    private static final Logger log = LoggerFactory.getLogger(TownRoadCoordinateResolver.class);

    /** 直辖市列表 */
    private static final java.util.Set<String> MUNICIPALITIES = java.util.Set.of(
            "北京市", "天津市", "上海市", "重庆市"
    );

    private final ObjectMapper objectMapper;
    private final LocalCoordDb localCoordDb;
    private final AmapGeocodeClient amapClient;

    /** 旧的扁平库：名称 → [lng, lat]（兜底用） */
    private Map<String, double[]> nameToCoords = Map.of();

    public TownRoadCoordinateResolver(ObjectMapper objectMapper,
                                       LocalCoordDb localCoordDb,
                                       AmapGeocodeClient amapClient) {
        this.objectMapper = objectMapper;
        this.localCoordDb = localCoordDb;
        this.amapClient = amapClient;
    }

    @PostConstruct
    public void loadCoordinateData() {
        try {
            ClassPathResource resource = new ClassPathResource("district-coordinates.json");
            try (InputStream in = resource.getInputStream()) {
                nameToCoords = objectMapper.readValue(
                        in,
                        new TypeReference<LinkedHashMap<String, double[]>>() {}
                );
            }
            log.info("[TownRoadCoord] loaded {} entries from district-coordinates.json (fallback)", nameToCoords.size());
        } catch (Exception e) {
            log.warn("[TownRoadCoord] failed to load district-coordinates.json: {}", e.getMessage());
            nameToCoords = Map.of();
        }

        log.info("[TownRoadCoord] local coord-db has {} entries, fallback has {} entries",
                localCoordDb.size(), nameToCoords.size());
    }

    /**
     * 返回解析后的 Location，补全了 coords。
     * 直接使用外部接口提供的 province/city/district/name 字段，不做正则解析。
     */
    public ExternalOrderRecord.Location resolveLocation(ExternalOrderRecord.Location location) {
        if (location == null) return null;
        if (hasCoords(location.coords())) return location;

        String province = trimToNull(location.province());
        String city = trimToNull(location.city());
        String district = trimToNull(location.district());
        String name = trimToNull(location.name());

        // 没有任何可用的地址信息
        if (province == null && name == null) return location;

        // 直辖市优化："重庆市"下"市辖区"不是真正的地级市，合并到 district 维度
        if (province != null && MUNICIPALITIES.contains(province)) {
            if ("市辖区".equals(city) || "县".equals(city)) {
                city = null; // 直辖市的"市辖区"/"县"是虚层级
            }
        }

        double[] resolved = null;

        // ---- 1. LocalCoordDb 分层库 ----
        // 1a. 省+市+区+名 全路径
        if (resolved == null && province != null && name != null) {
            resolved = localCoordDb.resolve(province, city, district, name);
        }
        // 1b. 省+市+区（当 name 就是区名本身时）
        if (resolved == null && province != null && district != null) {
            resolved = localCoordDb.resolve(province, city, district, district);
        }
        // 1c. 只用 name 查
        if (resolved == null && name != null) {
            resolved = localCoordDb.get(name);
        }

        // ---- 2. 旧扁平库 district-coordinates.json ----
        // 2a. 省+市+区
        if (resolved == null && province != null && district != null) {
            String key = (city != null) ? province + city + district : province + district;
            resolved = nameToCoords.get(key);
        }
        // 2b. 省+市
        if (resolved == null && province != null && city != null) {
            resolved = nameToCoords.get(province + city);
        }
        // 2c. 只用城市名
        if (resolved == null && city != null) {
            resolved = nameToCoords.get(city);
        }
        // 2d. 省
        if (resolved == null && province != null) {
            resolved = nameToCoords.get(province);
        }
        // 2e. 原始 name
        if (resolved == null && name != null) {
            resolved = nameToCoords.get(name);
        }

        // ---- 3. 高德 API ----
        if (resolved == null) {
            // 构建最准确的 API 查询地址
            String queryName = name != null ? name : (district != null ? district : "");
            resolved = amapClient.geocode(province, city, district, queryName);
        }

        if (resolved == null || resolved.length < 2) return location;

        log.debug("[TownRoadCoord] resolved coords for {}/{}/{} '{}' → [{}, {}]",
                province, city, district, name, resolved[0], resolved[1]);

        return new ExternalOrderRecord.Location(
                location.name(),
                location.province(),
                location.city(),
                location.district(),
                location.adcode(),
                resolved
        );
    }

    private boolean hasCoords(double[] coords) {
        return coords != null && coords.length >= 2
               && Double.isFinite(coords[0]) && Double.isFinite(coords[1]);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
