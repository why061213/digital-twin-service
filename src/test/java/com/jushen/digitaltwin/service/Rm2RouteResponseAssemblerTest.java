package com.jushen.digitaltwin.service;

import com.jushen.digitaltwin.dto.RenderRouteDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class Rm2RouteResponseAssemblerTest {
    @Test
    void usesVehicleRouteAsEffectiveCoordinatesAndKeepsBaselineSeparately() {
        List<double[]> baselineCoordinates = List.of(point(113, 23), point(114, 24));
        List<double[]> vehicleCoordinates = List.of(point(113, 23), point(113.5, 24.2), point(114, 24));
        RenderRouteDTO baseline = route(baselineCoordinates);
        Map<String, Object> position = Map.of(
                "routeCoordinates", vehicleCoordinates,
                "routeRevision", 42L,
                "pathKey", "vehicle-route::line-1",
                "routeLengthKm", 18.5,
                "isRouteBranch", true,
                "deviationCoordinates", vehicleCoordinates,
                "traversedCoordinates", vehicleCoordinates.subList(0, 2),
                "remainingCoordinates", vehicleCoordinates.subList(1, 3),
                "routeReplanAnchors", List.of(vehicleCoordinates.get(1))
        );

        Map<String, Object> result = Rm2RouteResponseAssembler.effectiveRoute(baseline, position);

        assertSame(vehicleCoordinates, result.get("coordinates"));
        assertSame(baselineCoordinates, result.get("baselineCoordinates"));
        assertEquals("vehicle-route::line-1", result.get("pathKey"));
        assertEquals("baseline-path", result.get("baselinePathKey"));
        assertEquals(42L, result.get("routeRevision"));
        assertEquals(true, result.get("isRouteBranch"));
        assertEquals(vehicleCoordinates.subList(0, 2), result.get("traversedCoordinates"));
        assertEquals(vehicleCoordinates.subList(1, 3), result.get("remainingCoordinates"));
    }

    @Test
    void keepsBaselineAsEffectiveRouteWhenNoCorrectionExists() {
        List<double[]> baselineCoordinates = List.of(point(113, 23), point(114, 24));
        RenderRouteDTO baseline = route(baselineCoordinates);

        Map<String, Object> result = Rm2RouteResponseAssembler.effectiveRoute(baseline, Map.of());

        assertSame(baselineCoordinates, result.get("coordinates"));
        assertSame(baselineCoordinates, result.get("baselineCoordinates"));
        assertEquals("baseline-path", result.get("pathKey"));
        assertEquals(false, result.containsKey("routeRevision"));
        assertEquals(false, result.containsKey("isRouteBranch"));
    }

    @Test
    void overlaysDynamicTripMilestonesFromPositionFrame() {
        List<double[]> coordinates = List.of(point(113, 23), point(114, 24));
        RenderRouteDTO baseline = route(coordinates, Map.of(
                "tripId", "trip-1",
                "targetStopId", "stop-2",
                "tripStatusText", "正在前往第二节点"
        ));
        Map<String, Object> position = Map.of(
                "tripId", "trip-1",
                "targetStopId", "stop-3",
                "tripStatusText", "正在前往第三节点"
        );

        Map<String, Object> result = Rm2RouteResponseAssembler.effectiveRoute(baseline, position);
        Map<?, ?> meta = (Map<?, ?>) result.get("meta");

        assertEquals("stop-3", meta.get("targetStopId"));
        assertEquals("正在前往第三节点", meta.get("tripStatusText"));
    }

    private static RenderRouteDTO route(List<double[]> coordinates) {
        return route(coordinates, Map.of());
    }

    private static RenderRouteDTO route(List<double[]> coordinates, Map<String, Object> meta) {
        return new RenderRouteDTO(
                "line-1", "order-1", "business-1", "粤E00001", "vehicle-1",
                "A", "B", coordinates.get(0), coordinates.get(coordinates.size() - 1), coordinates,
                12.0, 40.0, "运输中", "铝材", 10.0, "吨", 1_000L,
                "baseline-path", "rm2", "group-1", "primary", "GCJ02", "now",
                "signature", meta
        );
    }

    private static double[] point(double lng, double lat) {
        return new double[]{lng, lat};
    }
}
