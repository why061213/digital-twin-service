package com.jushen.digitaltwin.bootstrap;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class DashboardAuthController {

    private final DashboardAccessService accessService;
    private final DashboardAccessTokenService accessTokenService;

    public DashboardAuthController(
            DashboardAccessService accessService,
            DashboardAccessTokenService accessTokenService
    ) {
        this.accessService = accessService;
        this.accessTokenService = accessTokenService;
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> issue(
            HttpServletRequest request,
            @RequestHeader(value = "X-Dashboard-Device-Token", required = false) String deviceToken
    ) {
        DashboardAccessService.Verification verification = accessService.verify(request, deviceToken);
        if (!verification.authorized()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(
                    "device_not_authorized",
                    verification.message()
            ));
        }
        return ResponseEntity.ok(session(accessTokenService.issue(verification)));
    }

    @PostMapping("/session/refresh")
    public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest request) {
        DashboardAccessTokenService.IssuedSession issued = accessTokenService.refresh(
                DashboardTokenSupport.extract(request)
        );
        if (issued == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(
                    "access_key_expired",
                    "访问密钥无效或已过期"
            ));
        }
        return ResponseEntity.ok(session(issued));
    }

    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> validate(HttpServletRequest request) {
        DashboardAccessTokenService.Validation validation = accessTokenService.validate(
                DashboardTokenSupport.extract(request)
        );
        if (!validation.valid()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(
                    "access_key_invalid",
                    "访问密钥无效或已过期"
            ));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", true);
        response.put("tokenType", "Bearer");
        response.put("issuedAt", validation.issuedAt());
        response.put("refreshAfter", validation.refreshAfter());
        response.put("expiresAt", validation.expiresAt());
        response.put("verificationMethod", validation.verificationMethod());
        response.put("deviceIdentity", validation.deviceIdentity());
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> session(DashboardAccessTokenService.IssuedSession issued) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", issued.accessToken());
        response.put("tokenType", issued.tokenType());
        response.put("issuedAt", issued.issuedAt());
        response.put("refreshAfter", issued.refreshAfter());
        response.put("expiresAt", issued.expiresAt());
        response.put("verificationMethod", issued.verificationMethod());
        response.put("deviceIdentity", issued.deviceIdentity());
        return response;
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("code", code);
        response.put("message", message);
        return response;
    }
}
