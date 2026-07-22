package com.jushen.digitaltwin.baidu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
                        20_500, 1_800,
                        List.of(new double[]{113.25, 23.12}, new double[]{113.13, 23.03}),
                        List.of()));
        RoutePlanningService service = new RoutePlanningService(baidu, amap, true, 60_000, 0);

        RoutePlanningService.PlannedRoute first = service.plan(origin, destination);
        RoutePlanningService.PlannedRoute second = service.plan(origin, destination);

        assertThat(first.success()).isTrue();
        assertThat(first.provider()).isEqualTo("baidu");
        assertThat(first.distanceKm()).isEqualTo(20.5);
        assertThat(first.durationMs()).isEqualTo(1_800_000);
        assertThat(first.coordinates().get(0)).containsExactly(origin);
        assertThat(first.coordinates().get(first.coordinates().size() - 1)).containsExactly(destination);
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
        RoutePlanningService service = new RoutePlanningService(baidu, amap, true, 60_000, 0);

        RoutePlanningService.PlannedRoute route = service.plan(origin, destination);

        assertThat(route.success()).isTrue();
        assertThat(route.provider()).isEqualTo("amap");
        assertThat(route.distanceKm()).isEqualTo(21.0);
    }

    @Test
    void keepsBaselineAndWaypointPlansInSeparateCacheEntries() {
        BaiduRoutePlanService baidu = mock(BaiduRoutePlanService.class);
        AmapRoutePlanService amap = mock(AmapRoutePlanService.class);
        List<double[]> waypoints = List.of(new double[]{113.20, 23.08});
        when(baidu.planRoute(origin[1], origin[0], destination[1], destination[0]))
                .thenReturn(BaiduRoutePlanService.RoutePlanResult.success(
                        20_500, 1_800, List.of(origin, destination), List.of()));
        when(baidu.planRoute(eq(origin[1]), eq(origin[0]), eq(destination[1]), eq(destination[0]), anyList()))
                .thenReturn(BaiduRoutePlanService.RoutePlanResult.success(
                        22_000, 2_000, List.of(origin, waypoints.get(0), destination), List.of()));
        RoutePlanningService service = new RoutePlanningService(baidu, amap, true, 60_000, 0);

        RoutePlanningService.PlannedRoute baseline = service.plan(origin, destination);
        RoutePlanningService.PlannedRoute initialization = service.plan(origin, destination, waypoints);
        RoutePlanningService.PlannedRoute cachedInitialization = service.plan(origin, destination, waypoints);

        assertThat(baseline.distanceKm()).isEqualTo(20.5);
        assertThat(initialization.distanceKm()).isEqualTo(22.0);
        assertThat(cachedInitialization).isEqualTo(initialization);
        verify(baidu, times(1)).planRoute(origin[1], origin[0], destination[1], destination[0]);
        verify(baidu, times(1)).planRoute(eq(origin[1]), eq(origin[0]), eq(destination[1]), eq(destination[0]), anyList());
    }

    @Test
    void preservesWaypointsWhenFallingBackToAmap() {
        BaiduRoutePlanService baidu = mock(BaiduRoutePlanService.class);
        AmapRoutePlanService amap = mock(AmapRoutePlanService.class);
        List<double[]> waypoints = List.of(new double[]{113.20, 23.08});
        when(baidu.planRoute(eq(origin[1]), eq(origin[0]), eq(destination[1]), eq(destination[0]), anyList()))
                .thenReturn(BaiduRoutePlanService.RoutePlanResult.fail("quota"));
        when(amap.planRoute(eq(origin[1]), eq(origin[0]), eq(destination[1]), eq(destination[0]), anyList()))
                .thenReturn(AmapRoutePlanService.RoutePlanResult.success(
                        22_500, 2_100, 0, List.of(origin, waypoints.get(0), destination), List.of()));
        RoutePlanningService service = new RoutePlanningService(baidu, amap, true, 60_000, 0);

        RoutePlanningService.PlannedRoute route = service.plan(origin, destination, waypoints);

        assertThat(route.success()).isTrue();
        assertThat(route.provider()).isEqualTo("amap");
        assertThat(route.coordinates()).anyMatch(point -> point[0] == 113.20 && point[1] == 23.08);
        verify(amap, times(1)).planRoute(eq(origin[1]), eq(origin[0]), eq(destination[1]), eq(destination[0]), anyList());
    }

    @Test
    void keepsFullMatchingPathAndOnlyLimitsRenderingPath() {
        BaiduRoutePlanService baidu = mock(BaiduRoutePlanService.class);
        AmapRoutePlanService amap = mock(AmapRoutePlanService.class);
        List<double[]> detailed = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            double ratio = i / 599.0;
            detailed.add(new double[]{
                    origin[0] + (destination[0] - origin[0]) * ratio,
                    origin[1] + (destination[1] - origin[1]) * ratio
                            + Math.sin(ratio * Math.PI * 16) * 0.01
            });
        }
        when(baidu.planRoute(origin[1], origin[0], destination[1], destination[0]))
                .thenReturn(BaiduRoutePlanService.RoutePlanResult.success(
                        30_000, 2_400, detailed, List.of()));
        RoutePlanningService service = new RoutePlanningService(baidu, amap, true, 60_000, 0);

        RoutePlanningService.PlannedRoute route = service.plan(origin, destination);

        assertThat(route.matchingCoordinates()).hasSize(602);
        assertThat(route.coordinates()).hasSizeLessThanOrEqualTo(240);
        assertThat(route.coordinates().get(0)).containsExactly(origin);
        assertThat(route.coordinates().get(route.coordinates().size() - 1)).containsExactly(destination);
    }
}
