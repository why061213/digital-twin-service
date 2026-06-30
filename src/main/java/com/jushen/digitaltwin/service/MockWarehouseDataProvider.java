package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.config.WarehouseProperties;
import com.jushen.digitaltwin.model.WarehouseData;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class MockWarehouseDataProvider implements WarehouseDataProvider {

    private final List<WarehouseProperties.WarehouseConfig> warehouseConfigs;

    public MockWarehouseDataProvider(WarehouseProperties warehouseProperties) {
        this.warehouseConfigs = warehouseProperties.getWarehouses();
    }

    @Override
    public List<WarehouseData> fetchAllWarehouseData() {
        List<WarehouseData> list = new ArrayList<>();
        for (WarehouseProperties.WarehouseConfig config : warehouseConfigs) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("label", config.getLabel());
            data.put("inventory", ThreadLocalRandom.current().nextInt(2000, 8000));
            data.put("todayIn", ThreadLocalRandom.current().nextInt(50, 300));
            data.put("todayOut", ThreadLocalRandom.current().nextInt(40, 280));
            data.put("status", ThreadLocalRandom.current().nextDouble() < 0.9 ? "正常" : "繁忙");
            list.add(new WarehouseData(config.getCity(), data));
        }
        return list;
    }
}