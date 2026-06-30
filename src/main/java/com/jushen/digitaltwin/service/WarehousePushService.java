package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.config.CameraProperties;
import com.jushen.digitaltwin.config.WarehouseProperties;
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

    private final CameraProperties cameraProperties;

    private final RealtimeWebSocketHandler webSocketHandler;
    private final WarehouseDataProvider dataProvider;
    private final List<WarehouseProperties.WarehouseConfig> warehouseConfigs;

    public WarehousePushService(
            CameraProperties cameraProperties,
            RealtimeWebSocketHandler webSocketHandler,
            WarehouseDataProvider dataProvider,
            WarehouseProperties warehouseProperties
    ) {
        this.cameraProperties = cameraProperties;
        this.webSocketHandler = webSocketHandler;
        this.dataProvider = dataProvider;
        // 从配置中获取所有仓库定义
        this.warehouseConfigs = warehouseProperties.getWarehouses();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pushAllWarehousesRise() {
        new Thread(() -> {
            try {
                Thread.sleep(3000); // 等待前端连接
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }

            // 1. 推送所有仓库升起（快照）
            List<Map<String, Object>> messages = getWarehouseSnapshot();
            messages.forEach(webSocketHandler::broadcast);
            log.info("Pushed initial warehouse snapshot for {} warehouses", messages.size());

            // 2. 构建仓库城市名列表
            List<String> warehouseCities = new ArrayList<>();
            for (WarehouseProperties.WarehouseConfig config : warehouseConfigs) {
                warehouseCities.add(config.getCity());
            }

            // 3. 镜头序列
            // 聚焦总部佛山
            while(true){
                sendCameraControl(webSocketHandler, List.of("佛山市"), "focus");
                sleep(cameraProperties.getHeadquartersDuration());

                // 俯瞰所有仓库
                sendCameraControl(webSocketHandler, warehouseCities, "overview");
                sleep(cameraProperties.getOverviewDuration());

                // 逐个聚焦每个仓库
                for (String city : warehouseCities) {
                    sendCameraControl(webSocketHandler, List.of(city), "focus");
                    sleep(cameraProperties.getFocusDuration());
                }

                // 回到俯瞰
                sendCameraControl(webSocketHandler, warehouseCities, "overview");
            }

        }).start();
    }

    public List<Map<String, Object>> getWarehouseSnapshot() {
        List<WarehouseData> allData = dataProvider.fetchAllWarehouseData();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (WarehouseData data : allData) {
            messages.add(warehouseMessage(data));
        }
        return messages;
    }

    // 辅助方法：构建仓库消息
    private Map<String, Object> warehouseMessage(WarehouseData data) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "warehouse_update");
        message.put("cityName", data.getCityName());
        message.put("action", "rise");
        message.put("displayData", data.getDisplayData());
        return message;
    }

    // 发送镜头控制消息
    private void sendCameraControl(RealtimeWebSocketHandler handler, List<String> cityNames, String mode) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "camera_control");
        msg.put("cityNames", cityNames);
        msg.put("mode", mode);
        handler.broadcast(msg);
    }

    // 简单休眠
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<Map<String, Object>> pushWarehouseSnapshot() {
        List<Map<String, Object>> messages = getWarehouseSnapshot();
        messages.forEach(webSocketHandler::broadcast);
        return messages;
    }
}