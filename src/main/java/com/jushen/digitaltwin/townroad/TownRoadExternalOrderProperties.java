package com.jushen.digitaltwin.townroad;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "dashboard.websocket.external-order")
public class TownRoadExternalOrderProperties {

    /**
     * 外部订单 POST 地址。
     */
    private String postUrl = "";

    /**
     * true 表示 to.coords 缺失时不进入 town_road_render。
     */
    private boolean requireRenderableCoords = true;

    private long connectTimeoutMs = 5000;
    private long requestTimeoutMs = 12000;
    private int maxCandidateProvinceCount = 4;
    private int maxCandidatePathCount = 4;
    private double candidateToleranceRatio = 0.30;
    private int candidateAbsoluteSlack = 250;

    /** 已完成订单保留时长（分钟）。
     *  前端做模拟+修正，如果 order.updatedAt 在这个时间窗口内，
     *  即使状态为"已完成"也推送给前端做校准；超过则过滤。 */
    private int completedRetentionMinutes = 30;

    /** 是否在应用启动后自动拉取 RM2 外部订单快照。 */
    private boolean autoSyncEnabled = true;
    private long autoSyncInitialDelayMs = 5000;
    private long autoSyncFixedDelayMs = 900000;

    /**
     * 开启后，仅保留能由车牌解析出供应商 vehicleId 的订单。
     * 默认关闭，保留“真实位置优先，失败时模拟”的兼容行为。
     */
    private boolean ignoreOrdersWithoutRealPosition = false;

    /**
     * 上游状态诊断开关：开启后把装载/卸载中的车辆临时按“运输中”走完整运行链路。
     * 仅用于核对上游状态是否错误，验证结束后应关闭。
     */
    private boolean treatLoadingUnloadingAsTransporting = false;

    /** 新中间层实验：落盘订单/车辆链后立即截断所有后续处理。 */
    private boolean vehicleOrderChainExperimentEnabled = false;
    private String vehicleOrderChainStorePath = "runtime-data/vehicle-order-chain";

    public String getPostUrl() {
        return postUrl;
    }

    public void setPostUrl(String postUrl) {
        this.postUrl = postUrl;
    }

    public boolean isRequireRenderableCoords() {
        return requireRenderableCoords;
    }

    public void setRequireRenderableCoords(boolean requireRenderableCoords) {
        this.requireRenderableCoords = requireRenderableCoords;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getMaxCandidateProvinceCount() {
        return maxCandidateProvinceCount;
    }

    public void setMaxCandidateProvinceCount(int maxCandidateProvinceCount) {
        this.maxCandidateProvinceCount = maxCandidateProvinceCount;
    }

    public int getMaxCandidatePathCount() {
        return maxCandidatePathCount;
    }

    public void setMaxCandidatePathCount(int maxCandidatePathCount) {
        this.maxCandidatePathCount = maxCandidatePathCount;
    }

    public double getCandidateToleranceRatio() {
        return candidateToleranceRatio;
    }

    public void setCandidateToleranceRatio(double candidateToleranceRatio) {
        this.candidateToleranceRatio = candidateToleranceRatio;
    }

    public int getCandidateAbsoluteSlack() {
        return candidateAbsoluteSlack;
    }

    public void setCandidateAbsoluteSlack(int candidateAbsoluteSlack) {
        this.candidateAbsoluteSlack = candidateAbsoluteSlack;
    }

    public int getCompletedRetentionMinutes() {
        return completedRetentionMinutes;
    }

    public void setCompletedRetentionMinutes(int completedRetentionMinutes) {
        this.completedRetentionMinutes = completedRetentionMinutes;
    }

    public boolean isAutoSyncEnabled() {
        return autoSyncEnabled;
    }

    public void setAutoSyncEnabled(boolean autoSyncEnabled) {
        this.autoSyncEnabled = autoSyncEnabled;
    }

    public long getAutoSyncInitialDelayMs() {
        return autoSyncInitialDelayMs;
    }

    public void setAutoSyncInitialDelayMs(long autoSyncInitialDelayMs) {
        this.autoSyncInitialDelayMs = autoSyncInitialDelayMs;
    }

    public long getAutoSyncFixedDelayMs() {
        return autoSyncFixedDelayMs;
    }

    public void setAutoSyncFixedDelayMs(long autoSyncFixedDelayMs) {
        this.autoSyncFixedDelayMs = autoSyncFixedDelayMs;
    }

    public boolean isIgnoreOrdersWithoutRealPosition() {
        return ignoreOrdersWithoutRealPosition;
    }

    public void setIgnoreOrdersWithoutRealPosition(boolean ignoreOrdersWithoutRealPosition) {
        this.ignoreOrdersWithoutRealPosition = ignoreOrdersWithoutRealPosition;
    }

    public boolean isTreatLoadingUnloadingAsTransporting() {
        return treatLoadingUnloadingAsTransporting;
    }

    public void setTreatLoadingUnloadingAsTransporting(boolean treatLoadingUnloadingAsTransporting) {
        this.treatLoadingUnloadingAsTransporting = treatLoadingUnloadingAsTransporting;
    }

    public boolean isVehicleOrderChainExperimentEnabled() {
        return vehicleOrderChainExperimentEnabled;
    }

    public void setVehicleOrderChainExperimentEnabled(boolean vehicleOrderChainExperimentEnabled) {
        this.vehicleOrderChainExperimentEnabled = vehicleOrderChainExperimentEnabled;
    }

    public String getVehicleOrderChainStorePath() {
        return vehicleOrderChainStorePath;
    }

    public void setVehicleOrderChainStorePath(String vehicleOrderChainStorePath) {
        this.vehicleOrderChainStorePath = vehicleOrderChainStorePath;
    }
}
