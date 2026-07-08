package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TownRoadRenderService {

    private final ExternalOrderClient externalOrderClient;
    private final TownRoadMiddleLayer middleLayer;
    private final RealtimeWebSocketHandler realtimeWebSocketHandler;

    public TownRoadRenderService(
            ExternalOrderClient externalOrderClient,
            TownRoadMiddleLayer middleLayer,
            RealtimeWebSocketHandler realtimeWebSocketHandler
    ) {
        this.externalOrderClient = externalOrderClient;
        this.middleLayer = middleLayer;
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
    }

    public Map<String, Object> fetchProcessAndBroadcast(Map<String, Object> payload) {
        List<ExternalOrderRecord> rawOrders = externalOrderClient.postOrders(payload);
        return processAndBroadcast(rawOrders);
    }

    public Map<String, Object> processAndBroadcast(List<ExternalOrderRecord> rawOrders) {
        ExternalOrderSnapshotResult result = middleLayer.processSnapshot(rawOrders);

        for (TownRoadRenderCommand command : result.commands()) {
            realtimeWebSocketHandler.broadcast(command);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("message", "town_road_render commands broadcasted");
        response.put("rawCount", result.rawCount());
        response.put("normalizedCount", result.normalizedCount());
        response.put("shortHaulCount", result.shortHaulCount());
        response.put("commandCount", result.commands().size());
        response.put("diff", result.diff().toMap());
        response.put("commands", result.commands());
        return response;
    }

    public TownRoadMiddleLayer middleLayer() {
        return middleLayer;
    }
}