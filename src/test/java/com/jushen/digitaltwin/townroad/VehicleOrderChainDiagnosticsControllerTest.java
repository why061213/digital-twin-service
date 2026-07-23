package com.jushen.digitaltwin.townroad;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VehicleOrderChainDiagnosticsControllerTest {
    @Test
    void returnsStoreTransitMetricsWithoutMutation() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        VehicleOrderChainStore.TransitMetrics expected = new VehicleOrderChainStore.TransitMetrics(
                "2026-07-23T01:00:00Z", "store", 1, 0, 1, 0, 0,
                new VehicleOrderChainStore.IntervalStatistics(null, null, null, null, null, null), List.of());
        when(store.transitMetrics()).thenReturn(expected);
        VehicleTripRuntimeService tripRuntimeService = mock(VehicleTripRuntimeService.class);

        VehicleOrderChainStore.TransitMetrics actual =
                new VehicleOrderChainDiagnosticsController(store, tripRuntimeService).transitMetrics();

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void exposesCurrentTripRuntimeWithoutMutation() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        VehicleTripRuntimeService tripRuntimeService = mock(VehicleTripRuntimeService.class);
        when(tripRuntimeService.currentTrips()).thenReturn(List.of());

        assertThat(new VehicleOrderChainDiagnosticsController(store, tripRuntimeService).trips()).isEmpty();
    }
}
