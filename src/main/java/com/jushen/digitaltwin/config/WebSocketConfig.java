package com.jushen.digitaltwin.config;

import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import com.jushen.digitaltwin.websocket.TokenHandshakeInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(DashboardWebSocketProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler realtimeWebSocketHandler;
    private final TokenHandshakeInterceptor tokenHandshakeInterceptor;
    private final DashboardWebSocketProperties properties;

    public WebSocketConfig(
            RealtimeWebSocketHandler realtimeWebSocketHandler,
            TokenHandshakeInterceptor tokenHandshakeInterceptor,
            DashboardWebSocketProperties properties
    ) {
        this.realtimeWebSocketHandler = realtimeWebSocketHandler;
        this.tokenHandshakeInterceptor = tokenHandshakeInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeWebSocketHandler, "/ws/realtime")
                .addInterceptors(tokenHandshakeInterceptor)
                .setAllowedOriginPatterns(properties.getAllowedOriginPatterns().toArray(String[]::new));
    }
}
