package com.jushen.digitaltwin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TripRouteAnchorResolverTest {
    @Test
    void separatesVisitedPrefixAndPendingRemainingStopsForReplan() {
        List<double[]> route = List.of(
                point(112.8, 23.0), point(112.9, 23.1),
                point(113.0, 23.2), point(113.1, 23.3)
        );
        Map<String, Object> metadata = Map.of("tripStops", List.of(
                stop(route.get(0), "VISITED"),
                stop(route.get(1), "PENDING"),
                stop(route.get(2), "VISITED"),
                stop(route.get(3), "PENDING")
        ));

        TripRouteAnchorResolver.Anchors anchors = TripRouteAnchorResolver.resolve(
                metadata, route, 0.55, new ObjectMapper());

        assertEquals(1, anchors.completedWaypoints().size());
        assertArrayEquals(route.get(1), anchors.completedWaypoints().get(0));
        assertEquals(1, anchors.remainingWaypoints().size());
        assertArrayEquals(route.get(2), anchors.remainingWaypoints().get(0));
    }

    private static Map<String, Object> stop(double[] coordinates, String state) {
        return Map.of("coordinates", coordinates, "visitState", state);
    }

    private static double[] point(double lng, double lat) {
        return new double[]{lng, lat};
    }
}
