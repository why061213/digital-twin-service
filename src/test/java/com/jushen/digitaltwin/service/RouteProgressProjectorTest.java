package com.jushen.digitaltwin.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteProgressProjectorTest {

    @Test
    void normalizesWindowProjectionAgainstWholeRoute() {
        List<double[]> route = new ArrayList<>();
        for (int index = 0; index <= 10; index++) {
            route.add(new double[]{113 + index * 0.01, 23});
        }

        double progress = RouteProgressProjector.project(
                route,
                new double[]{113.04, 23},
                0.4
        );

        assertEquals(0.4, progress, 0.001);
    }

    @Test
    void usesHintWindowToChooseTheExpectedSideOfUShape() {
        List<double[]> route = List.of(
                new double[]{113.00, 23.00},
                new double[]{113.10, 23.00},
                new double[]{113.10, 23.01},
                new double[]{113.00, 23.01}
        );

        double progress = RouteProgressProjector.project(
                route,
                new double[]{113.05, 23.005},
                0.8
        );

        assertTrue(progress > 0.7 && progress < 0.9, "应投影到 U 形路线后半段");
    }
}
