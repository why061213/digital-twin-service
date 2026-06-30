package com.jushen.digitaltwin.model;

public record VehicleSnapshot(
        int total,
        int running,
        int idle,
        int charging,
        int fault,
        double averageSpeed
) {
}
