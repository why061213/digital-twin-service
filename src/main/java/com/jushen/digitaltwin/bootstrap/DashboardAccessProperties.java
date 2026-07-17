package com.jushen.digitaltwin.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "dashboard.access")
public class DashboardAccessProperties {

    private boolean enabled = true;
    private boolean trustLoopback = true;
    private List<String> allowedMacAddresses = new ArrayList<>();
    private String deviceToken = "";

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
}
