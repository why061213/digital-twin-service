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

    @Test
    void mayDeliverLoadedOrderBeforeAnotherPickupButNeverDeliverAnOrderBeforeItsOwnPickup() {
        VehicleOrderChainStore.StoredOrder loaded = stored("LOADED", "L1", 113.0, 23.0, 113.02, 23.0);
        VehicleOrderChainStore.StoredOrder waiting = stored("WAITING", "L2", 114.0, 23.0, 114.5, 23.0);
        Map<String, VehicleOrderChainStore.StoredOrder> orders = orders(loaded, waiting);
        VehicleTripTopologyService.TripTopology topology = new VehicleTripTopologyService().build(
                orders,
                Map.of(loaded.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED,
                        waiting.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED),
                Set.of(loaded.key()), Set.of(), null, new double[]{113.01, 23.0}, null);

        assertThat(topology.plannedStopIds().get(0)).isEqualTo(loaded.key() + "::DELIVERY");
        assertBefore(topology.plannedStopIds(), waiting.key() + "::PICKUP", waiting.key() + "::DELIVERY");
    }

    @Test
    void identicalCoordinatesProduceIdenticalSpatialOrderWithoutLivePosition() {
        VehicleOrderChainStore.StoredOrder firstA = stored("A1", "LA1", 113.0, 23.0, 113.13, 23.56);
        VehicleOrderChainStore.StoredOrder firstB = stored("A2", "LA2", 113.0, 23.0, 113.14, 23.62);
        VehicleOrderChainStore.StoredOrder secondA = stored("Z9", "LZ9", 113.0, 23.0, 113.13, 23.56);
        VehicleOrderChainStore.StoredOrder secondB = stored("Z8", "LZ8", 113.0, 23.0, 113.14, 23.62);
        VehicleTripTopologyService service = new VehicleTripTopologyService();

        VehicleTripTopologyService.TripTopology first = service.build(
                orders(firstA, firstB),
                Map.of(firstA.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED,
                        firstB.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED),
                Set.of(firstA.key(), firstB.key()), Set.of(), null);
        VehicleTripTopologyService.TripTopology second = service.build(
                orders(secondA, secondB),
                Map.of(secondA.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED,
                        secondB.key(), VehicleTripRuntimeService.TripMemberState.CONFIRMED),
                Set.of(secondA.key(), secondB.key()), Set.of(), null);

        assertThat(spatialPlan(first)).containsExactlyElementsOf(spatialPlan(second));
        assertThat(spatialPlan(first)).containsExactly("113.13000,23.56000", "113.14000,23.62000");
    }

    private List<String> spatialPlan(VehicleTripTopologyService.TripTopology topology) {
        Map<String, VehicleTripTopologyService.TripStop> byId = topology.stops().stream()
                .collect(java.util.stream.Collectors.toMap(
                        VehicleTripTopologyService.TripStop::stopId, stop -> stop));
        return topology.plannedStopIds().stream().map(byId::get)
                .map(stop -> String.format(java.util.Locale.ROOT, "%.5f,%.5f",
                        stop.coordinates()[0], stop.coordinates()[1]))
                .toList();
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
