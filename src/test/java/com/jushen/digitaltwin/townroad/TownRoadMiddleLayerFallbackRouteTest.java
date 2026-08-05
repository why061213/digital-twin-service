package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.baidu.RoutePlanningService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TownRoadMiddleLayerFallbackRouteTest {

    @Test
    void fallbackUsesOnlyRealEndpointsInsteadOfAdministrativeCenters() {
        TownRoadCoordinateResolver coordinateResolver = mock(TownRoadCoordinateResolver.class);
        ChinaBoundaryConstraint boundaryConstraint = mock(ChinaBoundaryConstraint.class);
        when(boundaryConstraint.constrainRoute(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        TownRoadMiddleLayer middleLayer = new TownRoadMiddleLayer(
                mock(ObjectMapper.class), mock(ProvinceRoadGraph.class), mock(CityRoadGraph.class),
                mock(DistrictRoadGraph.class), mock(ProvinceCodeResolver.class),
                new TownRoadExternalOrderProperties(), coordinateResolver,
                mock(DailyOrderStatisticsService.class), boundaryConstraint,
                mock(RoutePlanningService.class));
        ExternalOrderRecord.Location from = new ExternalOrderRecord.Location(
                "起点", "广西壮族自治区", "崇左市", "龙州县", "451423", new double[]{106.85, 22.34});
        ExternalOrderRecord.Location to = new ExternalOrderRecord.Location(
                "终点", "云南省", "文山壮族苗族自治州", "富宁县", "532628", new double[]{105.63, 23.63});

        List<double[]> route = middleLayer.routeCoordinatesFor(from, to);

        assertThat(route).hasSize(2);
        assertThat(route.get(0)).containsExactly(106.85, 22.34);
        assertThat(route.get(1)).containsExactly(105.63, 23.63);
        verifyNoInteractions(coordinateResolver);
    }
}
