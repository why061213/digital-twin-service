package com.jushen.digitaltwin.townroad;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "dashboard.external-order")
public class ExternalOrderProperties {

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
}