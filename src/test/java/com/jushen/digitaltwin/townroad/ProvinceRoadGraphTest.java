package com.jushen.digitaltwin.townroad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvinceRoadGraphTest {

    private final ProvinceRoadGraph graph = new ProvinceRoadGraph();

    @Test
    void shortHaulAllowsSameProvinceAndAdjacentProvinces() {
        assertTrue(graph.isSameOrAdjacent("440000", "440000"));
        assertTrue(graph.isSameOrAdjacent("440000", "450000"));
        assertTrue(graph.isSameOrAdjacent("450000", "440000"));
    }

    @Test
    void shortHaulRejectsNonAdjacentAndUnknownProvinces() {
        assertFalse(graph.isSameOrAdjacent("440000", "110000"));
        assertFalse(graph.isSameOrAdjacent("", "440000"));
        assertFalse(graph.isSameOrAdjacent("unknown", "440000"));
    }
}
