package com.jushen.digitaltwin.townroad;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地坐标库 + 高德地图 API 配置。
 * 在 application.yml 中配置：dashboard.coord-db.amap-key
 */
@Component
@ConfigurationProperties(prefix = "dashboard.coord-db")
public class CoordDbProperties {

    /** 高德地图 Web API Key（用于地理编码） */
    private String amapKey = "";

    /** 高德地图安全密钥（用于请求签名） */
    private String amapSecret = "";

    /** 高德 API 调用间隔（毫秒），避免 QPS 超限。0.4s/次 ≈ 2.5次/秒 */
    private long amapRateLimitMs = 400;

    /** coord-db 本地库目录（classpath 相对路径） */
    private String localPath = "coord-db";

    /** 高德 API 请求超时（毫秒） */
    private long requestTimeoutMs = 12000;

    public String getAmapKey() {
        return amapKey;
    }

    public void setAmapKey(String amapKey) {
        this.amapKey = amapKey;
    }

    public String getAmapSecret() {
        return amapSecret;
    }

    public void setAmapSecret(String amapSecret) {
        this.amapSecret = amapSecret;
    }

    public long getAmapRateLimitMs() {
        return amapRateLimitMs;
    }

    public void setAmapRateLimitMs(long amapRateLimitMs) {
        this.amapRateLimitMs = amapRateLimitMs;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }
}
