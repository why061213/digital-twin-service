package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.service.RoutePushService;
import com.jushen.digitaltwin.service.PositionSnapshot;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TownRoadRenderServicePipelineCutTest {
    @Test
    void dynamicInsertionFlowsFromSnapshotThroughTripEligibilityIntoRm2AndRoutePush() {
        TownRoadExternalOrderClient client = mock(TownRoadExternalOrderClient.class);
        TownRoadMiddleLayer middleLayer = mock(TownRoadMiddleLayer.class);
        RealtimeWebSocketHandler websocket = mock(RealtimeWebSocketHandler.class);
        AmapGeocodeClient geocode = mock(AmapGeocodeClient.class);
        TownRoadCoordinateResolver resolver = mock(TownRoadCoordinateResolver.class);
        RoutePushService routePush = mock(RoutePushService.class);
        DailyOrderStatisticsService statistics = mock(DailyOrderStatisticsService.class);
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        ProviderTrajectoryClient trajectory = mock(ProviderTrajectoryClient.class);
        TownRoadExternalOrderProperties properties = new TownRoadExternalOrderProperties();
        properties.setVehicleOrderChainExperimentEnabled(true);
        properties.setIgnoreOrdersWithoutRealPosition(true);
        properties.setTreatLoadingUnloadingAsTransporting(true);

        ExternalOrderRecord onboard = recordWithStops(
                "o1", "line-o1", "粤A90001", "运输中",
                new double[]{113.10, 23.00}, new double[]{112.70, 23.00});
        ExternalOrderRecord inserted = recordWithStops(
                "o2", "line-o2", "粤A90001", "待装载",
                new double[]{112.95, 23.00}, new double[]{112.80, 23.00});
        List<ExternalOrderRecord> raw = List.of(onboard, inserted);
        List<VehicleOrderChainStore.StoredOrder> stored = List.of(stored(onboard, 1), stored(inserted, 2));

        when(middleLayer.expandVehicleInstances(raw)).thenReturn(raw);
        when(middleLayer.instanceIdFor(any(ExternalOrderRecord.class)))
                .thenAnswer(invocation -> ((ExternalOrderRecord) invocation.getArgument(0)).lineId());
        when(store.ingest(raw)).thenReturn(new VehicleOrderChainStore.IngestResult(
                2, 2, 0, 2, 0, 0, 0, 0, 2, 0,
                2, 1, List.of("2026-07-26", "2026-07-27"), "store", "daily"));
        when(store.recentStoredOrders()).thenReturn(stored);
        when(store.latestObservedStoredOrders()).thenReturn(stored);
        when(store.writeEligibilityAnalysis(any())).thenReturn("analysis.json");
        when(routePush.refreshProviderVehicleDirectoryNow()).thenReturn(Map.of("vehicleCount", 1));
        when(routePush.prepareProviderPositionVehicle(any(), any(), any())).thenReturn(true);
        when(routePush.warmPositionCacheForLineIds(anySet())).thenReturn(Map.of("refreshed", 1));
        when(routePush.providerVehicleIdForLineId("line-o1")).thenReturn("vehicle-90001");
        when(routePush.freshProviderPosition("line-o1")).thenReturn(
                PositionSnapshot.fromProvider("line-o1", "vehicle-90001", "粤A90001", "粤A90001",
                        113.00, 23.00, 35));
        when(routePush.hasFreshProviderPosition(anyString())).thenReturn(true);
        when(geocode.reverseGeocode(any(double[].class))).thenReturn(
                new ExternalOrderRecord.Location("广东省佛山市", "广东省", "佛山市", "南海区", "440605",
                        new double[]{113.00, 23.00}));

        OrderSnapshotDiff emptyDiff = new OrderSnapshotDiff(
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        when(middleLayer.processSnapshot(anyList(), any())).thenAnswer(invocation -> {
            List<ExternalOrderRecord> input = invocation.getArgument(0);
            ExternalOrderRecord synthetic = input.get(0);
            return new ExternalOrderSnapshotResult(
                    1, 1, 0, 0, List.of(normalized(synthetic)), List.of(), List.of(), emptyDiff);
        });
        when(routePush.routeRuntimeMetrics(anyString())).thenReturn(new RoutePushService.RouteRuntimeMetrics(
                35d, 42d, 4_320_000L, "vehicle-90001", "粤A90001", true, "provider",
                List.of(new double[]{113.00, 23.00}, new double[]{112.95, 23.00}), "trip-path"));
        when(statistics.snapshot()).thenReturn(new DailyOrderStatisticsService.DailyOrderStatistics(
                "2026-07-27", 0, 0, 0, 0, 1, Instant.now().toString(), null));

        VehicleTripRuntimeService tripRuntime = new VehicleTripRuntimeService(store);
        VehicleOrderEligibilityService eligibility = new VehicleOrderEligibilityService(
                routePush, middleLayer, properties, store, trajectory, tripRuntime);
        TownRoadRenderService service = new TownRoadRenderService(
                client, middleLayer, websocket, geocode, resolver, routePush,
                properties, statistics, store, eligibility);

        service.processAndBroadcast(raw);

        ArgumentCaptor<List<ExternalOrderRecord>> pipelineInput = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<double[]>>> waypointInput = ArgumentCaptor.forClass(Map.class);
        verify(middleLayer).processSnapshot(pipelineInput.capture(), waypointInput.capture());
        assertThat(pipelineInput.getValue()).hasSize(1);
        ExternalOrderRecord synthetic = pipelineInput.getValue().get(0);
        assertThat(synthetic.orderId()).startsWith("trip-粤A90001");
        assertThat(synthetic.lineId()).isEqualTo("trip::" + synthetic.orderId());
        assertThat(synthetic.status()).isEqualTo("运输中");
        assertThat(synthetic.from().coords()).containsExactly(113.10, 23.00);
        // 从插入装载点 112.95 出发，112.80 比 112.70 更近，因此先途经 112.80，
        // 更远的 112.70 才是合并路线最终终点。
        assertThat(synthetic.to().coords()).containsExactly(112.70, 23.00);
        assertThat(synthetic.vehicle().cargoWeight()).isEqualTo(20d);
        assertThat(waypointInput.getValue()).containsOnlyKeys(synthetic.lineId());
        assertThat(waypointInput.getValue().get(synthetic.lineId()))
                .containsExactly(new double[]{112.95, 23.00}, new double[]{112.80, 23.00});

        assertThat(service.getLatestRm2Snapshot().routes()).hasSize(1);
        Map<String, Object> meta = service.getLatestRm2Snapshot().routes().get(0).meta();
        assertThat(meta).containsEntry("tripId", synthetic.orderId())
                .containsEntry("visualKey", synthetic.orderId())
                .containsKey("currentLegId")
                .containsEntry("targetOrderInstanceId", stored.get(1).key())
                .containsEntry("targetAction", "PICKUP")
                .containsEntry("tripPhase", "COLLECTING")
                .containsEntry("tripDecision", "EN_ROUTE_TO_PICKUP")
                .containsEntry("positionQuality", "FRESH")
                .containsEntry("pendingOrderCount", 1)
                .containsEntry("onboardOrderCount", 1)
                .containsEntry("completedOrderCount", 0);
        assertThat((List<?>) meta.get("tripStops")).hasSize(4);
        verify(routePush).setTripRuntimeMetadata(
                org.mockito.ArgumentMatchers.eq(synthetic.lineId()),
                org.mockito.ArgumentMatchers.argThat(runtimeMeta ->
                        "EN_ROUTE_TO_PICKUP".equals(runtimeMeta.get("tripDecision"))));
        verify(routePush).dispatchTownRoute(
                org.mockito.ArgumentMatchers.eq(synthetic.lineId()),
                any(), any(), any(), any(), any(), any(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any());
        verify(routePush).syncRm2PositionGroups(any(), anyString());
    }

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
        when(geocode.reverseGeocode(any(double[].class))).thenReturn(
                new ExternalOrderRecord.Location("江西省赣州市", "江西省", "赣州市", "章贡区", "360702",
                        new double[]{113.0, 23.0}));
        when(store.ingest(raw)).thenReturn(new VehicleOrderChainStore.IngestResult(
                3, 3, 0, 3, 0, 0, 0, 0, 3, 0,
                3, 3, List.of("2026-07-22", "2026-07-23"), "store", "daily"));
        when(store.recentStoredOrders()).thenReturn(List.of(
                stored(confirmed, 1), stored(inferred, 2), stored(rejected, 3)));
        when(eligibility.analyzeLatestVehicleOrders()).thenReturn(new VehicleOrderEligibilityService.EligibilityReport(
                Instant.now().toString(), true, Map.of("vehicleCount", 3), Map.of("refreshed", 3),
                3, 2, 1, List.of(
                decision(confirmed, true, "TRANSPORTING", "latest-order-is-transporting"),
                decision(inferred, true, "EN_ROUTE_TO_PICKUP", "onboard-orders-with-confirmed-pickup-ahead"),
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
                .containsExactly("trip-粤A10001", "trip-粤A10002");
        assertThat(pipelineInput.getValue()).extracting(ExternalOrderRecord::lineId)
                .containsExactly("trip::trip-粤A10001", "trip::trip-粤A10002");
        assertThat(pipelineInput.getValue()).extracting(order -> order.from().coords())
                .allSatisfy(coords -> assertThat(coords).containsExactly(113.0, 23.0));
        assertThat(pipelineInput.getValue()).extracting(order -> order.from().province())
                .containsOnly("江西省");
        assertThat(pipelineInput.getValue()).extracting(ExternalOrderRecord::status)
                .containsExactly("运输中", "运输中");
        verify(routePush, never()).warmPositionCacheForLineIds(anySet());
        verify(routePush).aliasFreshProviderPosition(
                "line-confirmed", "trip::trip-粤A10001", "粤A10001", "vehicle-confirmed");
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
                VehicleTripRuntimeService.PositionQuality.FRESH,
                List.of(order.orderId()), Set.of(), Set.of(order.orderId()), Set.of(), List.of(order.orderId()),
                Map.of(order.orderId(), VehicleTripRuntimeService.TripMemberState.CONFIRMED), Set.of(),
                "trip::trip-" + order.vehicle().plate(), "leg-current", 2L,
                order.orderId() + "::DELIVERY", order.orderId(), "DELIVERY",
                order.vehicle().currentCoords(), order.to().coords(), order.to().name());
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

    private ExternalOrderRecord recordWithStops(
            String orderId,
            String lineId,
            String plate,
            String status,
            double[] from,
            double[] to
    ) {
        return new ExternalOrderRecord(
                orderId, lineId,
                new ExternalOrderRecord.Location("装货点-" + orderId, "广东省", "佛山市", "南海区", "440605", from),
                new ExternalOrderRecord.Location("卸货点-" + orderId, "广东省", "肇庆市", "四会市", "441284", to),
                new ExternalOrderRecord.Vehicle(plate, "vehicle-90001", "货物", 10d, "吨", from, 35d),
                status, Instant.now().toString(), false, true);
    }

    private NormalizedTownRoadOrder normalized(ExternalOrderRecord order) {
        List<double[]> coordinates = List.of(order.from().coords(), order.to().coords());
        return new NormalizedTownRoadOrder(
                order.orderId(), order.lineId(), order.lineId(), order.vehicle().plate(),
                "from", "to", "from->to", "440000", "440000",
                List.of(List.of("440000")), List.of("440000"), List.of(0),
                List.of("440600", "441200"), List.of("佛山市", "肇庆市"), coordinates, coordinates,
                42d, 35d, 4_320_000L, "test", "group", "测试组",
                order.from(), order.to(), order.vehicle(), order.status(), order.updatedAt(),
                false, true, "data", "route");
    }
}
