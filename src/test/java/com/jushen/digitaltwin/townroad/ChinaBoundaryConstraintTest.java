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
}
