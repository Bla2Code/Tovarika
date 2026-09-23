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
            "/api/v1/trial-session", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/password-change");

    private final Set<String> allowedOrigins;
    private final SecurityErrorWriter errorWriter;
    private final String trialCookieName;

    public AllowedOriginFilter(AuthenticationProperties properties, SecurityErrorWriter errorWriter) {
        this.allowedOrigins = Set.copyOf(properties.cors().allowedOrigins());
        this.errorWriter = errorWriter;
        this.trialCookieName = properties.cookie().trialName();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean authMutation = HttpMethod.POST.matches(request.getMethod())
                && COOKIE_MUTATIONS.contains(request.getRequestURI());
        boolean projectMutation = (HttpMethod.PATCH.matches(request.getMethod())
                || HttpMethod.DELETE.matches(request.getMethod()))
                && request.getRequestURI().matches("/api/v1/projects/[^/]+/?")
                && !hasBearer(request) && hasTrialCookie(request);
        boolean productMutation = HttpMethod.POST.matches(request.getMethod())
                && request.getRequestURI().equals("/api/v1/products") && !hasBearer(request) && hasTrialCookie(request);
        return !authMutation && !projectMutation && !productMutation;
    }

    private boolean hasBearer(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private boolean hasTrialCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return false;
        }
        return java.util.Arrays.stream(request.getCookies())
                .anyMatch(cookie -> trialCookieName.equals(cookie.getName()));
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
