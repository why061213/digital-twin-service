package com.jushen.digitaltwin.externalorder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "dashboard.external-order")
public class ExternalOrderProperties {

    private boolean enabled = true;
    private String postUrl = "";
    private boolean fullSnapshot = true;
    private boolean broadcastDiff = true;
    private boolean scheduledSyncEnabled = false;
    private long syncRateMs = 10000;
    private long connectTimeoutMs = 5000;
    private long requestTimeoutMs = 12000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPostUrl() {
        return postUrl;
    }

    public void setPostUrl(String postUrl) {
        this.postUrl = postUrl;
    }

    public boolean isFullSnapshot() {
        return fullSnapshot;
    }

    public void setFullSnapshot(boolean fullSnapshot) {
        this.fullSnapshot = fullSnapshot;
    }

    public boolean isBroadcastDiff() {
        return broadcastDiff;
    }

    public void setBroadcastDiff(boolean broadcastDiff) {
        this.broadcastDiff = broadcastDiff;
    }

    public boolean isScheduledSyncEnabled() {
        return scheduledSyncEnabled;
    }

    public void setScheduledSyncEnabled(boolean scheduledSyncEnabled) {
        this.scheduledSyncEnabled = scheduledSyncEnabled;
    }

    public long getSyncRateMs() {
        return syncRateMs;
    }

    public void setSyncRateMs(long syncRateMs) {
        this.syncRateMs = syncRateMs;
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