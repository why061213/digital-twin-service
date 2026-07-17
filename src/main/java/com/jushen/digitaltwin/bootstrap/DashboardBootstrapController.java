package com.jushen.digitaltwin.bootstrap;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bootstrap")
public class DashboardBootstrapController {

    private final DashboardBootstrapService bootstrapService;
    private final DashboardAccessService accessService;

    public DashboardBootstrapController(
            DashboardBootstrapService bootstrapService,
            DashboardAccessService accessService
    ) {
        this.bootstrapService = bootstrapService;
        this.accessService = accessService;
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            HttpServletRequest request,
            @RequestHeader(value = "X-Dashboard-Device-Token", required = false) String deviceToken
    ) {
        DashboardBootstrapService.Snapshot bootstrap = bootstrapService.snapshot();
        DashboardAccessService.Verification verification = accessService.verify(request, deviceToken);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ready", bootstrap.initialized() && verification.authorized());
        response.put("backendReady", true);
        response.put("dataInitialized", bootstrap.initialized());
        response.put("authorized", verification.authorized());
        response.put("phase", verification.authorized() ? bootstrap.phase() : "unauthorized");
        response.put("message", verification.authorized() ? bootstrap.message() : verification.message());
        response.put("lastError", bootstrap.lastError());
        response.put("rawCount", bootstrap.rawCount());
        response.put("routeCount", bootstrap.routeCount());
        response.put("verificationMethod", verification.method());
        response.put("deviceIdentity", verification.deviceIdentity());
        response.put("retryAfterMs", 1500);
        response.put("serverTime", Instant.now().toString());
        return response;
    }
}
