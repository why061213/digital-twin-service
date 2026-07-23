package com.jushen.digitaltwin.townroad;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VehicleTripRuntimeServiceTest {
    @Test
    void keepsAllCurrentOrdersButSelectsOneStableTransportingAnchor() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord waiting = record("order-waiting", "line-waiting", "待装载");
        ExternalOrderRecord transporting = record("order-transit", "line-transit", "运输中");
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);

        VehicleTripRuntimeService.VehicleTripRuntime trip = service.reconcile(List.of(
                stored(waiting, 10), stored(transporting, 20))).get(0);

        assertThat(trip.orderInstanceIds()).hasSize(2);
        assertThat(trip.pendingPickupOrderIds()).contains(key(waiting));
        assertThat(trip.onboardOrderIds()).contains(key(transporting));
        assertThat(trip.anchorOrder().record().orderId()).isEqualTo("order-transit");
        assertThat(trip.phase()).isEqualTo(VehicleTripRuntimeService.TripPhase.COLLECTING);
    }

    @Test
    void completedMemberStaysInOpenTripInsteadOfClosingWholeVehicleTrip() {
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ExternalOrderRecord first = record("order-1", "line-1", "运输中");
        ExternalOrderRecord second = record("order-2", "line-2", "待装载");
        VehicleTripRuntimeService service = new VehicleTripRuntimeService(store);
        service.reconcile(List.of(stored(first, 10), stored(second, 20)));

        ExternalOrderRecord firstCompleted = record("order-1", "line-1", "已完成");
        VehicleTripRuntimeService.VehicleTripRuntime trip = service.reconcile(List.of(
                stored(firstCompleted, 30), stored(second, 20))).get(0);

        assertThat(trip.completedOrderIds()).contains(key(firstCompleted));
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
        return new ExternalOrderRecord(
                orderId, lineId,
                new ExternalOrderRecord.Location("装载点", "广东省", "佛山市", "南海区", "440605",
                        new double[]{113.1, 23.1}),
                new ExternalOrderRecord.Location("卸货点", "广东省", "肇庆市", "四会市", "441284",
                        new double[]{112.7, 23.3}),
                new ExternalOrderRecord.Vehicle("粤A10001", "vehicle-1", "货物", 10d, "吨", null, null),
                status, Instant.now().toString(), false, true);
    }
}
