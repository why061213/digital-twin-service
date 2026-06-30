package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final RealtimeWebSocketHandler webSocketHandler;

    public HealthController(RealtimeWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "onlineWebSocketClients", webSocketHandler.onlineCount(),
                "timestamp", Instant.now()
        );
    }
}
