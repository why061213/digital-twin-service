package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.service.RoutePushService;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TownRoadRenderServicePipelineCutTest {
    @Test
    void experimentFeedsOnlyConfirmedAndInferredTransitIntoOriginalPipeline() {
        TownRoadExternalOrderClient client = mock(TownRoadExternalOrderClient.class);
        TownRoadMiddleLayer middleLayer = mock(TownRoadMiddleLayer.class);
        RealtimeWebSocketHandler websocket = mock(RealtimeWebSocketHandler.class);
        AmapGeocodeClient geocode = mock(AmapGeocodeClient.class);
        TownRoadCoordinateResolver resolver = mock(TownRoadCoordinateResolver.class);
        RoutePushService routePush = mock(RoutePushService.class);
        DailyOrderStatisticsService statistics = mock(DailyOrderStatisticsService.class);
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        VehicleOrderEligibilityService eligibility = mock(VehicleOrderEligibilityService.class);
        TownRoadExternalOrderProperties properties = new TownRoadExternalOrderProperties();
        properties.setVehicleOrderChainExperimentEnabled(true);

        ExternalOrderRecord confirmed = record("confirmed", "line-confirmed", "粤A10001", "运输中");
        ExternalOrderRecord inferred = record("inferred", "line-inferred", "粤A10002", "待装载");
        ExternalOrderRecord rejected = record("rejected", "line-rejected", "粤A10003", "待装载");
        List<ExternalOrderRecord> raw = List.of(confirmed, inferred, rejected);
        when(middleLayer.expandVehicleInstances(raw)).thenReturn(raw);
        when(middleLayer.instanceIdFor(org.mockito.ArgumentMatchers.any(ExternalOrderRecord.class)))
                .thenAnswer(invocation -> ((ExternalOrderRecord) invocation.getArgument(0)).lineId());
        when(store.ingest(raw)).thenReturn(new VehicleOrderChainStore.IngestResult(
                3, 3, 0, 3, 0, 0, 0, 0, 3, 0,
                3, 3, List.of("2026-07-22", "2026-07-23"), "store", "daily"));
        when(store.recentStoredOrders()).thenReturn(List.of(
                stored(confirmed, 1), stored(inferred, 2), stored(rejected, 3)));
        when(eligibility.analyzeLatestVehicleOrders()).thenReturn(new VehicleOrderEligibilityService.EligibilityReport(
                Instant.now().toString(), true, Map.of("vehicleCount", 3), Map.of("refreshed", 3),
                3, 2, 1, List.of(
                decision(confirmed, true, "TRANSPORTING", "latest-order-is-transporting"),
                decision(inferred, true, "TRANSPORTING_RECORDED", "vehicle-order-chain-status:在途-2"),
                decision(rejected, false, "UNKNOWN", "waiting-auto-classification-disabled")
        ), "analysis.json"));
        OrderSnapshotDiff emptyDiff = new OrderSnapshotDiff(
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        when(middleLayer.processSnapshot(anyList())).thenReturn(new ExternalOrderSnapshotResult(
                2, 0, 0, 0, List.of(), List.of(), List.of(), emptyDiff));
        when(statistics.snapshot()).thenReturn(new DailyOrderStatisticsService.DailyOrderStatistics(
                "2026-07-23", 0, 0, 0, 0, 1, Instant.now().toString(), null));

        TownRoadRenderService service = new TownRoadRenderService(
                client, middleLayer, websocket, geocode, resolver, routePush,
                properties, statistics, store, eligibility);

        Map<String, Object> response = service.processAndBroadcast(raw);

        assertThat(response.get("pipelineCut")).isEqualTo(false);
        assertThat(response.get("type")).isEqualTo("town_road_render");
        assertThat(response.get("downstreamEligibleCount")).isEqualTo(2);
        ArgumentCaptor<List<ExternalOrderRecord>> pipelineInput = ArgumentCaptor.forClass(List.class);
        verify(middleLayer).processSnapshot(pipelineInput.capture());
        assertThat(pipelineInput.getValue()).extracting(ExternalOrderRecord::orderId)
                .containsExactly("confirmed", "inferred");
        assertThat(pipelineInput.getValue()).extracting(ExternalOrderRecord::status)
                .containsExactly("运输中", "运输中");
        verify(routePush, never()).warmPositionCacheForLineIds(anySet());
    }

    private VehicleOrderEligibilityService.VehicleDecision decision(
            ExternalOrderRecord order,
            boolean eligible,
            String state,
            String reason
    ) {
        return new VehicleOrderEligibilityService.VehicleDecision(
                order.vehicle().plate(), order.orderId(), order.lineId(), order.status(), order.updatedAt(),
                order.vehicle().carId(), eligible, state, reason, order.vehicle().currentCoords(),
                null, null, null, order.from().name(), order.from().coords(), null,
                "trip-" + order.vehicle().plate(), VehicleTripRuntimeService.TripPhase.LINEHAUL,
                List.of(order.orderId()), Set.of(), Set.of(order.orderId()), Set.of(), List.of(order.orderId()),
                Map.of(order.orderId(), VehicleTripRuntimeService.TripMemberState.CONFIRMED), Set.of());
    }

    private VehicleOrderChainStore.StoredOrder stored(ExternalOrderRecord order, long sequence) {
        return new VehicleOrderChainStore.StoredOrder(
                order.orderId() + "|" + order.lineId() + "|" + order.vehicle().plate(),
                order.orderId(), order.lineId(), order.vehicle().plate(), "OTHER",
                sequence, sequence, order);
    }

    private ExternalOrderRecord record(String orderId, String lineId, String plate, String status) {
        return new ExternalOrderRecord(
                orderId, lineId,
                new ExternalOrderRecord.Location("起点", "广东省", "佛山市", "南海区", "440605",
                        new double[]{113.1, 23.1}),
                new ExternalOrderRecord.Location("终点", "广东省", "肇庆市", "四会市", "441284",
                        new double[]{112.7, 23.3}),
                new ExternalOrderRecord.Vehicle(plate, "vehicle-" + orderId, "货物", 10d, "吨",
                        new double[]{113.0, 23.0}, 30d),
                status, "2026-07-23T01:00:00Z", false, true);
    }
}
