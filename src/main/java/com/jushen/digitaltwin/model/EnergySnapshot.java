package com.jushen.digitaltwin.model;

public record EnergySnapshot(
        double electricityKwh,
        double waterTons,
        double gasCubicMeters,
        double carbonKg,
        double loadPercent
) {
}
