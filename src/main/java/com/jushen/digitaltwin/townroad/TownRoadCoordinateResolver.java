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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从地址名称（如"山东省滨州市无棣县东风港站"）中解析省市区，
 * 并查本地坐标库补全经纬度。
 *
 * 查找优先级：LocalCoordDb（分层库） → district-coordinates.json（旧扁平库） → AmapGeocodeClient（高德API）
 */
@Component
public class TownRoadCoordinateResolver {

    private static final Logger log = LoggerFactory.getLogger(TownRoadCoordinateResolver.class);

    // {2,15} 支持长地名如"文山壮族苗族自治州""巴音郭楞蒙古自治州"
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "^([\\u4e00-\\u9fa5]{2,4}?(?:省|自治区|特别行政区|市))"
                    + "([\\u4e00-\\u9fa5]{2,15}?(?:市|自治州|地区|盟|区))?"
                    + "([\\u4e00-\\u9fa5]{2,15}?(?:县|区|市|旗|自治县|县级市))?"
                    + "(.*)$"
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
        // 加载旧的扁平库作为兜底
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
     * 尝试从名称中解析省市区并查坐标。
     */
    public double[] resolve(String name, String existingProvince) {
        if (name == null || name.isBlank()) return null;

        Matcher m = NAME_PATTERN.matcher(name.trim());
        if (!m.matches()) {
            // 尝试直接用名称查库
            double[] coords = localCoordDb.get(name.trim());
            if (coords != null) return coords;
            coords = nameToCoords.get(name.trim());
            if (coords != null) return coords;
            return amapClient.geocode(name.trim());
        }

        String province = m.group(1);
        String city = m.group(2);
        String district = m.group(3);
        String detail = m.group(4);

        // 1. 省+市+区+详细信息（新库）
        if (district != null && detail != null && !detail.isBlank()) {
            double[] coords = localCoordDb.resolve(province, city, district, detail);
            if (coords != null) return coords;
        }

        // 2. 省+市+区（新库）
        if (district != null) {
            double[] coords = localCoordDb.resolve(province, city, district, district);
            if (coords != null) return coords;

            // 旧库：省+市+区 全路径
            String fullPath = province + city + district;
            coords = nameToCoords.get(fullPath);
            if (coords != null) return coords;
        }

        // 3. 省+市（旧库）
        if (city != null) {
            String pc = province + city;
            double[] coords = nameToCoords.get(pc);
            if (coords != null) return coords;

            // 只用城市名
            coords = nameToCoords.get(city);
            if (coords != null) return coords;
        }

        // 4. 省
        if (existingProvince != null && !existingProvince.isBlank()) {
            double[] coords = nameToCoords.get(existingProvince);
            if (coords != null) return coords;
        }
        double[] coords = nameToCoords.get(province);
        if (coords != null) return coords;

        // 5. 原始名称
        coords = nameToCoords.get(name.trim());
        if (coords != null) return coords;

        // 6. 最后走一遍新库的 flat 查询
        coords = localCoordDb.get(name.trim());
        if (coords != null) return coords;

        // 7. 高德 API 兜底
        return amapClient.geocode(province, city, district,
                detail != null && !detail.isBlank() ? detail : district);
    }

    /**
     * 返回解析后的 Location，补全了 coords。
     */
    public ExternalOrderRecord.Location resolveLocation(ExternalOrderRecord.Location location) {
        if (location == null) return null;
        if (hasCoords(location.coords())) return location;

        String name = location.name();
        if (name == null || name.isBlank()) return location;

        double[] resolved = resolve(name, null);
        if (resolved == null || resolved.length < 2) return location;

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
}
