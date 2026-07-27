package com.jushen.digitaltwin.routeanalysis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteAnalysisServiceTest {
    private final RouteAnalysisService service = new RouteAnalysisService(100, 120, 800, 0.97, 300, 150, 300, 500);

    @Test
    void marksLongParallelCorridorAsSharedUsingExactLineIdentity() {
        Map<String, RouteAnalysisDTO> result = service.analyze(List.of(
                route("green", 113.0, 23.0, 113.02, 23.0),
                route("pink", 113.0, 23.0005, 113.02, 23.0005)
        ));

        assertTrue(result.get("green").parts().stream().anyMatch(part ->
                part.sharedWith().stream().anyMatch(route -> route.lineId().equals("pink"))));
        assertTrue(result.get("pink").parts().stream().anyMatch(part ->
                part.sharedWith().stream().anyMatch(route -> route.lineId().equals("green"))));
    }

    @Test
    void usesOneProjectionReferenceForTheWholeGroup() {
        Map<String, RouteAnalysisDTO> result = service.analyze(List.of(
                route("low", 120.0, 20.0, 120.02, 20.0),
                route("high", 120.0, 20.0005, 120.02, 20.0005)
        ));

        assertTrue(result.get("low").parts().stream().anyMatch(part -> !part.sharedWith().isEmpty()));
    }

    @Test
    void doesNotTreatShortCrossingAsShared() {
        Map<String, RouteAnalysisDTO> result = service.analyze(List.of(
                route("east", 113.0, 23.0, 113.02, 23.0),
                route("north", 113.01, 22.99, 113.01, 23.01)
        ));

        assertTrue(result.values().stream().flatMap(analysis -> analysis.parts().stream())
                .allMatch(part -> part.sharedWith().isEmpty()));
    }

    @Test
    void returnsToNormalBetweenTwoDeviationRuns() {
        Map<String, Object> route = route("vehicle", 113.0, 23.0, 113.04, 23.0);
        route.put("baselineCoordinates", List.of(
                point(113.0, 23.0), point(113.04, 23.0)
        ));
        route.put("coordinates", List.of(
                point(113.0, 23.0), point(113.008, 23.006), point(113.016, 23.0),
                point(113.024, 23.006), point(113.032, 23.0), point(113.04, 23.0)
        ));

        List<String> roles = service.analyze(List.of(route)).get("vehicle").parts().stream()
                .map(RouteAnalysisDTO.RoutePartDTO::routeRole).toList();
        assertFalse(roles.isEmpty());
        assertEquals("NORMAL", roles.get(0));
        assertTrue(roles.stream().filter("DEVIATION"::equals).count() >= 2);
    }

    private static Map<String, Object> route(String lineId, double fromLng, double fromLat, double toLng, double toLat) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("lineId", lineId);
        route.put("orderId", "order-" + lineId);
        route.put("plate", "plate-" + lineId);
        route.put("coordinates", List.of(point(fromLng, fromLat), point(toLng, toLat)));
        route.put("baselineCoordinates", route.get("coordinates"));
        route.put("meta", Map.of("visualKey", "trip-" + lineId));
        return route;
    }

    private static double[] point(double lng, double lat) {
        return new double[]{lng, lat};
    }
}
