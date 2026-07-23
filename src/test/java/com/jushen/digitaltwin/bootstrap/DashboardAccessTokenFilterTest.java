package com.jushen.digitaltwin.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DashboardAccessTokenFilterTest {
    private final DashboardAccessTokenFilter filter = new DashboardAccessTokenFilter(
            mock(DashboardAccessTokenService.class), new ObjectMapper());

    @Test
    void vehicleOrderChainMetricsIsPublicButOtherApiRoutesRemainProtected() {
        MockHttpServletRequest publicRequest = new MockHttpServletRequest(
                "GET", "/api/public/vehicle-order-chain/transit-metrics");
        MockHttpServletRequest protectedRequest = new MockHttpServletRequest(
                "GET", "/api/road/town/latest");

        assertThat(filter.shouldNotFilter(publicRequest)).isTrue();
        assertThat(filter.shouldNotFilter(protectedRequest)).isFalse();
    }
}
