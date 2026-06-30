package com.jushen.digitaltwin.model;

public record KpiSnapshot(
        double outputValue,
        double orderCompletionRate,
        double equipmentAvailability,
        double qualityPassRate,
        int activeOrders,
        int onlineVehicles
) {
}
