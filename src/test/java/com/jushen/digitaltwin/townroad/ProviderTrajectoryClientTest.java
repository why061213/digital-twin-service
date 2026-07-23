package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.service.RoutePushService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderTrajectoryClientTest {
    @Test
    void parsesSortsAndAppliesGcjOffsets() {
        ProviderTrajectoryClient client = new ProviderTrajectoryClient(
                new ObjectMapper(), mock(RoutePushService.class), "http://provider", 5000);
        String response = """
                {"code":200,"data":[
                  {"gpstime":"2026-07-23 08:04:00","lng":"113.0","lat":"23.0","glng":"0.004","glat":"-0.002"},
                  {"gpstime":"2026-07-23 08:02:00","lng":"112.9","lat":"22.9","glng":"0","glat":"0"}
                ]}
                """;

        ProviderTrajectoryClient.TrajectoryResult result = client.parseResponse(response);

        assertThat(result.success()).isTrue();
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().get(0).lng()).isEqualTo(112.9);
        assertThat(result.points().get(1).lng()).isEqualTo(113.004);
        assertThat(result.points().get(1).lat()).isEqualTo(22.998);
    }
}
