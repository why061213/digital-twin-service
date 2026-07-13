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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 从外部订单的 province/city/district/name 字段中补全经纬度。
 *
 * 查找优先级：
 *   1. 原始 coords（已有）
 *   2. LocalCoordDb 精确库
 *   3. 高德 API 精确查询 → 成功自动写入 LocalCoordDb
 *   4. district-coordinates.json 粗粒度兜底（区县→市→省）
 *   5. 最终失败 → skippedNotRenderable
 */
@Component
public class TownRoadCoordinateResolver {

    private static final Logger log = LoggerFactory.getLogger(TownRoadCoordinateResolver.class);

    private static final java.util.Set<String> MUNICIPALITIES = java.util.Set.of(
            "北京市", "天津市", "上海市", "重庆市"
    );

    private final ObjectMapper objectMapper;
    private final LocalCoordDb localCoordDb;
    private final AmapGeocodeClient amapClient;

    private Map<String, double[]> nameToCoords = Map.of();

    /** 统计：local / amap成功 / amap失败 / fallback / 总调用 */
    private final AtomicInteger statLocal = new AtomicInteger(0);
    private final AtomicInteger statAmapOk = new AtomicInteger(0);
    private final AtomicInteger statAmapFail = new AtomicInteger(0);
    private final AtomicInteger statFallback = new AtomicInteger(0);
    private final AtomicInteger statTotal = new AtomicInteger(0);

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
                nameToCoords = objectMapper.readValue(in,
                        new TypeReference<LinkedHashMap<String, double[]>>() {});
            }
            log.info("[TownRoadCoord] loaded {} entries from district-coordinates.json (兜底)", nameToCoords.size());
        } catch (Exception e) {
            log.warn("[TownRoadCoord] district-coordinates.json 加载失败: {}", e.getMessage());
            nameToCoords = Map.of();
        }

        log.info("[TownRoadCoord] ✅ 2026-07-10 新版解析器已启动！查找顺序: LocalCoordDb({}) → 高德API → 旧库({})",
                localCoordDb.size(), nameToCoords.size());
    }

    /**
     * 返回解析后的 Location，补全了 coords。
     */
    public ExternalOrderRecord.Location resolveLocation(ExternalOrderRecord.Location location) {
        statTotal.incrementAndGet();

        if (location == null) return null;
        if (hasCoords(location.coords())) return location;

        String province = trimToNull(location.province());
        String city = trimToNull(location.city());
        String district = trimToNull(location.district());
        String name = trimToNull(location.name());

        if (province == null && name == null) return location;

        // 直辖市："市辖区"/"县"是虚层级
        if (province != null && MUNICIPALITIES.contains(province)) {
            if ("市辖区".equals(city) || "县".equals(city)) city = null;
        }

        double[] resolved;

        // ==== 1. LocalCoordDb ====
        resolved = tryLocalDb(province, city, district, name);
        if (resolved != null) {
            statLocal.incrementAndGet();
            return buildLocation(location, resolved);
        }

        // ==== 2. 高德 API ====
        String q = name != null ? name : (district != null ? district : "");
        resolved = amapClient.geocode(province, city, district, q);
        if (resolved != null) {
            statAmapOk.incrementAndGet();
            return buildLocation(location, resolved);
        }
        statAmapFail.incrementAndGet();

        // ==== 3. 旧库兜底（已禁用，让高德失败走 skippedNotRenderable） ====
        // resolved = tryFallbackDb(province, city, district, name);
        // if (resolved != null) {
        //     statFallback.incrementAndGet();
        //     return buildLocation(location, resolved);
        // }

        return location;
    }

    private double[] tryLocalDb(String p, String c, String d, String n) {
        if (p != null && n != null) {
            double[] r = localCoordDb.resolve(p, c, d, n);
            if (r != null) return r;
        }
        if (p != null && d != null) {
            double[] r = localCoordDb.resolve(p, c, d, d);
            if (r != null) return r;
        }
        if (n != null) return localCoordDb.get(n);
        return null;
    }

    private double[] tryFallbackDb(String p, String c, String d, String n) {
        if (p != null && d != null) {
            String key = (c != null) ? p + c + d : p + d;
            double[] r = nameToCoords.get(key);
            if (r != null) return r;
        }
        if (p != null && c != null) {
            double[] r = nameToCoords.get(p + c);
            if (r != null) return r;
        }
        if (c != null) {
            double[] r = nameToCoords.get(c);
            if (r != null) return r;
        }
        if (p != null) {
            double[] r = nameToCoords.get(p);
            if (r != null) return r;
        }
        if (n != null) return nameToCoords.get(n);
        return null;
    }

    private ExternalOrderRecord.Location buildLocation(ExternalOrderRecord.Location loc, double[] coords) {
        return new ExternalOrderRecord.Location(
                loc.name(), loc.province(), loc.city(), loc.district(), loc.adcode(), coords
        );
    }

    public String getStatsAndReset() {
        int local = statLocal.getAndSet(0);
        int amapOk = statAmapOk.getAndSet(0);
        int amapFail = statAmapFail.getAndSet(0);
        int fallback = statFallback.getAndSet(0);
        int total = statTotal.getAndSet(0);
        return String.format("[TownRoadCoord] 坐标解析统计: 总调用=%d local=%d amap成功=%d amap失败=%d fallback=%d",
                total, local, amapOk, amapFail, fallback);
    }

    private boolean hasCoords(double[] coords) {
        return coords != null && coords.length >= 2
               && Double.isFinite(coords[0]) && Double.isFinite(coords[1]);
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
