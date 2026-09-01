package com.tovarika.tech.auth.api;

import com.tovarika.api.publicapi.model.AccessTokenDto;
import com.tovarika.api.publicapi.model.AuthProviderDto;
import com.tovarika.api.publicapi.model.AuthSessionDto;
import com.tovarika.api.publicapi.model.UserDto;
import com.tovarika.api.publicapi.model.UserStatusDto;
import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.SessionGrant;
import com.tovarika.tech.auth.domain.AuthProvider;
import com.tovarika.tech.auth.domain.AuthenticationSession;
import com.tovarika.tech.auth.domain.UserView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationDtoMapper {

    private final AuthenticationProperties properties;

    public AuthenticationDtoMapper(AuthenticationProperties properties) {
        this.properties = properties;
    }

    public AccessTokenDto accessToken(SessionGrant grant) {
        return new AccessTokenDto(
                grant.accessToken(), "Bearer", grant.expiresIn(), user(grant.user()));
    }

    public UserDto user(UserView view) {
        LinkedHashSet<AuthProviderDto> providers = new LinkedHashSet<>();
        view.providers().forEach(provider -> providers.add(provider(provider)));
        return new UserDto(
                        view.user().id(),
                        view.user().email(),
                        view.user().emailVerified(),
                        UserStatusDto.fromValue(view.user().status().name().toLowerCase(Locale.ROOT)),
                        providers,
                        dateTime(view.user().createdAt()),
                        dateTime(view.user().updatedAt()))
                .displayName(view.user().displayName())
                .avatarUrl(view.user().avatarUrl());
    }

    public AuthSessionDto session(AuthenticationSession session, String currentSessionId) {
        Instant inactivityExpiry = session.lastActivityAt().plus(properties.refresh().inactivityTtl());
        Instant effectiveExpiry = session.absoluteExpiresAt().isBefore(inactivityExpiry)
                ? session.absoluteExpiresAt()
                : inactivityExpiry;
        return new AuthSessionDto(
                        session.id(),
                        session.id().equals(currentSessionId),
                        dateTime(session.createdAt()),
                        dateTime(session.lastActivityAt()),
                        dateTime(effectiveExpiry))
                .deviceName(session.deviceName())
                .approximateIp(session.approximateIp());
    }

    private AuthProviderDto provider(AuthProvider provider) {
        return provider == AuthProvider.EMAIL ? AuthProviderDto.EMAIL : AuthProviderDto.YANDEX;
    }

    private OffsetDateTime dateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
