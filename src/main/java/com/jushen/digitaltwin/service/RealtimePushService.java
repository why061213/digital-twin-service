package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.model.City;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RealtimePushService {

    private static final Logger log = LoggerFactory.getLogger(RealtimePushService.class);

    private final RealtimeWebSocketHandler webSocketHandler;
    private final SimulationDataFactory dataFactory;
    private final TaskScheduler taskScheduler;
    private final AtomicReference<RouteState> latestRoute = new AtomicReference<>();

    public RealtimePushService(
            RealtimeWebSocketHandler webSocketHandler,
            SimulationDataFactory dataFactory,
            TaskScheduler taskScheduler
    ) {
        this.webSocketHandler = webSocketHandler;
        this.dataFactory = dataFactory;
        this.taskScheduler = taskScheduler;
    }

    //注释掉标注以暂停发送路线
    //@Scheduled(fixedRate = 3000, initialDelay = 1000)
    public void pushRealtimeSnapshots() {
        webSocketHandler.broadcast(dataFactory.nextKpiMessage());
        webSocketHandler.broadcast(dataFactory.nextInventoryMessage());
        webSocketHandler.broadcast(dataFactory.nextVehicleMessage());
        webSocketHandler.broadcast(dataFactory.nextTrafficEnergyMessage());
    }

    //注释掉标注以暂停发送路线
    //@EventListener(ApplicationReadyEvent.class)
    public void startBusinessRouteLoop() {
        scheduleNextBusinessRoute(2);
    }

    private void scheduleNextBusinessRoute(int delaySeconds) {
        taskScheduler.schedule(this::createBusinessRouteAndContinue, java.time.Instant.now().plusSeconds(delaySeconds));
    }

    private void createBusinessRouteAndContinue() {
        try {
            createBusinessRoute();
        } finally {
            scheduleNextBusinessRoute(dataFactory.randomRouteDelaySeconds());
        }
    }

    private void createBusinessRoute() {
        City from = dataFactory.randomCity();
        City to = dataFactory.randomDifferentCity(from);
        String lineId = UUID.randomUUID().toString();
        int durationSeconds = dataFactory.randomRouteDelaySeconds();
        Instant now = Instant.now();
        RouteState previousLatest = latestRoute.get();
        if (previousLatest != null && !previousLatest.expiresAt().isAfter(now)) {
            webSocketHandler.broadcast(
                    dataFactory.cityFallMessage(previousLatest.lineId(), previousLatest.from(), previousLatest.to())
            );
        }
        latestRoute.set(new RouteState(lineId, from, to, now.plusSeconds(durationSeconds)));

        webSocketHandler.broadcast(dataFactory.cityRaiseMessage(lineId, from, to));
        ScheduledFuture<?> ignored = taskScheduler.schedule(
                () -> pushFallIfRouteIsNotLatest(lineId, from, to),
                now.plusSeconds(durationSeconds)
        );
        log.debug("Scheduled city_fall for route {}, future={}", lineId, ignored);
    }

    private void pushFallIfRouteIsNotLatest(String lineId, City from, City to) {
        RouteState route = latestRoute.get();
        if (route != null && lineId.equals(route.lineId())) {
            log.debug("Keep latest route raised: {}", lineId);
            return;
        }
        webSocketHandler.broadcast(dataFactory.cityFallMessage(lineId, from, to));
    }

    private record RouteState(String lineId, City from, City to, Instant expiresAt) {
    }
}
