package com.jushen.digitaltwin.townroad;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleTripTopologyServiceTest {
    @Test
    void mergesNearbyStopsButKeepsInternalStopsAndPickupBeforeDelivery() {
        VehicleOrderChainStore.StoredOrder first = stored("O1", "L1", 113.10, 23.10, 113.50, 23.30);
        VehicleOrderChainStore.StoredOrder second = stored("O2", "L2", 113.11, 23.10, 113.60, 23.40);
        Map<String, VehicleOrderChainStore.StoredOrder> orders = orders(first, second);
        Map<String, VehicleTripRuntimeService.TripMemberState> members = Map.of(
                first.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED,
                second.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED);

        VehicleTripTopologyService.TripTopology topology = new VehicleTripTopologyService().build(
                orders, members, Set.of(), Set.of(), null);

        assertThat(topology.stops()).hasSize(4);
        assertThat(topology.stops()).filteredOn(stop -> stop.action() == VehicleTripTopologyService.StopAction.PICKUP)
                .allSatisfy(stop -> assertThat(stop.cargoDelta()).isEqualTo(10d));
        assertThat(topology.stops()).filteredOn(stop -> stop.action() == VehicleTripTopologyService.StopAction.DELIVERY)
                .allSatisfy(stop -> assertThat(stop.cargoDelta()).isEqualTo(-10d));
        assertThat(topology.nodes()).anySatisfy(node -> {
            assertThat(node.stops()).extracting(VehicleTripTopologyService.TripStop::stopId)
                    .contains(first.key() + "::PICKUP", second.key() + "::PICKUP");
            assertThat(node.internalVisitSequence()).hasSize(2);
        });
        assertBefore(topology.plannedStopIds(), first.key() + "::PICKUP", first.key() + "::DELIVERY");
        assertBefore(topology.plannedStopIds(), second.key() + "::PICKUP", second.key() + "::DELIVERY");
        assertThat(topology.legs()).hasSize(3);
        assertThat(topology.legs()).allSatisfy(leg -> {
            assertThat(leg.coordinates()).isEmpty();
            assertThat(leg.segmentKey()).contains("->");
        });
    }

    @Test
    void candidateAndQueuedOrdersCannotChangeConfirmedPlan() {
        VehicleOrderChainStore.StoredOrder confirmed = stored("O1", "L1", 113.10, 23.10, 113.50, 23.30);
        VehicleOrderChainStore.StoredOrder candidate = stored("O2", "L2", 114.10, 24.10, 114.50, 24.30);
        Map<String, VehicleOrderChainStore.StoredOrder> orders = orders(confirmed, candidate);

        VehicleTripTopologyService.TripTopology topology = new VehicleTripTopologyService().build(
                orders,
                Map.of(confirmed.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED,
                        candidate.key(), VehicleTripRuntimeService.TripMemberState.CANDIDATE),
                Set.of(), Set.of(), null);

        assertThat(topology.stops()).extracting(VehicleTripTopologyService.TripStop::orderInstanceId)
                .containsOnly(confirmed.key());
    }

    private void assertBefore(List<String> values, String first, String second) {
        assertThat(values.indexOf(first)).isLessThan(values.indexOf(second));
    }

    private Map<String, VehicleOrderChainStore.StoredOrder> orders(VehicleOrderChainStore.StoredOrder... values) {
        Map<String, VehicleOrderChainStore.StoredOrder> result = new LinkedHashMap<>();
        for (VehicleOrderChainStore.StoredOrder value : values) result.put(value.key(), value);
        return result;
    }

    private VehicleOrderChainStore.StoredOrder stored(
            String orderId,
            String lineId,
            double fromLng,
            double fromLat,
            double toLng,
            double toLat
    ) {
        ExternalOrderRecord record = new ExternalOrderRecord(
                orderId, lineId,
                new ExternalOrderRecord.Location("P-" + orderId, "广东省", "佛山市", "南海区", null,
                        new double[]{fromLng, fromLat}),
                new ExternalOrderRecord.Location("D-" + orderId, "广东省", "广州市", "番禺区", null,
                        new double[]{toLng, toLat}),
                new ExternalOrderRecord.Vehicle("粤A10001", "V1", null, 10d, "吨", null, null),
                "待装载", "2026-07-23T01:00:00Z", false, true);
        String key = orderId + "|" + lineId + "|粤A10001";
        return new VehicleOrderChainStore.StoredOrder(
                key, orderId, lineId, "粤A10001", "OTHER", 1L, 1L, record);
    }
}
