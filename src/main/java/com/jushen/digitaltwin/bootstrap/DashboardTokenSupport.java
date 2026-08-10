package com.jushen.digitaltwin.bootstrap;

import jakarta.servlet.http.HttpServletRequest;

public final class DashboardTokenSupport {

    private static final String BEARER_PREFIX = "Bearer ";

    private DashboardTokenSupport() {
    }

    public static String extract(HttpServletRequest request) {
        String bearer = extractBearer(request.getHeader("Authorization"));
        if (bearer != null) return bearer;
        String accessKey = request.getHeader("X-Dashboard-Access-Key");
        return accessKey == null || accessKey.isBlank() ? null : accessKey.trim();
    }

    public static String extractBearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
