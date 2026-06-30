package com.jushen.digitaltwin.model;

public record InventorySnapshot(
        int rawMaterial,
        int semiFinished,
        int finishedGoods,
        int warningCount,
        double turnoverRate
) {
}
