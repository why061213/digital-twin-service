package com.jushen.digitaltwin.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardAccessServiceTest {

    private final DashboardAccessService service = new DashboardAccessService(new DashboardAccessProperties());

    @Test
    void acceptsForwardedClientAddressOnlyFromLoopbackProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "192.168.5.83, 127.0.0.1");

        assertEquals("192.168.5.83", service.resolveRemoteAddress(request));
    }

    @Test
    void ignoresSpoofedForwardedAddressFromLanClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.5.83");
        request.addHeader("X-Forwarded-For", "192.168.5.99");

        assertEquals("192.168.5.83", service.resolveRemoteAddress(request));
    }
}
