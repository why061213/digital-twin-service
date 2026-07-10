package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TownRoadRenderService {

    private final TownRoadExternalOrderClient townRoadExternalOrderClient;
    private final TownRoadMiddleLayer middleLayer;
    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private Map<String, Object> lastResult = Map.of();

    public Map<String, Object> latestResult() {
        return lastResult;
    }

    public TownRoadRenderService(
            TownRoadExternalOrderClient townRoadExternalOrderClient,
            TownRoadMiddleLayer middleLayer,
            RealtimeWebSocketHandler realtimeWebSocketHandler
    ) {
        this.townRoadExternalOrderClient = townRoadExternalOrderClient;
        this.middleLayer = middleLayer;
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
    }

    public Map<String, Object> fetchProcessAndBroadcast() {
        List<ExternalOrderRecord> rawOrders = townRoadExternalOrderClient.fetchOrders();
        return processAndBroadcast(rawOrders);
    }

    public Map<String, Object> processAndBroadcast(List<ExternalOrderRecord> rawOrders) {
        ExternalOrderSnapshotResult result = middleLayer.processSnapshot(rawOrders);

        for (TownRoadRenderCommand command : result.commands()) {
            realtimeWebSocketHandler.broadcast(command);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("type","town_road_render");
        response.put("message", "town_road_render commands broadcasted");
        response.put("rawCount", result.rawCount());
        response.put("normalizedCount", result.normalizedCount());
        response.put("shortHaulCount", result.shortHaulCount());
        response.put("commandCount", result.commands().size());
        response.put("displayMode", result.commands().size() > 1 ? "multi_source_rotation" : "single_source");
        response.put("diff", result.diff().toMap());
        response.put("commands", result.commands());
        this.lastResult = response;
        return response;
    }

    public TownRoadMiddleLayer middleLayer() {
        return middleLayer;
    }
}