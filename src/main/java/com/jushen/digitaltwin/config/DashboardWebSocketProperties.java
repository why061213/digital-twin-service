package com.jushen.digitaltwin.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dashboard.websocket")
public class DashboardWebSocketProperties {

    private String token = "";
    private boolean legacyTokenEnabled = false;
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("*"));

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean isLegacyTokenEnabled() {
        return legacyTokenEnabled;
    }

    public void setLegacyTokenEnabled(boolean legacyTokenEnabled) {
        this.legacyTokenEnabled = legacyTokenEnabled;
    }

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }
}
