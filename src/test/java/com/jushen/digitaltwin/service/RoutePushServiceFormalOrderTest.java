package com.jushen.digitaltwin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.baidu.RoutePlanningService;
import com.jushen.digitaltwin.grouping.RouteGroupingEngine;
import com.jushen.digitaltwin.websocket.RealtimeWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoutePushServiceFormalOrderTest {
    private final RoutePushService service = new RoutePushService(
            mock(RealtimeWebSocketHandler.class), mock(SimulationDataFactory.class), new ObjectMapper(),
            mock(RouteGroupingEngine.class), mock(VehiclePositionCacheService.class),
            mock(RoutePlanningService.class), false, "real", "", 120, 80, 12,
            "province-path", "", 50, 0, "", "", false,
            false, "", "", "", false, "target/local-cache/test-token.json");

    @AfterEach
    void shutdown() {
        service.shutdownBulkDispatchExecutor();
    }

    @Test
    void formalOrderWithoutPositionStartsAtZeroInsteadOfRandomProgress() {
        Double progress = ReflectionTestUtils.invokeMethod(
                service, "initialProgressForExternalOrder",
                List.of(new double[]{113, 23}, new double[]{114, 24}),
                null, 100.0, 80.0, null, "运输中");

        assertThat(progress).isZero();
    }

    @Test
    void firstRealCalibrationForcesFullRouteProjection() {
        assertThat(RoutePushService.calibrationHintProgress(null, 0.96)).isEqualTo(-1);
        assertThat(RoutePushService.calibrationHintProgress("waiting-position", 0.90)).isEqualTo(-1);
        assertThat(RoutePushService.calibrationHintProgress("real-provider", 0.42)).isEqualTo(0.42);
    }

    @Test
    void plateComparisonIgnoresCommonDisplaySeparators() {
        String plain = ReflectionTestUtils.invokeMethod(service, "normalizePlateKey", "桂L63635");
        String decorated = ReflectionTestUtils.invokeMethod(service, "normalizePlateKey", "桂 L·636-35");

        assertThat(decorated).isEqualTo(plain);
    }
}
