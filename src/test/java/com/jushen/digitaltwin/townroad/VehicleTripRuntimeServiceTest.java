package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.baidu.RoutePlanningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleTripRuntimeServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesCompositeTripFromTrajectoryEvenWhenOrderStatusesAreWrong() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord first = compositeRecord(
                "order-a1-b1", "line-a1-b1", "已完成", "A1", 113.10, "B1", 113.30);
        ExternalOrderRecord second = compositeRecord(
                "order-a2-b2", "line-a2-b2", "运输中", "A2", 113.20, "B2", 113.40);
        List<VehicleOrderChainStore.StoredOrder> route = List.of(stored(first, 10), stored(second, 20));
        Instant start = Instant.parse("2026-07-27T00:00:00Z");

        VehicleTripRuntimeService.CompositeTripSnapshot beforeA1 = resolveComposite(
                store, route, points(start, 113.00));
        assertThat(beforeA1.statusText()).isEqualTo("正在前往第一装载点 · A1");
        assertThat(beforeA1.stops()).extracting(VehicleTripRuntimeService.CompositeStopView::locationName)
                .containsExactly("A1", "A2", "B1", "B2");
        assertThat(beforeA1.stops()).filteredOn(stop ->
                        stop.action() == VehicleTripTopologyService.StopAction.DELIVERY)
                .allMatch(stop -> "#ef4444".equals(stop.markerColor()));

        List<VehicleTripRuntimeService.TripPosition> throughA1 = points(start,
                113.00, 113.10, 113.10, 113.15);
        VehicleTripRuntimeService.CompositeTripSnapshot toA2 = resolveComposite(store, route, throughA1);
        assertThat(toA2.statusText()).isEqualTo("正在前往第二装载点 · A2");
        assertThat(toA2.trip().onboardOrderIds()).containsExactly(key(first));

        List<VehicleTripRuntimeService.TripPosition> throughA2 = append(throughA1, start, 4,
                113.20, 113.20, 113.25);
        VehicleTripRuntimeService.CompositeTripSnapshot toB1 = resolveComposite(store, route, throughA2);
        assertThat(toB1.statusText()).isEqualTo("正在前往第一目的地 · B1");
        assertThat(toB1.trip().onboardOrderIds()).containsExactlyInAnyOrder(key(first), key(second));

        List<VehicleTripRuntimeService.TripPosition> throughB1 = append(throughA2, start, 7,
                113.30, 113.30, 113.35);
        VehicleTripRuntimeService.CompositeTripSnapshot toB2 = resolveComposite(store, route, throughB1);
        assertThat(toB2.statusText()).isEqualTo("正在前往第二目的地 · B2");
        assertThat(toB2.trip().completedOrderIds()).containsExactly(key(first));

        List<VehicleTripRuntimeService.TripPosition> completed = append(throughB1, start, 10,
                113.40, 113.40, 113.46);
        VehicleTripRuntimeService.CompositeTripSnapshot done = resolveComposite(store, route, completed);
        assertThat(done.statusText()).isEqualTo("复合订单已完成");
        assertThat(done.trip().completedOrderIds()).containsExactlyInAnyOrder(key(first), key(second));
    }

    @Test
    void keepsAllCurrentOrdersButSelectsOneStableTransportingAnchor() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord waiting = record("order-waiting", "line-waiting", "待装载");
        ExternalOrderRecord transporting = record("order-transit", "line-transit", "运输中");
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);

        VehicleTripRuntimeService.VehicleTripRuntime trip = service.reconcile(List.of(
                stored(waiting, 10), stored(transporting, 20))).get(0);

        assertThat(trip.orderInstanceIds()).hasSize(2);
        assertThat(trip.orderMembers().get(key(waiting)))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.CANDIDATE);
        assertThat(trip.pendingPickupOrderIds()).doesNotContain(key(waiting));
        assertThat(trip.onboardOrderIds()).contains(key(transporting));
        assertThat(trip.anchorOrder().record().orderId()).isEqualTo("order-transit");
        assertThat(trip.phase()).isEqualTo(VehicleTripRuntimeService.TripPhase.LINEHAUL);
    }

    @Test
    void completedMemberIsRemovedFromOpenTripAsAWholeOrderChain() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord first = record("order-1", "line-1", "运输中");
        ExternalOrderRecord second = record("order-2", "line-2", "待装载");
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        service.reconcile(List.of(stored(first, 10), stored(second, 20)));

        ExternalOrderRecord firstCompleted = record("order-1", "line-1", "已完成");
        VehicleTripRuntimeService.VehicleTripRuntime trip = service.reconcile(List.of(
                stored(firstCompleted, 30), stored(second, 20))).get(0);

        assertThat(trip.orderInstanceIds()).doesNotContain(key(firstCompleted));
        assertThat(trip.ordersByInstanceId()).doesNotContainKey(key(firstCompleted));
        assertThat(trip.topology().stops()).extracting(VehicleTripTopologyService.TripStop::orderInstanceId)
                .doesNotContain(key(firstCompleted));
        assertThat(trip.pendingPickupOrderIds()).contains(key(second));
        assertThat(trip.phase()).isEqualTo(VehicleTripRuntimeService.TripPhase.TO_FIRST_PICKUP);
        assertThat(trip.closedAt()).isNull();
    }

    @Test
    void localInferredTransitMovesWaitingOrderIntoOnboardSet() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord waiting = record("order-1", "line-1", "待装载");
        when(store.recordedTransitStatus(waiting)).thenReturn("在途-2");

        VehicleTripRuntimeService.VehicleTripRuntime trip =
                new VehicleTripRuntimeService(store).reconcile(List.of(stored(waiting, 10))).get(0);

        assertThat(trip.onboardOrderIds()).containsExactly(key(waiting));
        assertThat(trip.pendingPickupOrderIds()).isEmpty();
        assertThat(trip.phase()).isEqualTo(VehicleTripRuntimeService.TripPhase.LINEHAUL);
    }

    @Test
    void orderFirstSeenMuchLaterIsQueuedInsteadOfChangingCurrentTripPlan() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord current = record("order-1", "line-1", "运输中");
        ExternalOrderRecord future = record("order-next", "line-next", "待装载");

        VehicleTripRuntimeService.VehicleTripRuntime trip = new VehicleTripRuntimeService(store).reconcile(List.of(
                stored(current, 1_000), stored(future, 31L * 60L * 1_000L))).get(0);

        assertThat(trip.orderMembers().get(key(current)))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.CONFIRMED);
        assertThat(trip.orderMembers().get(key(future)))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.QUEUED);
        assertThat(trip.queuedOrderIds()).containsExactly(key(future));
        assertThat(trip.topology().stops()).extracting(VehicleTripTopologyService.TripStop::orderInstanceId)
                .doesNotContain(key(future));
    }

    @Test
    void queuedOrderOpensNewTripOnlyAfterPreviousTripLeavesCurrentSnapshot() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord current = record("order-1", "line-1", "运输中");
        ExternalOrderRecord future = record("order-next", "line-next", "待装载");
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        VehicleTripRuntimeService.VehicleTripRuntime first = service.reconcile(List.of(
                stored(current, 1_000), stored(future, 31L * 60L * 1_000L))).get(0);

        VehicleTripRuntimeService.VehicleTripRuntime next = service.reconcile(List.of(
                stored(future, 31L * 60L * 1_000L))).get(0);

        assertThat(next.tripId()).isNotEqualTo(first.tripId());
        assertThat(next.orderMembers().get(key(future)))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.CONFIRMED);
        assertThat(next.queuedOrderIds()).isEmpty();
    }

    @Test
    void insertsQueuedAlongRouteOrderFromCurrentPositionAndAdvancesThatPickupOnly() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord onboard = record(
                "order-1", "line-1", "运输中",
                new double[]{113.0, 23.0}, new double[]{115.0, 23.0});
        ExternalOrderRecord inserted = record(
                "order-2", "line-2", "待装载",
                new double[]{114.0, 23.02}, new double[]{114.7, 23.0});
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        VehicleTripRuntimeService.VehicleTripRuntime initial = service.reconcile(List.of(
                stored(onboard, 1_000), stored(inserted, 31L * 60L * 1_000L))).get(0);

        VehicleTripRuntimeService.VehicleTripRuntime replanned = service.evaluateDynamicInsertions(
                initial, new double[]{113.5, 23.0});

        assertThat(replanned.tripId()).isEqualTo(initial.tripId());
        assertThat(replanned.runtimeLineId()).isEqualTo(initial.runtimeLineId());
        assertThat(replanned.orderMembers().get(key(inserted)))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.CONFIRMED);
        assertThat(replanned.queuedOrderIds()).doesNotContain(key(inserted));
        assertThat(service.currentTargetStop(replanned).orderInstanceId()).isEqualTo(key(inserted));
        assertThat(replanned.topology().legs().get(0).fromStopId()).isEqualTo("CURRENT_POSITION");
        assertThat(replanned.topology().legs().get(0).coordinates().get(0))
                .containsExactly(113.5, 23.0);

        VehicleTripRuntimeService.VehicleTripRuntime reconciled = service.reconcile(List.of(
                stored(onboard, 1_000), stored(inserted, 31L * 60L * 1_000L))).get(0);
        assertThat(reconciled.currentLegId()).isEqualTo(replanned.currentLegId());
        assertThat(reconciled.topology().legs().get(0).fromStopId()).isEqualTo("CURRENT_POSITION");
        assertThat(reconciled.topology().legs().get(0).coordinates().get(0))
                .containsExactly(113.5, 23.0);

        VehicleTripRuntimeService.VehicleTripRuntime loading = service.applyEligibilityEvidence(
                reconciled, key(inserted) + "::PICKUP", key(inserted), "LOADING",
                new double[]{114.0, 23.02});
        VehicleTripRuntimeService.VehicleTripRuntime departed = service.applyEligibilityEvidence(
                loading, key(inserted) + "::PICKUP", key(inserted), "DEPARTED",
                new double[]{114.1, 23.02});
        assertThat(departed.onboardOrderIds()).contains(key(onboard), key(inserted));
        assertThat(departed.pendingPickupOrderIds()).doesNotContain(key(inserted));
    }

    @Test
    void rejectsAlongPickupWhenDeliveryMakesWholeSuffixUnreasonable() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord onboard = record("order-1", "line-1", "运输中",
                new double[]{113.0, 23.0}, new double[]{120.2, 30.3});
        ExternalOrderRecord detour = record("order-2", "line-2", "待装载",
                new double[]{115.0, 27.0}, new double[]{87.6, 43.8});
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        VehicleTripRuntimeService.VehicleTripRuntime initial = service.reconcile(List.of(
                stored(onboard, 1_000), stored(detour, 31L * 60L * 1_000L))).get(0);

        VehicleTripRuntimeService.VehicleTripRuntime result = service.evaluateDynamicInsertions(
                initial, new double[]{114.0, 25.0});

        assertThat(result.orderMembers().get(key(detour)))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.QUEUED);
        assertThat(result.topology().stops()).extracting(VehicleTripTopologyService.TripStop::orderInstanceId)
                .doesNotContain(key(detour));
    }

    @Test
    void validatesAndMaterializesCompleteSuffixWithRoadPlanner() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        RoutePlanningService planner = mock(RoutePlanningService.class);
        when(planner.plan(any(double[].class), any(double[].class))).thenAnswer(invocation -> {
            double[] from = invocation.getArgument(0);
            double[] to = invocation.getArgument(1);
            double distance = VehicleTripTopologyService.haversineKm(from, to) * 1.1d;
            double[] middle = new double[]{(from[0] + to[0]) / 2d, (from[1] + to[1]) / 2d};
            return new RoutePlanningService.PlannedRoute(
                    true, "road-test", List.of(from.clone(), middle, to.clone()),
                    List.of(from.clone(), middle, to.clone()), distance, 60_000L, null);
        });
        ExternalOrderRecord onboard = record("order-1", "line-1", "运输中",
                new double[]{113.0, 23.0}, new double[]{115.0, 23.0});
        ExternalOrderRecord inserted = record("order-2", "line-2", "待装载",
                new double[]{114.0, 23.02}, new double[]{114.7, 23.0});
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(
                store, new VehicleTripTopologyService(), planner);
        VehicleTripRuntimeService.VehicleTripRuntime initial = service.reconcile(List.of(
                stored(onboard, 1_000), stored(inserted, 31L * 60L * 1_000L))).get(0);

        VehicleTripRuntimeService.VehicleTripRuntime result = service.evaluateDynamicInsertions(
                initial, new double[]{113.5, 23.0});

        assertThat(result.orderMembers().get(key(inserted)))
                .isEqualTo(VehicleTripRuntimeService.TripMemberState.CONFIRMED);
        assertThat(result.topology().plannedStopIds())
                .contains(key(inserted) + "::PICKUP", key(inserted) + "::DELIVERY");
        assertThat(result.topology().legs()).allSatisfy(leg -> {
            assertThat(leg.coordinates()).hasSize(3);
            assertThat(leg.durationMs()).isEqualTo(60_000L);
        });
        verify(planner, atLeast(4)).plan(any(double[].class), any(double[].class));
    }

    @Test
    void retainsPreviousOrderWhenDynamicReplanCannotBeValidatedByRoadPlanner() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        RoutePlanningService planner = mock(RoutePlanningService.class);
        when(planner.plan(any(double[].class), any(double[].class)))
                .thenReturn(RoutePlanningService.PlannedRoute.unavailable("planner unavailable"));
        ExternalOrderRecord nearerFromOrigin = record(
                "order-near", "line-near", "运输中",
                new double[]{113.0, 23.0}, new double[]{114.0, 23.0});
        ExternalOrderRecord fartherFromOrigin = record(
                "order-far", "line-far", "运输中",
                new double[]{113.1, 23.0}, new double[]{116.0, 23.0});
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(
                store, new VehicleTripTopologyService(), planner);
        VehicleTripRuntimeService.VehicleTripRuntime initial = service.reconcile(List.of(
                stored(nearerFromOrigin, 1_000), stored(fartherFromOrigin, 2_000))).get(0);

        VehicleTripRuntimeService.VehicleTripRuntime result = service.replanRemainingRoute(
                initial, new double[]{115.8, 23.0});

        assertThat(result.topology().planSignature()).isEqualTo(initial.topology().planSignature());
        assertThat(result.topology().plannedStopIds().get(0))
                .isEqualTo(key(nearerFromOrigin) + "::DELIVERY");
        verify(planner).plan(any(double[].class), any(double[].class));
    }

    @Test
    void deliveryRequiresValidDwellBeforeOnlyThatOrderCompletes() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord onboard = record("order-1", "line-1", "运输中",
                new double[]{113.0, 23.0}, new double[]{115.0, 23.0});
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        VehicleTripRuntimeService.VehicleTripRuntime trip = service.reconcile(
                List.of(stored(onboard, 1_000))).get(0);
        Instant enteredAt = Instant.parse("2026-07-27T00:00:00Z");

        VehicleTripRuntimeService.TargetPresenceObservation arrived = service.observeCurrentTarget(
                trip, new double[]{115.0, 23.0}, enteredAt);
        VehicleTripRuntimeService.TargetPresenceObservation dwelling = service.observeCurrentTarget(
                trip, new double[]{115.001, 23.0}, enteredAt.plusSeconds(61));
        VehicleTripRuntimeService.TargetPresenceObservation departed = service.observeCurrentTarget(
                trip, new double[]{115.2, 23.0}, enteredAt.plusSeconds(120));

        assertThat(arrived.state()).isEqualTo(VehicleTripRuntimeService.TargetPresenceState.ARRIVED);
        assertThat(dwelling.state()).isEqualTo(VehicleTripRuntimeService.TargetPresenceState.DWELLING);
        assertThat(departed.state()).isEqualTo(VehicleTripRuntimeService.TargetPresenceState.DEPARTED);
        String stopId = key(onboard) + "::DELIVERY";
        trip = service.applyEligibilityEvidence(trip, stopId, key(onboard), "ARRIVED",
                new double[]{115.0, 23.0});
        trip = service.applyEligibilityEvidence(trip, stopId, key(onboard), "UNLOADING",
                new double[]{115.001, 23.0});
        trip = service.applyEligibilityEvidence(trip, stopId, key(onboard), "DEPARTED",
                new double[]{115.2, 23.0});
        assertThat(trip.onboardOrderIds()).isEmpty();
        assertThat(trip.completedOrderIds()).containsExactly(key(onboard));
    }

    @Test
    void passingWithinFiveKilometersCannotCompleteLoadingOrDelivery() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord onboard = record("order-1", "line-1", "运输中",
                new double[]{113.0, 23.0}, new double[]{115.0, 23.0});
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        VehicleTripRuntimeService.VehicleTripRuntime trip = service.reconcile(
                List.of(stored(onboard, 1_000))).get(0);
        Instant first = Instant.parse("2026-07-27T00:00:00Z");

        // 距卸货点约 4km：处于旧 5km 节点融合范围内，但不属于新的 500m 到站范围。
        VehicleTripRuntimeService.TargetPresenceObservation nearPass = service.observeCurrentTarget(
                trip, new double[]{115.039, 23.0}, first);
        VehicleTripRuntimeService.TargetPresenceObservation laterPass = service.observeCurrentTarget(
                trip, new double[]{115.030, 23.0}, first.plusSeconds(90));
        VehicleTripRuntimeService.TargetPresenceObservation gone = service.observeCurrentTarget(
                trip, new double[]{115.20, 23.0}, first.plusSeconds(180));

        assertThat(nearPass.state()).isEqualTo(VehicleTripRuntimeService.TargetPresenceState.EN_ROUTE);
        assertThat(laterPass.state()).isEqualTo(VehicleTripRuntimeService.TargetPresenceState.EN_ROUTE);
        assertThat(gone.state()).isEqualTo(VehicleTripRuntimeService.TargetPresenceState.EN_ROUTE);
        assertThat(trip.onboardOrderIds()).containsExactly(key(onboard));
        assertThat(trip.completedOrderIds()).isEmpty();
    }

    @Test
    void stalePositionDoesNotRollbackConfirmedDeparture() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord waiting = record("order-1", "line-1", "待装载");
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        VehicleTripRuntimeService.VehicleTripRuntime trip = service.reconcile(List.of(stored(waiting, 10))).get(0);
        VehicleTripRuntimeService.VehicleTripRuntime loading = service.applyEligibilityEvidence(trip, "LOADING");
        assertThat(loading.currentNodeId()).isNotBlank();
        assertThat(loading.currentLegId()).isNull();
        trip = service.applyEligibilityEvidence(loading, "DEPARTED");

        VehicleTripRuntimeService.VehicleTripRuntime stale = service.applyEligibilityEvidence(trip, "NO_REAL_POSITION");

        assertThat(stale.onboardOrderIds()).containsExactly(key(waiting));
        assertThat(stale.phase()).isEqualTo(VehicleTripRuntimeService.TripPhase.LINEHAUL);
        assertThat(stale.positionQuality()).isEqualTo(VehicleTripRuntimeService.PositionQuality.STALE);
        assertThat(stale.currentLegId()).isNotBlank();
    }

    @Test
    void restartRestoresTripIdentityPlanVersionAndMonotonicOnboardState() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        when(store.runtimeRootPath()).thenReturn(temporaryDirectory);
        ExternalOrderRecord waiting = record("order-1", "line-1", "待装载");
        List<VehicleOrderChainStore.StoredOrder> snapshot = List.of(stored(waiting, 10));
        ObjectMapper objectMapper = new ObjectMapper();
        VehicleTripRuntimeService beforeRestart = new VehicleTripRuntimeService(
                store, new VehicleTripTopologyService(), objectMapper);
        VehicleTripRuntimeService.VehicleTripRuntime departed = beforeRestart.applyEligibilityEvidence(
                beforeRestart.reconcile(snapshot).get(0), "DEPARTED");

        VehicleTripRuntimeService afterRestart = new VehicleTripRuntimeService(
                store, new VehicleTripTopologyService(), objectMapper);
        VehicleTripRuntimeService.VehicleTripRuntime restored = afterRestart.reconcile(snapshot).get(0);

        assertThat(restored.tripId()).isEqualTo(departed.tripId());
        assertThat(restored.runtimeLineId()).isEqualTo(departed.runtimeLineId());
        assertThat(restored.planVersion()).isGreaterThanOrEqualTo(departed.planVersion());
        assertThat(restored.onboardOrderIds()).containsExactly(key(waiting));
        assertThat(restored.phase()).isEqualTo(VehicleTripRuntimeService.TripPhase.LINEHAUL);
    }

    private VehicleOrderChainStore.StoredOrder stored(ExternalOrderRecord record, long observedAt) {
        return new VehicleOrderChainStore.StoredOrder(
                key(record), record.orderId(), record.lineId(), record.vehicle().plate(),
                record.status().contains("完成") ? "COMPLETED" : "OTHER",
                observedAt, observedAt, record);
    }

    private String key(ExternalOrderRecord record) {
        return record.orderId() + "|" + record.lineId() + "|" + record.vehicle().plate();
    }

    private ExternalOrderRecord record(String orderId, String lineId, String status) {
        return record(orderId, lineId, status, new double[]{113.1, 23.1}, new double[]{112.7, 23.3});
    }

    private ExternalOrderRecord record(
            String orderId,
            String lineId,
            String status,
            double[] fromCoords,
            double[] toCoords
    ) {
        return new ExternalOrderRecord(
                orderId, lineId,
                new ExternalOrderRecord.Location("装载点", "广东省", "佛山市", "南海区", "440605",
                        fromCoords),
                new ExternalOrderRecord.Location("卸货点", "广东省", "肇庆市", "四会市", "441284",
                        toCoords),
                new ExternalOrderRecord.Vehicle("粤A10001", "vehicle-1", "货物", 10d, "吨", null, null),
                status, Instant.now().toString(), false, true);
    }

    @Test
    void reordersConfirmedDeliveriesFromCurrentPositionAndExposesFarthestAsFinalStop() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord farther = record(
                "order-a-far", "line-a-far", "运输中",
                new double[]{113.0, 23.0}, new double[]{116.0, 23.0});
        ExternalOrderRecord nearer = record(
                "order-z-near", "line-z-near", "运输中",
                new double[]{113.1, 23.0}, new double[]{114.0, 23.0});
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        VehicleTripRuntimeService.VehicleTripRuntime initial = service.reconcile(List.of(
                stored(farther, 1_000), stored(nearer, 2_000))).get(0);

        VehicleTripRuntimeService.VehicleTripRuntime replanned = service.evaluateDynamicInsertions(
                initial, new double[]{113.5, 23.0});

        assertThat(replanned.topology().plannedStopIds()).containsExactly(
                key(nearer) + "::DELIVERY",
                key(farther) + "::DELIVERY");
        assertThat(replanned.topology().legs().get(0).fromStopId()).isEqualTo("CURRENT_POSITION");
        assertThat(replanned.topology().legs().get(0).coordinates().get(0))
                .containsExactly(113.5, 23.0);

        VehicleTripRuntimeService.CompositeTripSnapshot snapshot = service.describeCompositeTrip(
                replanned, VehicleTripRuntimeService.TargetPresenceState.EN_ROUTE);
        assertThat(snapshot.stops().stream()
                .filter(stop -> stop.action() == VehicleTripTopologyService.StopAction.DELIVERY)
                .map(VehicleTripRuntimeService.CompositeStopView::orderInstanceId)
                .toList()).containsExactly(key(nearer), key(farther));
        assertThat(snapshot.stops().get(snapshot.stops().size() - 1).orderInstanceId())
                .isEqualTo(key(farther));
    }

    @Test
    void compositeViewDoesNotReopenUpstreamCompletedDelivery() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord fartherTransporting = record(
                "order-a-far", "line-a-far", "运输中",
                new double[]{113.0, 23.0}, new double[]{113.14, 23.62});
        ExternalOrderRecord nearer = record(
                "order-z-near", "line-z-near", "运输中",
                new double[]{113.1, 23.15}, new double[]{113.13, 23.56});
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        VehicleTripRuntimeService.VehicleTripRuntime initial = service.reconcile(List.of(
                stored(fartherTransporting, 1_000), stored(nearer, 2_000))).get(0);
        ExternalOrderRecord fartherCompleted = record(
                "order-a-far", "line-a-far", "已完成",
                new double[]{113.0, 23.0}, new double[]{113.14, 23.62});
        VehicleTripRuntimeService.VehicleTripRuntime upstreamCompleted = service.reconcile(List.of(
                stored(fartherCompleted, 1_000), stored(nearer, 2_000))).get(0);

        assertThat(initial.orderMembers()).hasSize(2);
        assertThat(upstreamCompleted.orderInstanceIds()).doesNotContain(key(fartherCompleted));

        VehicleTripRuntimeService.VehicleTripRuntime composite =
                service.includeAllActiveOrdersForCompositeView(upstreamCompleted);
        VehicleTripRuntimeService.VehicleTripRuntime replanned = service.evaluateDynamicInsertions(
                composite, new double[]{112.98, 23.08});

        assertThat(replanned.topology().plannedStopIds()).containsExactly(key(nearer) + "::DELIVERY");
        assertThat(replanned.topology().stops().stream()
                .filter(stop -> stop.orderInstanceId().equals(key(fartherCompleted)))
                .toList()).isEmpty();
    }

    private VehicleTripRuntimeService.CompositeTripSnapshot resolveComposite(
            VehicleOrderChainStore store,
            List<VehicleOrderChainStore.StoredOrder> route,
            List<VehicleTripRuntimeService.TripPosition> positions
    ) {
        return new VehicleTripRuntimeService(store).resolveCompositeTrip(route, positions);
    }

    private List<VehicleTripRuntimeService.TripPosition> points(Instant start, double... longitudes) {
        List<VehicleTripRuntimeService.TripPosition> result = new java.util.ArrayList<>();
        for (int index = 0; index < longitudes.length; index++) {
            long seconds = index == 0 ? 0 : index * 70L;
            result.add(new VehicleTripRuntimeService.TripPosition(
                    start.plusSeconds(seconds), new double[]{longitudes[index], 23.0}));
        }
        return List.copyOf(result);
    }

    private List<VehicleTripRuntimeService.TripPosition> append(
            List<VehicleTripRuntimeService.TripPosition> prefix,
            Instant start,
            int startIndex,
            double... longitudes
    ) {
        List<VehicleTripRuntimeService.TripPosition> result = new java.util.ArrayList<>(prefix);
        for (int index = 0; index < longitudes.length; index++) {
            result.add(new VehicleTripRuntimeService.TripPosition(
                    start.plusSeconds((startIndex + index) * 70L),
                    new double[]{longitudes[index], 23.0}));
        }
        return List.copyOf(result);
    }

    private ExternalOrderRecord compositeRecord(
            String orderId,
            String lineId,
            String status,
            String pickupName,
            double pickupLng,
            String deliveryName,
            double deliveryLng
    ) {
        return new ExternalOrderRecord(
                orderId, lineId,
                new ExternalOrderRecord.Location(pickupName, "广东省", "佛山市", "南海区", "440605",
                        new double[]{pickupLng, 23.0}),
                new ExternalOrderRecord.Location(deliveryName, "广东省", "佛山市", "南海区", "440605",
                        new double[]{deliveryLng, 23.0}),
                new ExternalOrderRecord.Vehicle("粤A10001", "vehicle-1", "货物", 10d, "吨", null, null),
                status, Instant.now().toString(), false, true);
    }
}
