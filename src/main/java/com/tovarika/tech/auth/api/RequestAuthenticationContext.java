package com.tovarika.tech.auth.api;

import com.tovarika.tech.auth.application.AuthErrorCode;
import com.tovarika.tech.auth.application.AuthException;
import com.tovarika.tech.auth.application.RequestMetadata;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class RequestAuthenticationContext {

    private final HttpServletRequest request;

    public RequestAuthenticationContext(HttpServletRequest request) {
        this.request = request;
    }

    public Principal principal() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)
                || !token.isAuthenticated()) {
            throw AuthException.unauthorized(AuthErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required");
        }
        return new Principal(token.getToken().getSubject(), token.getToken().getClaimAsString("sid"));
    }

    public String cookie(String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    public RequestMetadata metadata() {
        String userAgent = truncate(request.getHeader("User-Agent"), 200);
        return new RequestMetadata(userAgent, approximateIp(request.getRemoteAddr()));
    }

    public String rateLimitSubject(String discriminator) {
        return request.getRemoteAddr() + "|" + discriminator;
    }

    private String approximateIp(String address) {
        if (address == null) {
            return null;
        }
        if (address.contains(".")) {
            int lastDot = address.lastIndexOf('.');
            return truncate(address.substring(0, lastDot + 1) + "xxx", 64);
        }
        if (address.contains(":")) {
            String[] groups = address.split(":", -1);
            return truncate(String.join(":", Arrays.copyOf(groups, Math.min(groups.length, 4))) + "::", 64);
        }
        return truncate(address, 64);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record Principal(String userId, String sessionId) {}
}
