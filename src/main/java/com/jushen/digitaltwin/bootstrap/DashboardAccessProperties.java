package com.jushen.digitaltwin.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "dashboard.access")
public class DashboardAccessProperties {

    private boolean enabled = true;
    private boolean trustLoopback = true;
    private List<String> allowedMacAddresses = new ArrayList<>();
    private String deviceToken = "";
    private Duration sessionTtl = Duration.ofHours(8);
    private Duration sessionRefreshAfter = Duration.ofHours(4);
    private Duration sessionRefreshGrace = Duration.ofMinutes(2);
    private int maxActiveSessions = 256;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrustLoopback() {
        return trustLoopback;
    }

    public void setTrustLoopback(boolean trustLoopback) {
        this.trustLoopback = trustLoopback;
    }

    public List<String> getAllowedMacAddresses() {
        return allowedMacAddresses;
    }

    public void setAllowedMacAddresses(List<String> allowedMacAddresses) {
        this.allowedMacAddresses = allowedMacAddresses == null ? new ArrayList<>() : allowedMacAddresses;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken == null ? "" : deviceToken;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl == null ? Duration.ofHours(8) : sessionTtl;
    }

    public Duration getSessionRefreshAfter() {
        return sessionRefreshAfter;
    }

    public void setSessionRefreshAfter(Duration sessionRefreshAfter) {
        this.sessionRefreshAfter = sessionRefreshAfter == null ? Duration.ofHours(4) : sessionRefreshAfter;
    }

    public Duration getSessionRefreshGrace() {
        return sessionRefreshGrace;
    }

    public void setSessionRefreshGrace(Duration sessionRefreshGrace) {
        this.sessionRefreshGrace = sessionRefreshGrace == null ? Duration.ofMinutes(2) : sessionRefreshGrace;
    }

    public int getMaxActiveSessions() {
        return maxActiveSessions;
    }

    public void setMaxActiveSessions(int maxActiveSessions) {
        this.maxActiveSessions = maxActiveSessions;
    }
}
