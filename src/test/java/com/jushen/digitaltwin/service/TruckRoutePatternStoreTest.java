package com.jushen.digitaltwin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruckRoutePatternStoreTest {
    private final java.util.List<double[]> branch = java.util.List.of(
            new double[]{113.10, 23.10}, new double[]{113.20, 23.20}, new double[]{113.30, 23.30});

    @Test
    void recognizesPatternFromTwoDistinctPlates() {
        TruckRoutePatternStore store = new TruckRoutePatternStore();
        store.recordAlternative("od-route", 0.35, branch, "粤A1", "order-1");
        store.recordAlternative("od-route", 0.36, branch, "粤B2", "order-2");

        assertThat(store.isExpected("od-route", 0.35, branch, "粤C3", "order-3")).isTrue();
    }

    @Test
    void repeatedRefreshesOfSameOrderDoNotCreateExpectedPattern() {
        TruckRoutePatternStore store = new TruckRoutePatternStore();
        for (int i = 0; i < 5; i++) {
            store.recordAlternative("od-route", 0.35, branch, "粤A1", "order-1");
        }

        assertThat(store.isExpected("od-route", 0.35, branch, "粤A1", "order-1")).isFalse();
    }
}
