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
    private final Map<String, DailyVehicleRecord> vehiclesByInstanceId = new LinkedHashMap<>();

    private LocalDate businessDate = LocalDate.now(zoneId);
    private Instant windowStartedAt = Instant.now();
    private Instant lastUpdatedAt;

    public synchronized void applySnapshot(List<NormalizedTownRoadOrder> orders) {
        rollBusinessDateIfNeeded();
        if (orders == null || orders.isEmpty()) {
            return;
        }

        boolean changed = false;
        for (NormalizedTownRoadOrder order : orders) {
            if (order == null || order.vehicle() == null || order.instanceId() == null
                    || order.instanceId().isBlank()) {
                continue;
            }

            String orderId = firstNonBlank(order.orderId(), order.lineId(), order.instanceId());
            boolean cancelled = order.deleted() || isCancelled(order.status());
            DailyVehicleRecord previous = vehiclesByInstanceId.get(order.instanceId());
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
                vehiclesByInstanceId.put(order.instanceId(), next);
                changed = true;
            }
        }

        if (changed) {
            lastUpdatedAt = Instant.now();
        }
    }

    public synchronized DailyOrderStatistics snapshot() {
        rollBusinessDateIfNeeded();

        double deliveryTotalTons = 0;
        int dispatchedVehicleCount = 0;
        Map<String, Boolean> orders = new LinkedHashMap<>();
        long arrivedVehicleCount = 0;

        for (DailyVehicleRecord vehicle : vehiclesByInstanceId.values()) {
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
        vehiclesByInstanceId.clear();
        windowStartedAt = Instant.now();
        lastUpdatedAt = null;
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

    private record DailyVehicleRecord(
            String orderId,
            double cargoWeightTons,
            boolean arrived,
            boolean cancelled
    ) {
    }

    public record DailyOrderStatistics(
            String businessDate,
            double deliveryTotalTons,
            int dispatchedVehicleCount,
            int totalOrderCount,
            long arrivedVehicleCount,
            String windowStartedAt,
            String lastUpdatedAt
    ) {
    }
}
