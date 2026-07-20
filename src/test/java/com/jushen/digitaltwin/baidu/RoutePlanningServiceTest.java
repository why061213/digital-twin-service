package com.jushen.digitaltwin.baidu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutePlanningServiceTest {

    private final double[] origin = {113.2644, 23.1291};
    private final double[] destination = {113.1214, 23.0215};

    @Test
    void prefersBaiduAndCachesSuccessfulRoute() {
        BaiduRoutePlanService baidu = mock(BaiduRoutePlanService.class);
        AmapRoutePlanService amap = mock(AmapRoutePlanService.class);
        when(baidu.planRoute(origin[1], origin[0], destination[1], destination[0]))
                .thenReturn(BaiduRoutePlanService.RoutePlanResult.success(
                        20_500, 1_800, List.of(origin, destination), List.of()));
        RoutePlanningService service = new RoutePlanningService(baidu, amap, true, 60_000);

        RoutePlanningService.PlannedRoute first = service.plan(origin, destination);
        RoutePlanningService.PlannedRoute second = service.plan(origin, destination);

        assertThat(first.success()).isTrue();
        assertThat(first.provider()).isEqualTo("baidu");
        assertThat(first.distanceKm()).isEqualTo(20.5);
        assertThat(first.durationMs()).isEqualTo(1_800_000);
        assertThat(second).isEqualTo(first);
        verify(baidu, times(1)).planRoute(origin[1], origin[0], destination[1], destination[0]);
        verify(amap, never()).planRoute(origin[1], origin[0], destination[1], destination[0]);
    }

    @Test
    void fallsBackToAmapWhenBaiduFails() {
        BaiduRoutePlanService baidu = mock(BaiduRoutePlanService.class);
        AmapRoutePlanService amap = mock(AmapRoutePlanService.class);
        when(baidu.planRoute(origin[1], origin[0], destination[1], destination[0]))
                .thenReturn(BaiduRoutePlanService.RoutePlanResult.fail("quota"));
        when(amap.planRoute(origin[1], origin[0], destination[1], destination[0]))
                .thenReturn(AmapRoutePlanService.RoutePlanResult.success(
                        21_000, 1_900, 0, List.of(origin, destination), List.of()));
        RoutePlanningService service = new RoutePlanningService(baidu, amap, true, 60_000);

        RoutePlanningService.PlannedRoute route = service.plan(origin, destination);

        assertThat(route.success()).isTrue();
        assertThat(route.provider()).isEqualTo("amap");
        assertThat(route.distanceKm()).isEqualTo(21.0);
    }
}
