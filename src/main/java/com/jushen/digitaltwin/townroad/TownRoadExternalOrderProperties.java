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
}
