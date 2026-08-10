package com.jushen.digitaltwin.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteCorrectionPathBuilderTest {

    @Test
    void keepsOrderEndpointsAndSplicesConfirmedVehiclePosition() {
        List<double[]> original = List.of(
                new double[]{113.0, 23.0},
                new double[]{113.2, 23.1},
                new double[]{113.4, 23.2}
        );
        double[] confirmed = new double[]{113.18, 23.16};
        List<double[]> remaining = List.of(
                confirmed,
                new double[]{113.32, 23.18},
                new double[]{113.4, 23.2}
        );

        RouteCorrectionPathBuilder.Result result = RouteCorrectionPathBuilder.splice(
                original, 0.45, confirmed, remaining);

        assertArrayEquals(original.get(0), result.coordinates().get(0), 0.000001);
        assertArrayEquals(original.get(original.size() - 1),
                result.coordinates().get(result.coordinates().size() - 1), 0.000001);
        assertTrue(result.coordinates().stream().anyMatch(point ->
                Math.abs(point[0] - confirmed[0]) < 0.000001
                        && Math.abs(point[1] - confirmed[1]) < 0.000001));
        assertTrue(result.totalDistanceKm() > result.completedDistanceKm());
        assertTrue(result.progress() > 0 && result.progress() < 1);
    }

    @Test
    void repeatedCorrectionKeepsTheOriginalOrderStart() {
        List<double[]> firstCorrection = RouteCorrectionPathBuilder.splice(
                List.of(new double[]{112.9, 23.0}, new double[]{113.5, 23.4}),
                0.3,
                new double[]{113.0, 23.2},
                List.of(new double[]{113.0, 23.2}, new double[]{113.5, 23.4})
        ).coordinates();

        RouteCorrectionPathBuilder.Result secondCorrection = RouteCorrectionPathBuilder.splice(
                firstCorrection,
                0.55,
                new double[]{113.2, 23.32},
                List.of(new double[]{113.2, 23.32}, new double[]{113.5, 23.4})
        );

        assertArrayEquals(new double[]{112.9, 23.0}, secondCorrection.coordinates().get(0), 0.000001);
        assertArrayEquals(new double[]{113.5, 23.4},
                secondCorrection.coordinates().get(secondCorrection.coordinates().size() - 1), 0.000001);
        assertEquals(1, secondCorrection.coordinates().stream()
                .filter(point -> Math.abs(point[0] - 113.2) < 0.000001
                        && Math.abs(point[1] - 23.32) < 0.000001)
                .count());
        assertTrue(secondCorrection.coordinates().stream().anyMatch(point ->
                Math.abs(point[0] - 113.0) < 0.000001
                        && Math.abs(point[1] - 23.2) < 0.000001));
    }

    @Test
    void joinsRoadPlannedPrefixAndRemainingWithoutStraightConnector() {
        List<double[]> original = List.of(
                new double[]{112.9, 23.0},
                new double[]{113.5, 23.4}
        );
        double[] confirmed = new double[]{113.1, 23.3};
        double[] prefixRoadBend = new double[]{112.95, 23.18};
        double[] remainingRoadBend = new double[]{113.32, 23.36};

        RouteCorrectionPathBuilder.Result result = RouteCorrectionPathBuilder.joinPlanned(
                original,
                confirmed,
                List.of(original.get(0), prefixRoadBend, confirmed),
                List.of(confirmed, remainingRoadBend, original.get(1))
        );

        assertArrayEquals(original.get(0), result.coordinates().get(0), 0.000001);
        assertArrayEquals(prefixRoadBend, result.coordinates().get(1), 0.000001);
        assertArrayEquals(confirmed, result.coordinates().get(2), 0.000001);
        assertArrayEquals(remainingRoadBend, result.coordinates().get(3), 0.000001);
        assertArrayEquals(original.get(1), result.coordinates().get(4), 0.000001);
        assertTrue(result.progress() > 0 && result.progress() < 1);
    }

    @Test
    void partitionsStitchedRouteAtTheSameBoundaryWithoutDroppingHistory() {
        List<double[]> route = List.of(
                new double[]{112.9, 23.0},
                new double[]{113.0, 23.2},
                new double[]{113.2, 23.32},
                new double[]{113.5, 23.4}
        );

        RouteCorrectionPathBuilder.Partition partition = RouteCorrectionPathBuilder.partition(route, 0.6);

        assertArrayEquals(route.get(0), partition.traversedCoordinates().get(0), 0.000001);
        assertArrayEquals(route.get(route.size() - 1),
                partition.remainingCoordinates().get(partition.remainingCoordinates().size() - 1), 0.000001);
        assertArrayEquals(
                partition.traversedCoordinates().get(partition.traversedCoordinates().size() - 1),
                partition.remainingCoordinates().get(0),
                0.000001);
    }
}
