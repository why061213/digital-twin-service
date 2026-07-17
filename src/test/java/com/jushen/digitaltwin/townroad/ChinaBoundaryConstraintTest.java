package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChinaBoundaryConstraintTest {

    private ChinaBoundaryConstraint constraint;

    @BeforeEach
    void setUp() {
        constraint = new ChinaBoundaryConstraint(new ObjectMapper());
    }

    @Test
    void acceptsARegularDomesticSegment() {
        assertTrue(constraint.segmentInside(
                new double[]{116.4074, 39.9042},
                new double[]{117.2000, 39.1333}
        ));
    }

    @Test
    void rejectsFrozenSeaCrossing() {
        assertFalse(constraint.segmentInside(
                new double[]{110.2060, 20.0500},
                new double[]{110.2060, 20.3500}
        ));
    }

    @Test
    void collapsesAnUnnecessaryLargeDomesticLoop() {
        List<double[]> repaired = constraint.constrainRoute(List.of(
                new double[]{113.2644, 23.1291},
                new double[]{104.0665, 30.5723},
                new double[]{116.4074, 39.9042},
                new double[]{114.3055, 30.5928}
        ));

        assertEquals(2, repaired.size());
        assertEquals(113.2644, repaired.get(0)[0], 0.000001);
        assertEquals(114.3055, repaired.get(1)[0], 0.000001);
    }

    @Test
    void repairsLongzhouToFuningWithoutTheOldEasternDetour() {
        List<double[]> repaired = constraint.constrainRoute(List.of(
                new double[]{106.847977, 22.336879},
                new double[]{107.589768, 23.329815},
                new double[]{105.747652, 23.465634}
        ));

        assertTrue(repaired.size() >= 2);
        assertTrue(pathLengthKm(repaired) < 260.0);
        for (int i = 1; i < repaired.size(); i++) {
            assertTrue(constraint.segmentInside(repaired.get(i - 1), repaired.get(i)));
        }
    }

    private double pathLengthKm(List<double[]> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] from = points.get(i - 1);
            double[] to = points.get(i);
            double lat1 = Math.toRadians(from[1]);
            double lat2 = Math.toRadians(to[1]);
            double deltaLat = lat2 - lat1;
            double deltaLng = Math.toRadians(to[0] - from[0]);
            double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                    + Math.cos(lat1) * Math.cos(lat2)
                    * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
            total += 6_371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }
        return total;
    }
}
