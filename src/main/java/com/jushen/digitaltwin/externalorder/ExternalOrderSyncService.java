package com.jushen.digitaltwin.externalorder;

import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExternalOrderSyncService {

    private final ExternalOrderClient client;
    private final ExternalOrderStore store;
    private final ExternalOrderProperties properties;
    private final RealtimeWebSocketHandler webSocketHandler;

    public ExternalOrderSyncService(
            ExternalOrderClient client,
            ExternalOrderStore store,
            ExternalOrderProperties properties,
            RealtimeWebSocketHandler webSocketHandler
    ) {
        this.client = client;
        this.store = store;
        this.properties = properties;
        this.webSocketHandler = webSocketHandler;
    }

    public Map<String, Object> sync(Map<String, Object> payload) {
        List<ExternalOrderRecord> records = client.postOrders(payload);

        ExternalOrderDiff diff = store.applySnapshot(
                records,
                properties.isFullSnapshot()
        );

        if (properties.isBroadcastDiff()) {
            broadcastDiff(diff);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("received", records.size());
        result.put("total", store.allRoutes().size());
        result.put("added", diff.addedCount());
        result.put("updated", diff.updatedCount());
        result.put("deleted", diff.deletedCount());
        result.put("unchanged", diff.unchangedCount());
        result.put("routeChanged", diff.routeChangedCount());
        return result;
    }

    @Scheduled(fixedRateString = "${dashboard.external-order.sync-rate-ms:900000}")
    public void scheduledSync() {
        if (!properties.isScheduledSyncEnabled()) {
            return;
        }

        sync(Map.of());
    }

    public List<ExternalOrderRoute> allRoutes() {
        return store.allRoutes();
    }

    public List<ExternalOrderRoute> findSameOdOrders(String fromKey, String toKey) {
        return store.findSameOdOrders(fromKey, toKey);
    }

    public List<ExternalOrderRoute> findOrdersAlongRoute(List<String> routeNodeKeys) {
        return store.findOrdersAlongRoute(routeNodeKeys);
    }

    private void broadcastDiff(ExternalOrderDiff diff) {
        for (ExternalOrderRoute route : diff.deleted()) {
            webSocketHandler.broadcast(removeMessage(route));
        }

        for (ExternalOrderRoute route : diff.added()) {
            webSocketHandler.broadcast(routeMessage(route, true));
            broadcastPosition(route);
        }

        for (ExternalOrderRoute route : diff.routeChanged()) {
            webSocketHandler.broadcast(routeMessage(route, false));
            broadcastPosition(route);
        }

        for (ExternalOrderRoute route : diff.updated()) {
            if (diff.routeChanged().stream().anyMatch(item -> item.lineId().equals(route.lineId()))) {
                continue;
            }

            broadcastPosition(route);
        }
    }

    private Map<String, Object> routeMessage(ExternalOrderRoute route, boolean created) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "road_path");
        message.put("lineId", route.lineId());
        message.put("orderId", route.orderId());
        message.put("orderFamilyId", route.orderFamilyId());
        message.put("pathKey", route.pathKey());
        message.put("from", route.from());
        message.put("to", route.to());
        message.put("coordinates", route.coordinates());
        message.put("created", created);
        message.put("routeLengthKm", route.routeLengthKm());
        message.put("speedKmh", route.speedKmh());
        message.put("travelDurationMs", route.travelDurationMs());

        message.put("plate", route.plate());
        message.put("carId", route.carId());
        message.put("cargoWeight", route.cargoWeight());
        message.put("cargoUnit", route.cargoUnit());
        message.put("status", route.status());

        return message;
    }

    private void broadcastPosition(ExternalOrderRoute route) {
        double[] position = route.currentCoords();

        if (position == null || position.length < 2) {
            position = route.fromCoords();
        }

        if (position == null || position.length < 2) {
            return;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "truck_position");
        message.put("lineId", route.lineId());
        message.put("position", position);
        message.put("speedKmh", route.speedKmh());
        message.put("status", route.status());
        message.put("plate", route.plate());
        message.put("carId", route.carId());

        webSocketHandler.broadcast(message);
    }

    private Map<String, Object> removeMessage(ExternalOrderRoute route) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "road_remove");
        message.put("lineId", route.lineId());
        message.put("orderId", route.orderId());
        return message;
    }
}
