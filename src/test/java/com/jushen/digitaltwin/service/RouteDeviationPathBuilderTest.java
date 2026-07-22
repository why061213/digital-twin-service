package com.jushen.digitaltwin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteDeviationPathBuilderTest {
    @Test
    void extractsOnlyTheDifferentMiddleSection() {
        List<double[]> baseline = List.of(
                point(113.00, 23.00), point(113.01, 23.00), point(113.02, 23.00),
                point(113.03, 23.00), point(113.04, 23.00));
        List<double[]> vehicle = List.of(
                point(113.00, 23.00), point(113.01, 23.00), point(113.02, 23.01),
                point(113.03, 23.00), point(113.04, 23.00));

        List<double[]> branch = RouteDeviationPathBuilder.extract(baseline, vehicle);

        assertEquals(3, branch.size());
        assertArrayEquals(vehicle.get(1), branch.get(0));
        assertArrayEquals(vehicle.get(3), branch.get(2));
    }

    @Test
    void returnsNoBranchForMinorGpsDrift() {
        List<double[]> baseline = List.of(point(113.00, 23.00), point(113.04, 23.00));
        List<double[]> vehicle = List.of(
                point(113.00, 23.00), point(113.02, 23.001), point(113.04, 23.00));

        assertTrue(RouteDeviationPathBuilder.extract(baseline, vehicle).isEmpty());
    }

    private static double[] point(double lng, double lat) {
        return new double[]{lng, lat};
    }
}
