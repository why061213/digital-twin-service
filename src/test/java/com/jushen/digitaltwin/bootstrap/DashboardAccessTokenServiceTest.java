package com.jushen.digitaltwin.bootstrap;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardAccessTokenServiceTest {

    @Test
    void issuesAndRefreshesOpaqueSessionKeys() {
        DashboardAccessProperties properties = new DashboardAccessProperties();
        properties.setSessionTtl(Duration.ofHours(8));
        properties.setSessionRefreshAfter(Duration.ofHours(4));
        properties.setSessionRefreshGrace(Duration.ofMinutes(2));
        DashboardAccessTokenService service = new DashboardAccessTokenService(properties);
        DashboardAccessService.Verification verification = new DashboardAccessService.Verification(
                true,
                "mac-whitelist",
                "mac_whitelist_verified",
                "192.168.1.20",
                "AA:BB:CC:DD:EE:FF",
                "设备验证通过"
        );

        DashboardAccessTokenService.IssuedSession first = service.issue(verification);
        assertNotNull(first);
        assertTrue(first.accessToken().startsWith("jdt_"));
        assertTrue(service.validate(first.accessToken()).valid());

        DashboardAccessTokenService.IssuedSession refreshed = service.refresh(first.accessToken());
        assertNotNull(refreshed);
        assertNotEquals(first.accessToken(), refreshed.accessToken());
        assertTrue(service.validate(refreshed.accessToken()).valid());
        assertTrue(service.validate(first.accessToken()).valid(), "old key remains valid during refresh grace");
    }
}
