package com.jushen.digitaltwin.config;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ConfigData {
    // 路线分组
    private int roadGroupSize = 12;                     // 对应 dashboard.route.group-size
    private int roadGroupDisplayBaseMs = 8000;
    private int roadGroupDisplayAddMs = 200;
    private String defaultGroupStrategy = "business-priority";

    // 模拟速度
    private String simulationProfile = "test";          // dashboard.route.simulation-profile
    private double testSimulationSpeedKmh = 15000;
    private double realSimulationSpeedKmh = 80;

    // 位置推送
    private boolean passivePositionPushEnabled = false;
    private int truckPositionPushRateMs = 60000;

    // 相机巡游
    private int cameraFocusDurationMs = 20000;
    private int cameraOverviewDurationMs = 20000;
    private int cameraHeadquartersDurationMs = 20000;

    // 仓库面板
    private int warehousePanelWidth = 180;
    private int warehousePanelMaxHeight = 240;

    // 自动轮播
    // 自动轮播
    private int autoCarouselRoadGroupCycles = 2;
    private int autoCarouselChinaMapLoops = 1;
    private int autoCarouselChinaMapDurationMs = 30000; // 可以保留作兜底，不再作为主要切换条件
    private boolean autoCarouselEnabled = true;

    // getters & setters ......
//    public int getRoadGroupSize() { return roadGroupSize; }
//    public void setRoadGroupSize(int roadGroupSize) { this.roadGroupSize = roadGroupSize; }
//
//    public int getRoadGroupDisplayBaseMs() { return roadGroupDisplayBaseMs; }
//    public void setRoadGroupDisplayBaseMs(int roadGroupDisplayBaseMs) { this.roadGroupDisplayBaseMs = roadGroupDisplayBaseMs; }
//
//    public int getRoadGroupDisplayAddMs() { return roadGroupDisplayAddMs; }
//    public void setRoadGroupDisplayAddMs(int roadGroupDisplayAddMs) { this.roadGroupDisplayAddMs = roadGroupDisplayAddMs; }
//
//    public String getDefaultGroupStrategy() { return defaultGroupStrategy; }
//    public void setDefaultGroupStrategy(String defaultGroupStrategy) { this.defaultGroupStrategy = defaultGroupStrategy; }
//
//    public String getSimulationProfile() { return simulationProfile; }
//    public void setSimulationProfile(String simulationProfile) { this.simulationProfile = simulationProfile; }
//
//    public double getTestSimulationSpeedKmh() { return testSimulationSpeedKmh; }
//    public void setTestSimulationSpeedKmh(double testSimulationSpeedKmh) { this.testSimulationSpeedKmh = testSimulationSpeedKmh; }
//
//    public double getRealSimulationSpeedKmh() { return realSimulationSpeedKmh; }
//    public void setRealSimulationSpeedKmh(double realSimulationSpeedKmh) { this.realSimulationSpeedKmh = realSimulationSpeedKmh; }
//
//    public boolean isPassivePositionPushEnabled() { return passivePositionPushEnabled; }
//    public void setPassivePositionPushEnabled(boolean passivePositionPushEnabled) { this.passivePositionPushEnabled = passivePositionPushEnabled; }
//
//    public int getTruckPositionPushRateMs() { return truckPositionPushRateMs; }
//    public void setTruckPositionPushRateMs(int truckPositionPushRateMs) { this.truckPositionPushRateMs = truckPositionPushRateMs; }
//
//    public int getCameraFocusDurationMs() { return cameraFocusDurationMs; }
//    public void setCameraFocusDurationMs(int cameraFocusDurationMs) { this.cameraFocusDurationMs = cameraFocusDurationMs; }
//
//    public int getCameraOverviewDurationMs() { return cameraOverviewDurationMs; }
//    public void setCameraOverviewDurationMs(int cameraOverviewDurationMs) { this.cameraOverviewDurationMs = cameraOverviewDurationMs; }
//
//    public int getCameraHeadquartersDurationMs() { return cameraHeadquartersDurationMs; }
//    public void setCameraHeadquartersDurationMs(int cameraHeadquartersDurationMs) { this.cameraHeadquartersDurationMs = cameraHeadquartersDurationMs; }
//
//    public int getWarehousePanelWidth() { return warehousePanelWidth; }
//    public void setWarehousePanelWidth(int warehousePanelWidth) { this.warehousePanelWidth = warehousePanelWidth; }
//
//    public int getWarehousePanelMaxHeight() { return warehousePanelMaxHeight; }
//    public void setWarehousePanelMaxHeight(int warehousePanelMaxHeight) { this.warehousePanelMaxHeight = warehousePanelMaxHeight; }
//
//    public int getAutoCarouselRoadGroupCycles() { return autoCarouselRoadGroupCycles; }
//    public void setAutoCarouselRoadGroupCycles(int autoCarouselRoadGroupCycles) { this.autoCarouselRoadGroupCycles = autoCarouselRoadGroupCycles; }
//
//    public int getAutoCarouselChinaMapDurationMs() { return autoCarouselChinaMapDurationMs; }
//    public void setAutoCarouselChinaMapDurationMs(int autoCarouselChinaMapDurationMs) { this.autoCarouselChinaMapDurationMs = autoCarouselChinaMapDurationMs; }
//
//    public boolean isAutoCarouselEnabled() { return autoCarouselEnabled; }
//    public void setAutoCarouselEnabled(boolean autoCarouselEnabled) { this.autoCarouselEnabled = autoCarouselEnabled; }
}
