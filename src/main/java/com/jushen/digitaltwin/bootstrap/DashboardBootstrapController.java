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
    private final DashboardAccessTokenService accessTokenService;

    public DashboardBootstrapController(
            DashboardBootstrapService bootstrapService,
            DashboardAccessService accessService,
            DashboardAccessTokenService accessTokenService
    ) {
        this.bootstrapService = bootstrapService;
        this.accessService = accessService;
        this.accessTokenService = accessTokenService;
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            HttpServletRequest request,
            @RequestHeader(value = "X-Dashboard-Device-Token", required = false) String deviceToken
    ) {
        DashboardBootstrapService.Snapshot bootstrap = bootstrapService.snapshot();
        DashboardAccessService.Verification verification = accessService.verify(request, deviceToken);
        DashboardAccessTokenService.Validation tokenValidation = accessTokenService.validate(
                DashboardTokenSupport.extract(request)
        );
        boolean authorized = verification.authorized() || tokenValidation.valid();
        String verificationMethod = tokenValidation.valid() ? "access-key" : verification.method();
        String deviceIdentity = tokenValidation.valid() ? tokenValidation.deviceIdentity() : verification.deviceIdentity();
        String verificationMessage = tokenValidation.valid() ? "访问密钥验证通过" : verification.message();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ready", bootstrap.initialized() && authorized);
        response.put("backendReady", true);
        response.put("dataInitialized", bootstrap.initialized());
        response.put("authorized", authorized);
        response.put("phase", authorized ? bootstrap.phase() : "unauthorized");
        response.put("message", authorized ? bootstrap.message() : verificationMessage);
        response.put("lastError", bootstrap.lastError());
        response.put("rawCount", bootstrap.rawCount());
        response.put("routeCount", bootstrap.routeCount());
        response.put("verificationMethod", verificationMethod);
        response.put("deviceIdentity", deviceIdentity);
        response.put("retryAfterMs", 1500);
        response.put("serverTime", Instant.now().toString());
        return response;
    }
}
