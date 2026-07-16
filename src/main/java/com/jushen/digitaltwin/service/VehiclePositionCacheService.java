package com.jushen.digitaltwin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 车辆位置缓存服务：
 * - 缓存外部接口返回的真实车辆位置
 * - 单车查询和批量查询均只读缓存，不穿透外部接口
 * - 超时未更新标记 stale
 * - 批量刷新由调用方（RoutePushService）触发
 */
@Service
public class VehiclePositionCacheService {

    private static final Logger log = LoggerFactory.getLogger(VehiclePositionCacheService.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final String externalPositionUrl;
    private final int batchSize;
    private final int requestTimeoutMs;
    private final long staleAfterMs;
    private final boolean positionRefreshEnabled;

    /** 位置缓存：key = lineId */
    private final ConcurrentHashMap<String, PositionSnapshot> cache = new ConcurrentHashMap<>();

    public VehiclePositionCacheService(
            ObjectMapper objectMapper,
            @Value("${dashboard.route.external-position-url:}") String externalPositionUrl,
            @Value("${dashboard.route.external-position-batch-size:50}") int batchSize,
            @Value("${dashboard.route.position-refresh.enabled:true}") boolean positionRefreshEnabled,
            @Value("${dashboard.route.position-refresh.batch-size:50}") int refreshBatchSize,
            @Value("${dashboard.route.position-refresh.request-timeout-ms:10000}") int requestTimeoutMs,
            @Value("${dashboard.route.position-refresh.stale-after-ms:180000}") long staleAfterMs
    ) {
        this.objectMapper = objectMapper;
        this.externalPositionUrl = externalPositionUrl;
        this.batchSize = refreshBatchSize > 0 ? refreshBatchSize : batchSize;
        this.requestTimeoutMs = requestTimeoutMs;
        this.staleAfterMs = staleAfterMs;
        this.positionRefreshEnabled = positionRefreshEnabled && externalPositionUrl != null && !externalPositionUrl.isBlank();
        log.info("[PositionCache] initialized: refreshEnabled={}, batchSize={}, timeoutMs={}, staleAfterMs={}",
                this.positionRefreshEnabled, this.batchSize, this.requestTimeoutMs, this.staleAfterMs);
    }

    public boolean isEnabled() {
        return positionRefreshEnabled;
    }

    // ---------------------------------------------------------------
    // 缓存读取（只读，不穿透外部接口）
    // ---------------------------------------------------------------

    public PositionSnapshot getPosition(String lineId) {
        PositionSnapshot snapshot = cache.get(lineId);
        if (snapshot == null) return null;
        if (isStale(snapshot)) return snapshot.markStale();
        return snapshot;
    }

    public List<PositionSnapshot> getPositions(Collection<String> lineIds) {
        List<PositionSnapshot> results = new ArrayList<>();
        Set<String> deduped = new LinkedHashSet<>(lineIds);
        for (String lineId : deduped) {
            PositionSnapshot snapshot = getPosition(lineId);
            if (snapshot != null) results.add(snapshot);
        }
        return results;
    }

    public boolean isStale(PositionSnapshot snapshot) {
        return isStale(snapshot, staleAfterMs);
    }

    /** 使用自定义过期阈值判断是否过期 */
    public boolean isStale(PositionSnapshot snapshot, long customStaleAfterMs) {
        return System.currentTimeMillis() - snapshot.fetchedAt().toEpochMilli() > customStaleAfterMs;
    }

    /**
     * 根据路线特征动态计算位置校准间隔。
     *
     * @param routeLengthKm 路线总长度（公里）
     * @param progress      当前运输进度 0.0~1.0
     * @param speedKmh      当前速度（km/h）
     * @return 建议的校准间隔（毫秒），范围 15s ~ 5min
     */
    public long calibrationIntervalMs(double routeLengthKm, double progress, double speedKmh) {
        // 基础间隔：短途高频，长途低频（短途大概率城市道路）
        long base = routeLengthKm < 50 ? 60_000      // <50km：每 60s
                  : routeLengthKm < 200 ? 120_000    // 50~200km：每 120s
                  : 180_000;                          // >200km：每 180s

        // 起止阶段（前 25% 和后 25%）更可能在城市，加倍频率
        if (progress < 0.25 || progress > 0.75) {
            base = (long)(base * 0.5);
        }

        // 速度越慢越像城市路况，加倍频率
        if (speedKmh < 30) {
            base = (long)(base * 0.2);
        } else if (speedKmh < 60) {
            base = (long)(base * 0.75);
        }

        return Math.max(15_000, Math.min(base, 300_000)); // 夹钳 15s ~ 5min
    }

    public int size() { return cache.size(); }

    /** 标记所有超时未更新的缓存为 stale。返回标记数量。 */
    public int markStaleEntries() {
        long threshold = System.currentTimeMillis() - staleAfterMs;
        int count = 0;
        for (Map.Entry<String, PositionSnapshot> entry : cache.entrySet()) {
            if (entry.getValue().fetchedAt().toEpochMilli() < threshold && !entry.getValue().stale()) {
                cache.put(entry.getKey(), entry.getValue().markStale());
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------
    // 缓存写入
    // ---------------------------------------------------------------

    public void putPosition(String lineId, PositionSnapshot snapshot) {
        cache.put(lineId, snapshot);
    }

    public void removePosition(String lineId) {
        cache.remove(lineId);
    }

    public void removePositions(Collection<String> lineIds) {
        lineIds.forEach(cache::remove);
    }

    /**
     * 批量刷新：收集活跃运输中的 lineId→vehicleId 映射，按 vehicleId 去重后批量请求外部接口。
     * @param tokenSupplier 提供 access token
     * @param vehicleToLineIds vehicleId → 关联的 lineId 集合
     * @return 刷新统计信息
     */
    public Map<String, Object> runBatchRefresh(
            Supplier<String> tokenSupplier,
            Map<String, Set<String>> vehicleToLineIds
    ) {
        if (!positionRefreshEnabled) {
            return Map.of("skipped", true, "reason", "disabled");
        }
        if (vehicleToLineIds.isEmpty()) {
            return Map.of("skipped", true, "reason", "no-active-vehicles");
        }

        List<String> uniqueVehicleIds = new ArrayList<>(vehicleToLineIds.keySet());
        int totalVehicles = uniqueVehicleIds.size();
        int batchCount = (int) Math.ceil((double) totalVehicles / batchSize);

        log.info("[PositionCache] refresh starting: lineCount={}, uniqueVehicleCount={}, batchCount={}",
                vehicleToLineIds.values().stream().mapToInt(Set::size).sum(), totalVehicles, batchCount);

        int requestedCount = 0;
        int returnedCount = 0;
        int missingCount = 0;
        int cacheUpdatedCount = 0;
        int failedBatchCount = 0;
        long maxBatchCostMs = 0;

        for (int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
            int fromIndex = batchIndex * batchSize;
            int toIndex = Math.min(fromIndex + batchSize, totalVehicles);
            List<String> batchVehicleIds = uniqueVehicleIds.subList(fromIndex, toIndex);
            requestedCount += batchVehicleIds.size();

            long batchStart = System.currentTimeMillis();
            try {
                Map<String, ProviderPositionResult> batchResults = fetchPositionsByVehicleIds(
                        tokenSupplier.get(), batchVehicleIds);
                long batchCost = System.currentTimeMillis() - batchStart;
                if (batchCost > maxBatchCostMs) maxBatchCostMs = batchCost;

                returnedCount += batchResults.size();
                missingCount += (batchVehicleIds.size() - batchResults.size());

                for (Map.Entry<String, ProviderPositionResult> entry : batchResults.entrySet()) {
                    String vehicleId = entry.getKey();
                    ProviderPositionResult posResult = entry.getValue();
                    Set<String> lineIds = vehicleToLineIds.get(vehicleId);
                    if (lineIds == null || lineIds.isEmpty()) continue;

                    for (String lineId : lineIds) {
                        cache.put(lineId, PositionSnapshot.fromProvider(
                                lineId, vehicleId, posResult.vehicleName, posResult.plate,
                                posResult.lng, posResult.lat, posResult.speedKmh));
                        cacheUpdatedCount++;
                    }
                }
            } catch (Exception e) {
                failedBatchCount++;
                log.warn("[PositionCache] batch {} failed: {} vehicles, error={}",
                        batchIndex, batchVehicleIds.size(), e.getMessage());
            }
        }

        int staleMarked = markStaleEntries();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requested", requestedCount);
        summary.put("returned", returnedCount);
        summary.put("missing", missingCount);
        summary.put("cacheUpdated", cacheUpdatedCount);
        summary.put("staleMarked", staleMarked);
        summary.put("failedBatches", failedBatchCount);
        summary.put("maxBatchCostMs", maxBatchCostMs);
        summary.put("cacheSize", cache.size());

        log.info("[PositionCache] refresh summary: {}", summary);
        return summary;
    }

    // ---------------------------------------------------------------
    // 外部接口批量查询
    // ---------------------------------------------------------------

    public Map<String, ProviderPositionResult> fetchPositionsByVehicleIds(String token, List<String> vehicleIds)
            throws IOException, InterruptedException {
        if (vehicleIds.isEmpty()) return Map.of();
        if (externalPositionUrl == null || externalPositionUrl.isBlank()) return Map.of();

        String carIds = String.join(",", vehicleIds);
        String url = externalPositionUrl + "/video/webapi/location/get-location-use-carids";

        Map<String, Object> body = Map.of("car_ids", carIds);
        String json = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("CarID API returned status " + response.statusCode());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
        if (!(result.get("code") instanceof Number code) || code.intValue() != 200) {
            throw new IOException("CarID API code not 200: " + result);
        }

        Map<String, Object> dataBlock = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> vehicleList = dataBlock == null
                ? List.of()
                : (List<Map<String, Object>>) dataBlock.get("data");
        if (vehicleList == null || vehicleList.isEmpty()) {
            log.info("[PositionCache] API returned empty data for {} vehicleIds", vehicleIds.size());
            return Map.of();
        }

        // 解析所有返回车辆，按 vehicle_id 建立索引
        Map<String, ProviderPositionResult> resultByVehicleId = new LinkedHashMap<>();
        int invalidCount = 0;
        for (Map<String, Object> vehicle : vehicleList) {
            String vehicleId = stringValue(vehicle.get("vehicle_id"));
            if (vehicleId == null) { invalidCount++; continue; }
            double lng, lat;
            try {
                lng = Double.parseDouble(String.valueOf(vehicle.get("lng")));
                lat = Double.parseDouble(String.valueOf(vehicle.get("lat")));
            } catch (NumberFormatException e) { invalidCount++; continue; }
            double speed = 0;
            try { speed = Double.parseDouble(String.valueOf(vehicle.get("speed"))); }
            catch (NumberFormatException ignored) {}

            resultByVehicleId.put(vehicleId, new ProviderPositionResult(
                    vehicleId, stringValue(vehicle.get("vehicle_name")),
                    stringValue(vehicle.get("plate")), lng, lat, speed));
        }

        log.info("[PositionCache] batch: requested={}, returned={}, missing={}, invalid={}",
                vehicleIds.size(), resultByVehicleId.size(),
                vehicleIds.size() - resultByVehicleId.size(), invalidCount);
        return resultByVehicleId;
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public record ProviderPositionResult(
            String vehicleId, String vehicleName, String plate,
            double lng, double lat, double speedKmh
    ) {}
}
