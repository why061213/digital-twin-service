package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
 */
@Component
public class TownRoadCoordinateResolver {

    private static final Pattern NAME_PATTERN = Pattern.compile(
            "^([\\u4e00-\\u9fa5]{2,4}?(?:省|自治区|特别行政区|市))"
                    + "([\\u4e00-\\u9fa5]{2,6}?(?:市|自治州|地区|盟|区))?"
                    + "([\\u4e00-\\u9fa5]{2,6}?(?:县|区|市|旗|自治县))?"
                    + "(.*)$"
    );

    private final ObjectMapper objectMapper;

    /** 名称 → [lng, lat] */
    private Map<String, double[]> nameToCoords = Map.of();

    public TownRoadCoordinateResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
            System.out.println("[TownRoadCoord] loaded " + nameToCoords.size() + " location coordinates");
        } catch (Exception e) {
            System.err.println("[TownRoadCoord] failed to load district-coordinates.json: " + e.getMessage());
            nameToCoords = Map.of();
        }
    }

    /**
     * 尝试从名称中解析省市区并查坐标。
     * @param name 原始地址名称
     * @param existingProvince 已有的省份（可能来自 adcode 等其他字段）
     * @return [lng, lat] 或 null
     */
    public double[] resolve(String name, String existingProvince) {
        if (name == null || name.isBlank()) return null;

        Matcher m = NAME_PATTERN.matcher(name.trim());
        if (!m.matches()) {
            // 尝试直接用名称查库
            return nameToCoords.get(name.trim());
        }

        String province = m.group(1);  // 山东省
        String city = m.group(2);      // 滨州市
        String district = m.group(3);  // 无棣县

        // 1. 省+市+区
        if (district != null) {
            String fullPath = province + city + district;
            double[] coords = nameToCoords.get(fullPath);
            if (coords != null) return coords;
        }

        // 2. 省+市
        if (city != null) {
            String pc = province + city;
            double[] coords = nameToCoords.get(pc);
            if (coords != null) return coords;
        }

        // 3. 只用城市名（跨省情况下可能有用）
        if (city != null) {
            double[] coords = nameToCoords.get(city);
            if (coords != null) return coords;
        }

        // 4. 省（使用已有的 province 信息）
        if (existingProvince != null && !existingProvince.isBlank()) {
            double[] coords = nameToCoords.get(existingProvince);
            if (coords != null) return coords;
        }

        // 5. 直接用 province 从 name 中查
        double[] coords = nameToCoords.get(province);
        if (coords != null) return coords;

        // 6. 用原始名称逐段尝试
        return nameToCoords.get(name.trim());
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
