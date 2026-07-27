package com.jushen.digitaltwin.routeanalysis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteAnalysisServiceTest {
    private final RouteAnalysisService service = new RouteAnalysisService(100, 120, 800, 0.97, 300, 150, 300, 500);

    @Test
    void givesSameOrderVehiclesOnTheSameDeviationOneBranchIdentity() {
        Map<String, Object> green = route("green", 113.0, 23.0, 113.02, 23.0);
        Map<String, Object> pink = route("pink", 113.0, 23.0005, 113.02, 23.0005);
        green.put("businessLineId", "business-1");
        pink.put("businessLineId", "business-1");
        green.put("baselineCoordinates", List.of(point(113.0, 23.02), point(113.02, 23.02)));
        pink.put("baselineCoordinates", List.of(point(113.0, 23.02), point(113.02, 23.02)));
        String originalBranch = service.analyze(List.of(green)).get("green").parts().stream()
                .map(RouteAnalysisDTO.RoutePartDTO::branchGroupId).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        Map<String, RouteAnalysisDTO> result = service.analyze(List.of(green, pink));

        String greenBranch = result.get("green").parts().stream()
                .map(RouteAnalysisDTO.RoutePartDTO::branchGroupId).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        String pinkBranch = result.get("pink").parts().stream()
                .map(RouteAnalysisDTO.RoutePartDTO::branchGroupId).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        assertEquals(originalBranch, greenBranch);
        assertEquals(greenBranch, pinkBranch);
    }

    @Test
    void doesNotAssignOneBranchIdentityAcrossDifferentOrders() {
        Map<String, Object> first = route("first", 120.0, 20.0, 120.02, 20.0);
        Map<String, Object> second = route("second", 120.0, 20.0005, 120.02, 20.0005);
        first.put("businessLineId", "business-1");
        second.put("businessLineId", "business-2");
        first.put("baselineCoordinates", List.of(point(120.0, 20.02), point(120.02, 20.02)));
        second.put("baselineCoordinates", List.of(point(120.0, 20.02), point(120.02, 20.02)));
        Map<String, RouteAnalysisDTO> result = service.analyze(List.of(
                first, second
        ));

        String firstBranch = result.get("first").parts().stream()
                .map(RouteAnalysisDTO.RoutePartDTO::branchGroupId).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        String secondBranch = result.get("second").parts().stream()
                .map(RouteAnalysisDTO.RoutePartDTO::branchGroupId).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        assertNotEquals(firstBranch, secondBranch);
        assertTrue(result.values().stream().flatMap(analysis -> analysis.parts().stream())
                .allMatch(part -> part.sharedWith().isEmpty()));
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
