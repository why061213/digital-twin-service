package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.service.RoutePushService;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TownRoadRenderServicePipelineCutTest {
    @Test
    void experimentStopsBeforePositionRoutePlanningAndPublishing() {
        TownRoadExternalOrderClient client = mock(TownRoadExternalOrderClient.class);
        TownRoadMiddleLayer middleLayer = mock(TownRoadMiddleLayer.class);
        RealtimeWebSocketHandler websocket = mock(RealtimeWebSocketHandler.class);
        AmapGeocodeClient geocode = mock(AmapGeocodeClient.class);
        TownRoadCoordinateResolver resolver = mock(TownRoadCoordinateResolver.class);
        RoutePushService routePush = mock(RoutePushService.class);
        DailyOrderStatisticsService statistics = mock(DailyOrderStatisticsService.class);
        VehicleOrderChainStore store = mock(VehicleOrderChainStore.class);
        TownRoadExternalOrderProperties properties = new TownRoadExternalOrderProperties();
        properties.setVehicleOrderChainExperimentEnabled(true);
        ExternalOrderRecord record = mock(ExternalOrderRecord.class);
        when(middleLayer.expandVehicleInstances(List.of(record))).thenReturn(List.of(record));
        when(store.ingest(List.of(record))).thenReturn(new VehicleOrderChainStore.IngestResult(
                1, 1, 0, 1, 0, 0, 0, 0, 1, 0,
                1, 1, List.of("2026-07-21", "2026-07-22"), "store", "daily"));
        TownRoadRenderService service = new TownRoadRenderService(
                client, middleLayer, websocket, geocode, resolver, routePush,
                properties, statistics, store);

        Map<String, Object> response = service.processAndBroadcast(List.of(record));

        assertThat(response.get("pipelineCut")).isEqualTo(true);
        assertThat(response.get("type")).isEqualTo("vehicle_order_chain_store");
        verify(middleLayer, never()).processSnapshot(org.mockito.ArgumentMatchers.anyList());
        verify(routePush, never()).warmPositionCacheForLineIds(org.mockito.ArgumentMatchers.anySet());
        verify(websocket, never()).broadcastToScopeSubscribers(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
    }
}
