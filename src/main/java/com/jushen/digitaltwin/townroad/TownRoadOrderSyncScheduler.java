package com.jushen.digitaltwin.townroad;

import com.jushen.digitaltwin.bootstrap.DashboardBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RM2 的唯一自动订单入口。
 *
 * 复用 TownRoadRenderService 的正式流水线，避免旧 ExternalOrderSyncService
 * 把未分流订单广播为 road_path。
 */
@Service
public class TownRoadOrderSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(TownRoadOrderSyncScheduler.class);

    private final TownRoadRenderService renderService;
    private final TownRoadExternalOrderProperties properties;
    private final DashboardBootstrapService bootstrapService;
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public TownRoadOrderSyncScheduler(
            TownRoadRenderService renderService,
            TownRoadExternalOrderProperties properties,
            DashboardBootstrapService bootstrapService
    ) {
        this.renderService = renderService;
        this.properties = properties;
        this.bootstrapService = bootstrapService;
    }

    @Scheduled(
            initialDelayString = "${dashboard.websocket.external-order.auto-sync-initial-delay-ms:5000}",
            fixedDelayString = "${dashboard.websocket.external-order.auto-sync-fixed-delay-ms:900000}"
    )
    public void syncSnapshot() {
        if (!properties.isAutoSyncEnabled()) {
            return;
        }
        if (!syncing.compareAndSet(false, true)) {
            log.warn("[TownRoadSync] skipped overlapping external order sync");
            return;
        }

        long startedAt = System.currentTimeMillis();
        bootstrapService.markSynchronizationStarted();
        try {
            Map<String, Object> result = renderService.fetchProcessAndBroadcast();
            bootstrapService.markSynchronizationSucceeded(result);
            log.info("[TownRoadSync] snapshot synchronized: rawCount={}, shortHaulCount={}, elapsedMs={}",
                    result.getOrDefault("rawCount", 0),
                    result.getOrDefault("shortHaulCount", 0),
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            bootstrapService.markSynchronizationFailed(e);
            log.warn("[TownRoadSync] external order sync failed: {}", e.getMessage());
        } finally {
            syncing.set(false);
        }
    }
}
