package com.jushen.digitaltwin.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper;

    public RealtimeWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("Dashboard websocket connected: {}, online={}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info(
                "Dashboard websocket disconnected: {}, status={} {}, online={}",
                session.getId(),
                status.getCode(),
                status.getReason(),
                sessions.size()
        );
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        if (isConnectionReset(exception)) {
            log.info("Dashboard websocket transport closed by client: {}", session.getId());
            return;
        }
        log.warn("Dashboard websocket transport error: {}", session.getId(), exception);
        closeQuietly(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 前端会定时发送应用层心跳。服务端立即回 pong，避免代理/浏览器把长连接误判为空闲连接。
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if ("ping".equals(node.path("type").asText())) {
                send(session, objectMapper.writeValueAsString(Map.of(
                        "type", "pong",
                        "serverTime", System.currentTimeMillis()
                )));
            }
        } catch (Exception e) {
            log.debug("Ignored non-dashboard websocket client message: {}", message.getPayload(), e);
        }
    }

    public void broadcast(Object message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize websocket message", e);
            return;
        }

        for (WebSocketSession session : sessions) {
            send(session, payload);
        }
    }

    public int onlineCount() {
        return sessions.size();
    }

    private void send(WebSocketSession session, String payload) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException e) {
            sessions.remove(session);
            if (isConnectionReset(e)) {
                log.debug("Skipped websocket send because client already closed: {}", session.getId());
                return;
            }
            log.warn("Failed to send websocket message: {}", session.getId(), e);
            closeQuietly(session);
        }
    }

    private boolean isConnectionReset(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("connection reset")
                        || normalized.contains("broken pipe")
                        || normalized.contains("established connection was aborted")
                        || message.contains("你的主机中的软件中止了一个已建立的连接")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void closeQuietly(WebSocketSession session) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // 连接已经不可用时无需再次抛出，下一轮广播会清理 session 集合。
        }
    }
}
