package com.jushen.digitaltwin.bootstrap;

import com.jushen.digitaltwin.townroad.TownRoadExternalOrderProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class DashboardBootstrapService {

    private final TownRoadExternalOrderProperties orderProperties;
    private final String instanceId = UUID.randomUUID().toString();
    private final Instant processStartedAt = Instant.now();
    private volatile boolean initialized;
    private volatile boolean synchronizing;
    private volatile String lastError;
    private volatile Instant initializedAt;
    private volatile int rawCount;
    private volatile int routeCount;

    public DashboardBootstrapService(TownRoadExternalOrderProperties orderProperties) {
        this.orderProperties = orderProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!orderProperties.isAutoSyncEnabled()) {
            initialized = true;
            initializedAt = Instant.now();
        }
    }

    public synchronized void markSynchronizationStarted() {
        synchronizing = true;
        lastError = null;
    }

    public synchronized void markSynchronizationSucceeded(Map<String, Object> result) {
        synchronizing = false;
        initialized = true;
        initializedAt = Instant.now();
        lastError = null;
        rawCount = asInt(result.get("rawCount"));
        routeCount = asInt(result.get("roadMapRouteCount")) + asInt(result.get("rm2Vehicles"));
    }

    public synchronized void markSynchronizationFailed(Throwable error) {
        synchronizing = false;
        lastError = error == null ? "订单初始化失败" : error.getMessage();
    }

    public Snapshot snapshot() {
        String phase = initialized ? "ready" : synchronizing ? "synchronizing" : lastError == null ? "starting" : "retrying";
        String message = initialized
                ? "后端数据初始化完成"
                : synchronizing
                    ? "正在同步订单与路线快照"
                    : lastError == null ? "等待后端初始化" : "订单初始化失败，等待自动重试";
        return new Snapshot(instanceId, processStartedAt, initialized, phase, message, lastError,
                rawCount, routeCount, initializedAt);
    }

    private int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public record Snapshot(
            String instanceId,
            Instant processStartedAt,
            boolean initialized,
            String phase,
            String message,
            String lastError,
            int rawCount,
            int routeCount,
            Instant initializedAt
    ) {
    }
}
