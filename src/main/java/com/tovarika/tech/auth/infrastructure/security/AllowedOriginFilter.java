package com.tovarika.tech.auth.infrastructure.security;

import com.tovarika.api.publicapi.model.ErrorCodeDto;
import com.tovarika.tech.auth.application.AuthenticationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AllowedOriginFilter extends OncePerRequestFilter {

    private static final Set<String> COOKIE_MUTATIONS = Set.of(
            "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/password-change");

    private final Set<String> allowedOrigins;
    private final SecurityErrorWriter errorWriter;

    public AllowedOriginFilter(AuthenticationProperties properties, SecurityErrorWriter errorWriter) {
        this.allowedOrigins = Set.copyOf(properties.cors().allowedOrigins());
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !COOKIE_MUTATIONS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null || !allowedOrigins.contains(origin)) {
            errorWriter.write(request, response, 403, ErrorCodeDto.FORBIDDEN, "Origin is not allowed");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
