package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DailyOrderStatisticsService {

    private static final Logger log = LoggerFactory.getLogger(DailyOrderStatisticsService.class);
    private static final int CACHE_VERSION = 1;

    private final ZoneId zoneId = ZoneId.systemDefault();
    private final ObjectMapper objectMapper;
    private final boolean cacheEnabled;
    private final Path cachePath;
    private final int retentionDays;
    private final Map<LocalDate, DailyBucket> bucketsByDate = new LinkedHashMap<>();

    private LocalDate businessDate = LocalDate.now(zoneId);

    public DailyOrderStatisticsService(
            ObjectMapper objectMapper,
            @Value("${dashboard.daily-statistics.cache-enabled:true}") boolean cacheEnabled,
            @Value("${dashboard.daily-statistics.cache-path:runtime-data/daily-order-statistics.json}") String cachePath,
            @Value("${dashboard.daily-statistics.retention-days:3}") int retentionDays
    ) {
        this.objectMapper = objectMapper;
        this.cacheEnabled = cacheEnabled && cachePath != null && !cachePath.isBlank();
        this.cachePath = this.cacheEnabled ? Path.of(cachePath).toAbsolutePath().normalize() : null;
        this.retentionDays = Math.max(1, retentionDays);
        log.info("[DailyStatistics] cache configuration: enabled={}, path={}, retentionDays={}",
                this.cacheEnabled, this.cachePath, this.retentionDays);
        loadCache();
        currentBucket();
        cleanupExpiredBuckets();
        persistCache();
    }

    public synchronized void applySnapshot(List<NormalizedTownRoadOrder> orders) {
        rollBusinessDateIfNeeded();
        if (orders == null || orders.isEmpty()) {
            return;
        }

        DailyBucket bucket = currentBucket();
        boolean changed = false;
        for (NormalizedTownRoadOrder order : orders) {
            if (order == null || order.vehicle() == null || order.instanceId() == null
                    || order.instanceId().isBlank()) {
                continue;
            }

            String orderId = firstNonBlank(order.orderId(), order.lineId(), order.instanceId());
            DailyVehicleRecord previous = bucket.vehiclesByInstanceId().get(order.instanceId());
            boolean currentlyCancelled = order.deleted() || isCancelled(order.status());
            // Daily KPIs are cumulative: a vehicle already dispatched today must not disappear
            // merely because a later provider snapshot marks the order cancelled or deleted.
            boolean cancelled = currentlyCancelled && (previous == null || previous.cancelled());
            boolean arrived = isCompleted(order.status()) || previous != null && previous.arrived();
            double cargoWeightTons = toTons(order.vehicle().cargoWeight(), order.vehicle().cargoUnit());
            if (cargoWeightTons <= 0 && previous != null) {
                cargoWeightTons = previous.cargoWeightTons();
            }
            DailyVehicleRecord next = new DailyVehicleRecord(
                    orderId,
                    cargoWeightTons,
                    arrived,
                    cancelled
            );
            if (!next.equals(previous)) {
                bucket.vehiclesByInstanceId().put(order.instanceId(), next);
                changed = true;
            }
        }

        if (changed) {
            bucket.lastUpdatedAt(Instant.now());
            bumpRevision(bucket);
            persistCache();
        }
    }

    public synchronized DailyOrderStatistics snapshot() {
        rollBusinessDateIfNeeded();
        DailyBucket bucket = currentBucket();

        double deliveryTotalTons = 0;
        int dispatchedVehicleCount = 0;
        Map<String, Boolean> orders = new LinkedHashMap<>();
        long arrivedVehicleCount = 0;

        for (DailyVehicleRecord vehicle : bucket.vehiclesByInstanceId().values()) {
            if (vehicle.cancelled()) {
                continue;
            }
            deliveryTotalTons += vehicle.cargoWeightTons();
            dispatchedVehicleCount++;
            orders.put(vehicle.orderId(), Boolean.TRUE);
            if (vehicle.arrived()) {
                arrivedVehicleCount++;
            }
        }

        return new DailyOrderStatistics(
                businessDate.toString(),
                roundToTwoDecimals(deliveryTotalTons),
                dispatchedVehicleCount,
                orders.size(),
                arrivedVehicleCount,
                bucket.revision(),
                bucket.windowStartedAt().toString(),
                bucket.lastUpdatedAt() == null ? null : bucket.lastUpdatedAt().toString()
        );
    }

    private DailyBucket currentBucket() {
        return bucketsByDate.computeIfAbsent(
                businessDate,
                ignored -> new DailyBucket(
                        new LinkedHashMap<>(),
                        Instant.now(),
                        null,
                        System.currentTimeMillis()
                )
        );
    }

    private void rollBusinessDateIfNeeded() {
        LocalDate today = LocalDate.now(zoneId);
        if (today.equals(businessDate)) {
            return;
        }
        businessDate = today;
        currentBucket();
        cleanupExpiredBuckets();
        persistCache();
    }

    private void cleanupExpiredBuckets() {
        LocalDate oldestRetainedDate = businessDate.minusDays(retentionDays - 1L);
        bucketsByDate.keySet().removeIf(date -> date.isBefore(oldestRetainedDate) || date.isAfter(businessDate));
    }

    private void bumpRevision(DailyBucket bucket) {
        bucket.revision(Math.max(bucket.revision() + 1, System.currentTimeMillis()));
    }

    private void loadCache() {
        if (!cacheEnabled || cachePath == null || !Files.isRegularFile(cachePath)) {
            return;
        }
        try {
            CacheFile cache = objectMapper.readValue(cachePath.toFile(), CacheFile.class);
            if (cache == null || cache.version() != CACHE_VERSION || cache.days() == null) {
                log.warn("[DailyStatistics] ignored unsupported cache: path={}", cachePath);
                return;
            }

            LocalDate oldestRetainedDate = businessDate.minusDays(retentionDays - 1L);
            int restoredVehicles = 0;
            for (Map.Entry<String, CachedDay> entry : cache.days().entrySet()) {
                LocalDate date;
                try {
                    date = LocalDate.parse(entry.getKey());
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (date.isBefore(oldestRetainedDate) || date.isAfter(businessDate)) {
                    continue;
                }

                CachedDay cachedDay = entry.getValue();
                if (cachedDay == null) continue;
                Map<String, DailyVehicleRecord> vehicles = new LinkedHashMap<>();
                if (cachedDay.vehiclesByInstanceId() != null) {
                    vehicles.putAll(cachedDay.vehiclesByInstanceId());
                }
                DailyBucket bucket = new DailyBucket(
                        vehicles,
                        parseInstant(cachedDay.windowStartedAt(), Instant.now()),
                        parseInstant(cachedDay.lastUpdatedAt(), null),
                        cachedDay.revision() > 0 ? cachedDay.revision() : System.currentTimeMillis()
                );
                bucketsByDate.put(date, bucket);
                restoredVehicles += vehicles.size();
            }
            log.info("[DailyStatistics] cache restored: path={}, days={}, vehicles={}",
                    cachePath, bucketsByDate.size(), restoredVehicles);
        } catch (Exception error) {
            log.warn("[DailyStatistics] failed to load cache: path={}, error={}", cachePath, error.getMessage());
        }
    }

    private void persistCache() {
        if (!cacheEnabled || cachePath == null) {
            return;
        }
        try {
            Files.createDirectories(cachePath.getParent());
            Map<String, CachedDay> days = new LinkedHashMap<>();
            bucketsByDate.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .forEach(entry -> {
                        DailyBucket bucket = entry.getValue();
                        days.put(entry.getKey().toString(), new CachedDay(
                                bucket.windowStartedAt().toString(),
                                bucket.lastUpdatedAt() == null ? null : bucket.lastUpdatedAt().toString(),
                                bucket.revision(),
                                new LinkedHashMap<>(bucket.vehiclesByInstanceId())
                        ));
                    });

            CacheFile cache = new CacheFile(CACHE_VERSION, retentionDays, days);
            Path temporaryPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryPath.toFile(), cache);
            try {
                Files.move(temporaryPath, cachePath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            log.warn("[DailyStatistics] failed to persist cache: path={}, error={}", cachePath, error.getMessage());
        }
    }

    private Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean isCompleted(String status) {
        String normalized = normalize(status);
        return normalized.contains("完成")
                || normalized.contains("签收")
                || normalized.contains("到达")
                || normalized.contains("送达")
                || "finished".equals(normalized)
                || "completed".equals(normalized);
    }

    private boolean isCancelled(String status) {
        String normalized = normalize(status);
        return normalized.contains("取消") || "cancelled".equals(normalized) || "canceled".equals(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private double toTons(Double weight, String unit) {
        if (weight == null || !Double.isFinite(weight) || weight <= 0) {
            return 0;
        }
        String normalizedUnit = normalize(unit);
        if (normalizedUnit.equals("kg") || normalizedUnit.contains("千克") || normalizedUnit.contains("公斤")) {
            return weight / 1_000d;
        }
        if (normalizedUnit.equals("g") || normalizedUnit.contains("克")) {
            return weight / 1_000_000d;
        }
        return weight;
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record CacheFile(
            int version,
            int retentionDays,
            Map<String, CachedDay> days
    ) {
    }

    private record CachedDay(
            String windowStartedAt,
            String lastUpdatedAt,
            long revision,
            Map<String, DailyVehicleRecord> vehiclesByInstanceId
    ) {
    }

    private record DailyVehicleRecord(
            String orderId,
            double cargoWeightTons,
            boolean arrived,
            boolean cancelled
    ) {
    }

    private static final class DailyBucket {
        private final Map<String, DailyVehicleRecord> vehiclesByInstanceId;
        private final Instant windowStartedAt;
        private Instant lastUpdatedAt;
        private long revision;

        private DailyBucket(
                Map<String, DailyVehicleRecord> vehiclesByInstanceId,
                Instant windowStartedAt,
                Instant lastUpdatedAt,
                long revision
        ) {
            this.vehiclesByInstanceId = vehiclesByInstanceId;
            this.windowStartedAt = windowStartedAt;
            this.lastUpdatedAt = lastUpdatedAt;
            this.revision = revision;
        }

        private Map<String, DailyVehicleRecord> vehiclesByInstanceId() {
            return vehiclesByInstanceId;
        }

        private Instant windowStartedAt() {
            return windowStartedAt;
        }

        private Instant lastUpdatedAt() {
            return lastUpdatedAt;
        }

        private void lastUpdatedAt(Instant value) {
            lastUpdatedAt = value;
        }

        private long revision() {
            return revision;
        }

        private void revision(long value) {
            revision = value;
        }
    }

    public record DailyOrderStatistics(
            String businessDate,
            double deliveryTotalTons,
            int dispatchedVehicleCount,
            int totalOrderCount,
            long arrivedVehicleCount,
            long revision,
            String windowStartedAt,
            String lastUpdatedAt
    ) {
    }
}
