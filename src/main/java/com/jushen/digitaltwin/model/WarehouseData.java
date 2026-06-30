package com.jushen.digitaltwin.model;

import java.util.Map;

public class WarehouseData {
    private String cityName;
    private Map<String, Object> displayData;

    public WarehouseData(String cityName, Map<String, Object> displayData) {
        this.cityName = cityName;
        this.displayData = displayData;
    }

    public String getCityName() { return cityName; }
    public Map<String, Object> getDisplayData() { return displayData; }
}