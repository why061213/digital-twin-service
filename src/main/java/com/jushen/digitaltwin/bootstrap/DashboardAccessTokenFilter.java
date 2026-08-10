package com.jushen.digitaltwin.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DashboardAccessTokenFilter extends OncePerRequestFilter {

    private final DashboardAccessTokenService accessTokenService;
    private final ObjectMapper objectMapper;

    public DashboardAccessTokenFilter(
            DashboardAccessTokenService accessTokenService,
            ObjectMapper objectMapper
    ) {
        this.accessTokenService = accessTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !path.startsWith("/api/")
                || path.startsWith("/api/bootstrap/")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/public/vehicle-order-chain/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        DashboardAccessTokenService.Validation validation = accessTokenService.validate(
                DashboardTokenSupport.extract(request)
        );
        if (validation.valid()) {
            filterChain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.addHeader("Vary", "Origin");
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "ok", false,
                "code", "dashboard_access_key_required",
                "message", "请通过验证页面获取访问密钥，并使用 Authorization: Bearer <key>"
        ));
    }
}
