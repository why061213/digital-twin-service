package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Frozen production-like regression case for the RM2 vehicle shown on 2026-07-27. */
class YueE55170SnapshotReplayTest {
    private static final String FIXTURE =
            "/fixtures/vehicle-order-chain/yue-e-55170-2026-07-27.json";
    private static final String CURRENT_INSTANCE =
            "CY0220260727012|CY0220260727012-1|粤E55170";
    private static final String HISTORICAL_ORDER = "CY0220260722068";

    @Test
    void frozenEvidenceDistinguishesHistoryFromTheCurrentTrip() throws IOException {
        FrozenCase frozen = loadFixture();

        assertThat(frozen.vehicleKey()).isEqualTo("粤E55170");
        assertThat(frozen.history()).extracting(HistoryObservation::orderId)
                .contains(HISTORICAL_ORDER, "CY0220260727012");
        assertThat(frozen.capturedOutcome().orderInstanceIds()).containsExactly(CURRENT_INSTANCE);
        assertThat(frozen.capturedOutcome().orderInstanceIds())
                .noneMatch(instanceId -> instanceId.startsWith(HISTORICAL_ORDER + "|"));
        assertThat(frozen.capturedOutcome().decision()).isEqualTo("EN_ROUTE_TO_DELIVERY");
        assertThat(frozen.capturedOutcome().tripPhase()).isEqualTo("LINEHAUL");
        assertThat(frozen.capturedOutcome().targetAction()).isEqualTo("DELIVERY");
    }

    @Test
    void replayShowsExactlyWhatTheTripRuntimeDoesToTheCurrentOrder() throws IOException {
        FrozenCase frozen = loadFixture();
        FrozenStoredOrder input = frozen.currentStoredOrder();
        VehicleOrderChainStore.StoredOrder stored = new VehicleOrderChainStore.StoredOrder(
                input.key(), input.orderId(), input.routeKey(), frozen.vehicleKey(), "OTHER",
                input.firstObservedAtMs(), input.lastObservedAtMs(), input.record());

        VehicleTripRuntimeService service = new VehicleTripRuntimeService(mock(VehicleOrderChainStore.class));
        VehicleTripRuntimeService.VehicleTripRuntime trip = service.reconcile(List.of(stored)).get(0);

        assertThat(trip.vehicleKey()).isEqualTo(frozen.vehicleKey());
        assertThat(trip.orderInstanceIds()).containsExactly(CURRENT_INSTANCE);
        assertThat(trip.orderMembers().get(CURRENT_INSTANCE))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.CONFIRMED);
        assertThat(trip.onboardOrderIds()).containsExactly(CURRENT_INSTANCE);
        assertThat(trip.pendingPickupOrderIds()).isEmpty();
        assertThat(trip.pendingDeliveryOrderIds()).containsExactly(CURRENT_INSTANCE);
        assertThat(trip.phase()).isEqualTo(VehicleTripRuntimeService.TripPhase.LINEHAUL);
        assertThat(service.currentTargetStop(trip).action())
                .isEqualTo(VehicleTripTopologyService.StopAction.DELIVERY);
        assertThat(trip.orderInstanceIds())
                .noneMatch(instanceId -> instanceId.startsWith(HISTORICAL_ORDER + "|"));
    }

    private FrozenCase loadFixture() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(FIXTURE)) {
            assertThat(input).as("fixture %s", FIXTURE).isNotNull();
            return new ObjectMapper().readValue(input, FrozenCase.class);
        }
    }

    private record FrozenCase(
            int schemaVersion,
            String caseId,
            String capturedAt,
            String vehicleKey,
            String providerVehicleId,
            double[] currentPosition,
            List<HistoryObservation> history,
            FrozenStoredOrder currentStoredOrder,
            CapturedOutcome capturedOutcome
    ) {}

    private record HistoryObservation(
            String orderId,
            String from,
            String to,
            String observedAt,
            String status
    ) {}

    private record FrozenStoredOrder(
            String key,
            String orderId,
            String routeKey,
            long firstObservedAtMs,
            long lastObservedAtMs,
            ExternalOrderRecord record
    ) {}

    private record CapturedOutcome(
            String decision,
            String reason,
            boolean groupEligible,
            String tripId,
            String runtimeLineId,
            String tripPhase,
            int planVersion,
            List<String> orderInstanceIds,
            List<String> onboardOrderIds,
            List<String> pendingPickupOrderIds,
            List<String> pendingDeliveryOrderIds,
            List<String> completedOrderIds,
            String targetAction,
            String targetDestination
    ) {}
}
