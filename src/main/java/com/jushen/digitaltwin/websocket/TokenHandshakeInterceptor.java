package com.jushen.digitaltwin.websocket;

import com.jushen.digitaltwin.bootstrap.DashboardAccessTokenService;
import com.jushen.digitaltwin.config.DashboardWebSocketProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private final DashboardWebSocketProperties properties;
    private final DashboardAccessTokenService accessTokenService;

    public TokenHandshakeInterceptor(
            DashboardWebSocketProperties properties,
            DashboardAccessTokenService accessTokenService
    ) {
        this.properties = properties;
        this.accessTokenService = accessTokenService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = extractToken(request.getURI());
        if (accessTokenService.validate(token).valid() || matchesLegacyToken(token)) {
            return true;
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // No-op.
    }

    private String extractToken(URI uri) {
        return UriComponentsBuilder.fromUri(uri)
                .build()
                .getQueryParams()
                .getFirst("token");
    }

    private boolean matchesLegacyToken(String candidate) {
        String configured = properties.getToken();
        return properties.isLegacyTokenEnabled()
                && configured != null
                && !configured.isBlank()
                && candidate != null
                && MessageDigest.isEqual(
                        configured.getBytes(StandardCharsets.UTF_8),
                        candidate.getBytes(StandardCharsets.UTF_8)
                );
    }
}
