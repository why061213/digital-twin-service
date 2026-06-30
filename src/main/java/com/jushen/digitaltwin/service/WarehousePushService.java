package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.model.WarehouseData;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WarehousePushService {

    private static final Logger log = LoggerFactory.getLogger(WarehousePushService.class);

    private final RealtimeWebSocketHandler webSocketHandler;
    private final WarehouseDataProvider dataProvider;

    public WarehousePushService(
            RealtimeWebSocketHandler webSocketHandler,
            WarehouseDataProvider dataProvider
    ) {
        this.webSocketHandler = webSocketHandler;
        this.dataProvider = dataProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pushAllWarehousesRise() {
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }

            List<Map<String, Object>> messages = pushWarehouseSnapshot();
            log.info("Pushed initial warehouse snapshot for {} warehouses", messages.size());
        }).start();
    }

    public List<Map<String, Object>> pushWarehouseSnapshot() {
        List<Map<String, Object>> messages = getWarehouseSnapshot();
        messages.forEach(webSocketHandler::broadcast);
        return messages;
    }

    public List<Map<String, Object>> getWarehouseSnapshot() {
        List<WarehouseData> allData = dataProvider.fetchAllWarehouseData();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (WarehouseData data : allData) {
            messages.add(warehouseMessage(data));
        }
        return messages;
    }

    private Map<String, Object> warehouseMessage(WarehouseData data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "warehouse_update");
        message.put("cityName", data.getCityName());
        message.put("action", "rise");
        message.put("displayData", data.getDisplayData());
        return message;
    }
}
