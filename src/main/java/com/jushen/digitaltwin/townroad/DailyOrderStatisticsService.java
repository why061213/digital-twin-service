package com.jushen.digitaltwin.townroad;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DailyOrderStatisticsService {

    private final ZoneId zoneId = ZoneId.systemDefault();
    private final Map<String, DailyVehicleRecord> vehiclesByLineId = new LinkedHashMap<>();

    private LocalDate businessDate = LocalDate.now(zoneId);
    private Instant windowStartedAt = Instant.now();
    private Instant lastUpdatedAt;

    public synchronized void accept(List<ExternalOrderRecord> records) {
        rollBusinessDateIfNeeded();
        if (records == null || records.isEmpty()) {
            return;
        }

        for (ExternalOrderRecord record : records) {
            if (record == null || record.vehicle() == null) {
                continue;
            }

            String lineId = firstNonBlank(
                    record.lineId(),
                    record.vehicle().carId(),
                    record.vehicle().plate()
            );
            if (lineId == null) {
                continue;
            }

            String orderId = firstNonBlank(record.orderId(), record.lineId(), lineId);
            boolean cancelled = Boolean.TRUE.equals(record.deleted()) || isCancelled(record.status());
            vehiclesByLineId.put(lineId, new DailyVehicleRecord(
                    orderId,
                    toTons(record.vehicle().cargoWeight(), record.vehicle().cargoUnit()),
                    isCompleted(record.status()),
                    cancelled
            ));
        }

        lastUpdatedAt = Instant.now();
    }

    public synchronized DailyOrderStatistics snapshot() {
        rollBusinessDateIfNeeded();

        double deliveryTotalTons = 0;
        int dispatchedVehicleCount = 0;
        Map<String, Boolean> orderCompletion = new LinkedHashMap<>();

        for (DailyVehicleRecord vehicle : vehiclesByLineId.values()) {
            if (vehicle.cancelled()) {
                continue;
            }
            deliveryTotalTons += vehicle.cargoWeightTons();
            dispatchedVehicleCount++;
            orderCompletion.merge(vehicle.orderId(), vehicle.completed(), Boolean::logicalAnd);
        }

        long completedOrderCount = orderCompletion.values().stream()
                .filter(Boolean.TRUE::equals)
                .count();

        return new DailyOrderStatistics(
                businessDate.toString(),
                roundToTwoDecimals(deliveryTotalTons),
                dispatchedVehicleCount,
                orderCompletion.size(),
                completedOrderCount,
                windowStartedAt.toString(),
                lastUpdatedAt == null ? null : lastUpdatedAt.toString()
        );
    }

    private void rollBusinessDateIfNeeded() {
        LocalDate today = LocalDate.now(zoneId);
        if (today.equals(businessDate)) {
            return;
        }
        businessDate = today;
        vehiclesByLineId.clear();
        windowStartedAt = Instant.now();
        lastUpdatedAt = null;
    }

    private boolean isCompleted(String status) {
        String normalized = normalize(status);
        return normalized.contains("完成")
                || normalized.contains("签收")
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

    private record DailyVehicleRecord(
            String orderId,
            double cargoWeightTons,
            boolean completed,
            boolean cancelled
    ) {
    }

    public record DailyOrderStatistics(
            String businessDate,
            double deliveryTotalTons,
            int dispatchedVehicleCount,
            int totalOrderCount,
            long completedOrderCount,
            String windowStartedAt,
            String lastUpdatedAt
    ) {
    }
}
