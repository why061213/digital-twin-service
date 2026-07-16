package com.jushen.digitaltwin.townroad;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistrictRoadGraphTest {

    private DistrictRoadGraph graph;

    @BeforeEach
    void setUp() {
        CityRoadGraph cityGraph = new CityRoadGraph(new ProvinceCodeResolver());
        graph = new DistrictRoadGraph(cityGraph);
    }

    @Test
    void prefersAuthoredBorderChainOverGenericCityFallback() {
        List<String> path = graph.shortestPath("230722", "230883", Set.of("230000"));

        assertFalse(path.isEmpty());
        assertTrue(path.contains("230781"), "应经过伊春边境链出口");
        assertTrue(path.contains("230803"), "应经过佳木斯边境链入口");
    }

    @Test
    void crossesQiongzhouStraitOnlyThroughExplicitMaritimeGateway() {
        List<String> path = graph.shortestPath("440803", "460106", Set.of("440000", "460000"));

        assertFalse(path.isEmpty());
        assertTrue(path.contains("440825"), "广东侧必须经徐闻县");
        assertTrue(path.contains("460105"), "海南侧必须经秀英区");
    }

    @Test
    void resolvesExactDistrictBeforeFallingBackToCityRepresentative() {
        ExternalOrderRecord.Location exact = new ExternalOrderRecord.Location(
                "徐闻", "广东省", "湛江市", "徐闻县", "440825", new double[]{110.1, 20.3}
        );
        ExternalOrderRecord.Location cityOnly = new ExternalOrderRecord.Location(
                "湛江", "广东省", "湛江市", null, "440800", new double[]{110.3, 21.2}
        );

        assertEquals("440825", graph.districtCodeFor(exact));
        assertEquals("440802", graph.districtCodeFor(cityOnly));
    }
}
