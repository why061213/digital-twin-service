package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.service.PositionSnapshot;
import com.jushen.digitaltwin.service.RoutePushService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VehicleOrderEligibilityServiceTest {
    @Test
    void appliesCompletedRetentionTransportingAndLoadingRules() {
        Instant now = Instant.now();
        List<VehicleOrderChainStore.StoredOrder> orders = List.of(
                stored(record("old-completed", "粤A10001", "已完成", now.minusSeconds(31 * 60), 113.0), 1),
                stored(record("recent-completed", "粤A10002", "已完成", now.minusSeconds(10 * 60), 113.0), 2),
                stored(record("transporting", "粤A10003", "运输中", now.minusSeconds(60), 113.0), 3),
                stored(record("loading", "粤A10004", "待装载", now.minusSeconds(30), 113.0), 4)
        );
        Fixture fixture = fixture(orders);
        for (VehicleOrderChainStore.StoredOrder stored : orders) {
            String lineId = stored.orderId();
            when(fixture.routePush.providerVehicleIdForLineId(lineId)).thenReturn("provider-" + lineId);
            when(fixture.routePush.freshProviderPosition(lineId)).thenReturn(
                    PositionSnapshot.fromProvider(lineId, "provider-" + lineId, stored.vehicleKey(),
                            stored.vehicleKey(), 113.01, 23.0, 0));
        }

        VehicleOrderEligibilityService.EligibilityReport report = fixture.service.analyzeLatestVehicleOrders();

        assertThat(report.groupEligibleCount()).isEqualTo(1);
        assertThat(report.decisions()).extracting(VehicleOrderEligibilityService.VehicleDecision::orderId)
                .doesNotContain("old-completed", "recent-completed");
        assertThat(decision(report, "transporting").groupEligible()).isTrue();
        assertThat(decision(report, "loading").decision()).isEqualTo("EN_ROUTE_TO_PICKUP");
    }

    @Test
    void waitingVehicleUsesPreviousCompletedOrderAndTrajectoryToDetectDeparture() {
        Instant now = Instant.now();
        VehicleOrderChainStore.StoredOrder previous = stored(
                record("previous", "粤A20001", "已完成", now.minusSeconds(2 * 3600), 112.8), 1);
        VehicleOrderChainStore.StoredOrder current = stored(
                record("current", "粤A20001", "待装载", now.minusSeconds(60), 113.0), 2);
        Fixture fixture = fixture(List.of(previous, current));
        when(fixture.routePush.providerVehicleIdForLineId("current")).thenReturn("20001");
        when(fixture.routePush.freshProviderPosition("current")).thenReturn(
                PositionSnapshot.fromProvider("current", "20001", "粤A20001", "粤A20001",
                        113.09, 23.0, 20));
        when(fixture.trajectory.fetch(any(), any(), any())).thenReturn(
                new ProviderTrajectoryClient.TrajectoryResult(true, "ok", List.of(
                        point(now.minusSeconds(600), 113.02),
                        point(now.minusSeconds(480), 113.03),
                        point(now.minusSeconds(360), 113.05),
                        point(now.minusSeconds(240), 113.07)
                )));

        VehicleOrderEligibilityService.EligibilityReport report = fixture.service.analyzeLatestVehicleOrders();
        VehicleOrderEligibilityService.VehicleDecision decision = decision(report, "current");

        assertThat(decision.previousOrderId()).isEqualTo("previous");
        assertThat(decision.decision()).isEqualTo("DEPARTED");
        assertThat(decision.groupEligible()).isTrue();
        verify(fixture.store).recordSuspectedInTransit(current.record());
    }

    @Test
    void recordedInferredTransitRemainsGroupEligibleWithoutRepeatingTrajectoryAnalysis() {
        Instant now = Instant.now();
        VehicleOrderChainStore.StoredOrder current = stored(
                record("current", "粤A30001", "待装载", now.minusSeconds(60), 113.0), 1);
        Fixture fixture = fixture(List.of(current));
        when(fixture.routePush.providerVehicleIdForLineId("current")).thenReturn("30001");
        when(fixture.routePush.freshProviderPosition("current")).thenReturn(
                PositionSnapshot.fromProvider("current", "30001", "粤A30001", "粤A30001",
                        113.09, 23.0, 20));
        when(fixture.store.recordedTransitStatus(current.record())).thenReturn("在途-2");

        VehicleOrderEligibilityService.VehicleDecision decision = decision(
                fixture.service.analyzeLatestVehicleOrders(), "current");

        assertThat(decision.groupEligible()).isTrue();
        assertThat(decision.decision()).isEqualTo("EN_ROUTE_TO_DELIVERY");
        assertThat(decision.reason()).isEqualTo("onboard-orders-with-confirmed-delivery-ahead");
    }

    @Test
    void disabledWaitingAnalysisStillGroupsConfirmedAndRecordedTransitOnly() {
        Instant now = Instant.now();
        VehicleOrderChainStore.StoredOrder confirmed = stored(
                record("confirmed", "粤A40001", "运输中", now.minusSeconds(60), 113.0), 1);
        VehicleOrderChainStore.StoredOrder inferred = stored(
                record("inferred", "粤A40002", "待装载", now.minusSeconds(60), 113.0), 2);
        VehicleOrderChainStore.StoredOrder waiting = stored(
                record("waiting", "粤A40003", "待装载", now.minusSeconds(60), 113.0), 3);
        List<VehicleOrderChainStore.StoredOrder> orders = List.of(confirmed, inferred, waiting);
        Fixture fixture = fixture(orders, false);
        for (VehicleOrderChainStore.StoredOrder stored : orders) {
            when(fixture.routePush.providerVehicleIdForLineId(stored.orderId()))
                    .thenReturn("provider-" + stored.orderId());
            when(fixture.routePush.freshProviderPosition(stored.orderId())).thenReturn(
                    PositionSnapshot.fromProvider(stored.orderId(), "provider-" + stored.orderId(),
                            stored.vehicleKey(), stored.vehicleKey(), 113.09, 23.0, 20));
        }
        when(fixture.store.recordedTransitStatus(inferred.record())).thenReturn("在途-2");

        VehicleOrderEligibilityService.EligibilityReport report = fixture.service.analyzeLatestVehicleOrders();

        assertThat(report.groupEligibleCount()).isEqualTo(2);
        assertThat(decision(report, "confirmed").groupEligible()).isTrue();
        assertThat(decision(report, "inferred").groupEligible()).isTrue();
        assertThat(decision(report, "waiting").groupEligible()).isFalse();
        assertThat(decision(report, "waiting").reason())
                .isEqualTo("waiting-auto-classification-disabled");
        verifyNoInteractions(fixture.trajectory);
    }

    @Test
    void sameVehicleMultipleOrdersAreKeptInOneTripAndTransportingOrderBecomesAnchor() {
        Instant now = Instant.now();
        VehicleOrderChainStore.StoredOrder waiting = stored(
                record("waiting", "粤A50001", "待装载", now.minusSeconds(30), 113.0), 1);
        VehicleOrderChainStore.StoredOrder transporting = stored(
                record("transporting", "粤A50001", "运输中", now.minusSeconds(60), 113.1), 2);
        Fixture fixture = fixture(List.of(waiting, transporting));
        when(fixture.routePush.providerVehicleIdForLineId("transporting")).thenReturn("50001");
        when(fixture.routePush.freshProviderPosition("transporting")).thenReturn(
                PositionSnapshot.fromProvider("transporting", "50001", "粤A50001", "粤A50001",
                        113.2, 23.0, 30));

        VehicleOrderEligibilityService.EligibilityReport report = fixture.service.analyzeLatestVehicleOrders();

        assertThat(report.latestVehicleCount()).isEqualTo(1);
        assertThat(report.decisions()).hasSize(1);
        VehicleOrderEligibilityService.VehicleDecision decision = report.decisions().get(0);
        assertThat(decision.orderId()).isEqualTo("transporting");
        assertThat(decision.tripOrderInstanceIds()).hasSize(2);
        assertThat(decision.tripPhase()).isEqualTo(VehicleTripRuntimeService.TripPhase.COLLECTING);
        assertThat(decision.tripOrderMembers().get(waiting.key()))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.CONFIRMED);
        assertThat(decision.targetOrderInstanceId()).isEqualTo(waiting.key());
        assertThat(decision.currentLegOriginPosition()).containsExactly(113.2, 23.0);
        assertThat(decision.tripStatusText()).contains("装载点");
        assertThat(decision.tripStops()).hasSize(4);
        assertThat(decision.tripStops())
                .filteredOn(stop -> stop.action() == VehicleTripTopologyService.StopAction.DELIVERY)
                .allMatch(stop -> "#ef4444".equals(stop.markerColor()));
        assertThat(decision.groupEligible()).isTrue();
    }

    @Test
    void disabledRealPositionFilteringStillDescribesCompositeTripStructure() {
        Instant now = Instant.now();
        VehicleOrderChainStore.StoredOrder first = stored(
                record("first", "桂L91622", "运输中", now.minusSeconds(60), 113.0), 1);
        VehicleOrderChainStore.StoredOrder second = stored(
                record("second", "桂L91622", "待装载", now.minusSeconds(30), 113.1), 2);
        Fixture fixture = fixture(List.of(first, second));
        fixture.properties.setIgnoreOrdersWithoutRealPosition(false);
        when(fixture.routePush.freshProviderPosition("first")).thenReturn(
                snapshot("first", "vehicle-91622", 113.05, 23.0, now));

        VehicleOrderEligibilityService.EligibilityReport report = fixture.service.analyzeLatestVehicleOrders();

        assertThat(report.enabled()).isFalse();
        assertThat(report.decisions()).hasSize(1);
        assertThat(report.decisions().get(0).tripOrderInstanceIds()).hasSize(2);
        assertThat(report.decisions().get(0).tripStops()).hasSize(4);
        assertThat(report.decisions().get(0).currentPosition()).containsExactly(113.05, 23.0);
        assertThat(report.decisions().get(0).currentLegOriginPosition()).containsExactly(113.05, 23.0);
        verify(fixture.routePush).freshProviderPosition("first");
        verifyNoInteractions(fixture.trajectory);
    }

    @Test
    void localSimulatedHistoryAdvancesThroughTheSameDwellAndDepartureStateMachine() {
        Instant now = Instant.parse("2026-07-28T02:00:00Z");
        VehicleOrderChainStore.StoredOrder transporting = stored(
                record("transporting", "粤E54972", "运输中", now.minusSeconds(120), 113.1), 1);
        VehicleOrderChainStore.StoredOrder waiting = stored(
                record("waiting", "粤E54972", "待装载", now.minusSeconds(60), 113.0), 2);
        Fixture fixture = fixture(List.of(transporting, waiting));
        when(fixture.routePush.providerVehicleIdForLineId("transporting")).thenReturn("54972");
        when(fixture.routePush.freshProviderPosition("transporting")).thenReturn(
                PositionSnapshot.fromProvider("transporting", "54972", "粤E54972", "粤E54972",
                        113.1, 23.0, 20));
        VehicleOrderEligibilityService.VehicleDecision initial =
                fixture.service.analyzeLatestVehicleOrders().decisions().get(0);
        when(fixture.routePush.localPositionHistoryForTrip(initial.tripId())).thenReturn(List.of(
                new RoutePushService.RoutePositionHistorySample(
                        now, new double[]{113.0, 23.0}, "simulated-arrival", 0.2),
                new RoutePushService.RoutePositionHistorySample(
                        now.plusSeconds(60), new double[]{113.0, 23.0}, "simulated-dwell", 0.2),
                new RoutePushService.RoutePositionHistorySample(
                        now.plusSeconds(61), new double[]{113.02, 23.0}, "simulated-departure", 0.22)
        ));

        int updated = fixture.service.advanceTripsFromLocalPositionHistory();

        assertThat(updated).isEqualTo(1);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadata =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(fixture.routePush).setTripRuntimeMetadataForTrip(eq(initial.tripId()), metadata.capture());
        assertThat(metadata.getValue().get("targetStopId")).isNotEqualTo(initial.targetStopId());
        assertThat(metadata.getValue().get("tripStatusText")).isNotEqualTo(initial.tripStatusText());
    }

    @Test
    void localDestinationDwellAppendsInferredCompletionAndRequestsRouteRefresh() {
        Instant now = Instant.parse("2026-07-28T02:00:00Z");
        VehicleOrderChainStore.StoredOrder transporting = stored(
                record("transporting", "粤E54972", "运输中", now.minusSeconds(120), 113.1), 1);
        Fixture fixture = fixture(List.of(transporting));
        when(fixture.routePush.providerVehicleIdForLineId("transporting")).thenReturn("54972");
        when(fixture.routePush.freshProviderPosition("transporting")).thenReturn(
                PositionSnapshot.fromProvider("transporting", "54972", "粤E54972", "粤E54972",
                        113.1, 23.0, 20));
        when(fixture.store.recordInferredCompletion(transporting.record())).thenReturn(true);
        VehicleOrderEligibilityService.VehicleDecision initial =
                fixture.service.analyzeLatestVehicleOrders().decisions().get(0);
        when(fixture.routePush.localPositionHistoryForTrip(initial.tripId())).thenReturn(List.of(
                new RoutePushService.RoutePositionHistorySample(
                        now, new double[]{112.7, 23.3}, "simulated-arrival", 0.98),
                new RoutePushService.RoutePositionHistorySample(
                        now.plusSeconds(60), new double[]{112.7, 23.3}, "simulated-dwell", 0.99)
        ));

        assertThat(fixture.service.advanceTripsFromLocalPositionHistory()).isEqualTo(1);
        verify(fixture.store).recordInferredCompletion(transporting.record());
        assertThat(fixture.service.consumeCompletionRefreshRequired()).isTrue();
        assertThat(fixture.service.consumeCompletionRefreshRequired()).isFalse();
    }

    @Test
    void localFinalDeliveryCompletionBlocksStaleUpstreamTransportingRoute() {
        Instant enteredAt = Instant.parse("2026-07-27T00:00:00Z");
        VehicleOrderChainStore.StoredOrder transporting = stored(
                record("transporting", "粤A60001", "运输中", enteredAt.minusSeconds(60), 113.0), 1);
        Fixture fixture = fixture(List.of(transporting));
        when(fixture.routePush.providerVehicleIdForLineId("transporting")).thenReturn("60001");
        when(fixture.routePush.freshProviderPosition("transporting")).thenReturn(
                snapshot("transporting", "60001", 112.70, 23.30, enteredAt),
                snapshot("transporting", "60001", 112.701, 23.30, enteredAt.plusSeconds(61)),
                snapshot("transporting", "60001", 112.72, 23.30, enteredAt.plusSeconds(120)),
                snapshot("transporting", "60001", 112.73, 23.30, enteredAt.plusSeconds(180)));

        assertThat(decision(fixture.service.analyzeLatestVehicleOrders(), "transporting").decision())
                .isEqualTo("ARRIVED");
        VehicleOrderEligibilityService.VehicleDecision completed = decision(
                fixture.service.analyzeLatestVehicleOrders(), "transporting");
        VehicleOrderEligibilityService.VehicleDecision staleUpstream = decision(
                fixture.service.analyzeLatestVehicleOrders(), "transporting");

        assertThat(completed.decision()).isEqualTo("TRIP_COMPLETED_LOCAL");
        assertThat(completed.groupEligible()).isFalse();
        assertThat(completed.onboardOrderIds()).isEmpty();
        assertThat(completed.completedOrderIds()).containsExactly(transporting.key());
        assertThat(staleUpstream.decision()).isEqualTo("TRIP_COMPLETED_LOCAL");
        assertThat(staleUpstream.groupEligible()).isFalse();
        verify(fixture.store).recordInferredCompletion(transporting.record());
    }

    private Fixture fixture(List<VehicleOrderChainStore.StoredOrder> orders) {
        return fixture(orders, true);
    }

    private Fixture fixture(List<VehicleOrderChainStore.StoredOrder> orders, boolean autoClassifyWaiting) {
        RoutePushService routePush = mock(RoutePushService.class);
        TownRoadMiddleLayer middleLayer = mock(TownRoadMiddleLayer.class);
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ProviderTrajectoryClient trajectory = mock(ProviderTrajectoryClient.class);
        TownRoadExternalOrderProperties properties = new TownRoadExternalOrderProperties();
        properties.setIgnoreOrdersWithoutRealPosition(true);
        properties.setTreatLoadingUnloadingAsTransporting(autoClassifyWaiting);
        when(routePush.refreshProviderVehicleDirectoryNow()).thenReturn(Map.of("vehicleCount", orders.size()));
        when(routePush.prepareProviderPositionVehicle(any(), any(), any())).thenReturn(true);
        when(routePush.warmPositionCacheForLineIds(anySet())).thenReturn(Map.of("refreshed", orders.size()));
        when(middleLayer.instanceIdFor(any())).thenAnswer(invocation ->
                ((ExternalOrderRecord) invocation.getArgument(0)).orderId());
        when(store.recentStoredOrders()).thenReturn(orders);
        when(store.writeEligibilityAnalysis(any())).thenReturn("analysis.json");
        VehicleTripRuntimeService tripRuntimeService = new VehicleTripRuntimeService(store);
        return new Fixture(
                new VehicleOrderEligibilityService(
                        routePush, middleLayer, properties, store, trajectory, tripRuntimeService),
                routePush, trajectory, store, properties);
    }

    private VehicleOrderEligibilityService.VehicleDecision decision(
            VehicleOrderEligibilityService.EligibilityReport report,
            String orderId
    ) {
        return report.decisions().stream()
                .filter(item -> orderId.equals(item.orderId()))
                .findFirst().orElseThrow();
    }

    private VehicleOrderChainStore.StoredOrder stored(ExternalOrderRecord record, int sequence) {
        return new VehicleOrderChainStore.StoredOrder(
                record.orderId() + "|" + record.lineId() + "|" + record.vehicle().plate(),
                record.orderId(), record.lineId(), record.vehicle().plate(),
                record.status().contains("完成") ? "COMPLETED" : "OTHER",
                sequence, sequence, record);
    }

    private ExternalOrderRecord record(
            String orderId,
            String plate,
            String status,
            Instant updatedAt,
            double fromLng
    ) {
        return new ExternalOrderRecord(
                orderId,
                "route-" + orderId,
                new ExternalOrderRecord.Location("装载点", "广东省", "佛山市", "南海区", "440605",
                        new double[]{fromLng, 23.0}),
                new ExternalOrderRecord.Location("目的地", "广东省", "肇庆市", "四会市", "441284",
                        new double[]{112.7, 23.3}),
                new ExternalOrderRecord.Vehicle(plate, null, "货物", 10d, "吨", null, null),
                status,
                updatedAt.toString(),
                false,
                true
        );
    }

    private ProviderTrajectoryClient.TrackPoint point(Instant time, double lng) {
        return new ProviderTrajectoryClient.TrackPoint(time, lng, 23.0);
    }

    private PositionSnapshot snapshot(
            String lineId,
            String vehicleId,
            double lng,
            double lat,
            Instant providerTime
    ) {
        return new PositionSnapshot(
                lineId, vehicleId, vehicleId, "粤A60001", lng, lat, 10d,
                null, null, null, null, "none", true, null, null,
                providerTime, providerTime, "real", false);
    }

    private record Fixture(
            VehicleOrderEligibilityService service,
            RoutePushService routePush,
            ProviderTrajectoryClient trajectory,
            VehicleOrderChainStore store,
            TownRoadExternalOrderProperties properties
    ) {}
}
