package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.baidu.RoutePlanningService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TownRoadMiddleLayerLocationResolutionTest {

    @Test
    void resolvesStopCoordinatesBeforeVehicleTripTopologyIsBuilt() {
        TownRoadCoordinateResolver resolver = mock(TownRoadCoordinateResolver.class);
        ExternalOrderRecord.Location rawFrom = new ExternalOrderRecord.Location(
                "佛山装载点", "广东省", "佛山市", "南海区", "440605", null);
        ExternalOrderRecord.Location rawTo = new ExternalOrderRecord.Location(
                "清远目的地", "广东省", "清远市", "清城区", "441802", null);
        ExternalOrderRecord.Location resolvedFrom = new ExternalOrderRecord.Location(
                "佛山装载点", "广东省", "佛山市", "南海区", "440605", new double[]{112.9, 23.0});
        ExternalOrderRecord.Location resolvedTo = new ExternalOrderRecord.Location(
                "清远目的地", "广东省", "清远市", "清城区", "441802", new double[]{113.1, 23.7});
        when(resolver.resolveLocation(rawFrom)).thenReturn(resolvedFrom);
        when(resolver.resolveLocation(rawTo)).thenReturn(resolvedTo);

        TownRoadMiddleLayer middleLayer = new TownRoadMiddleLayer(
                mock(ObjectMapper.class), mock(ProvinceRoadGraph.class), mock(CityRoadGraph.class),
                mock(DistrictRoadGraph.class), mock(ProvinceCodeResolver.class),
                new TownRoadExternalOrderProperties(), resolver,
                mock(DailyOrderStatisticsService.class), mock(ChinaBoundaryConstraint.class),
                mock(RoutePlanningService.class));
        ExternalOrderRecord raw = new ExternalOrderRecord(
                "order-1", "line-1", null, rawFrom, rawTo,
                new ExternalOrderRecord.Vehicle("桂L91622", "vehicle-1", "铝锭", 35d, "吨", null, null),
                "运输中", "2026-07-28 09:00:00", false, false, null, null);

        ExternalOrderRecord resolved = middleLayer.resolveOrderLocations(raw);

        assertThat(resolved.from().coords()).containsExactly(112.9, 23.0);
        assertThat(resolved.to().coords()).containsExactly(113.1, 23.7);
        assertThat(resolved.orderId()).isEqualTo(raw.orderId());
        assertThat(resolved.vehicle()).isSameAs(raw.vehicle());
    }
}
