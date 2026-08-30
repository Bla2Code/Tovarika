package com.tovarika.tech.auth.infrastructure.security;

import com.tovarika.tech.auth.application.AuthenticationProperties;
import com.tovarika.tech.auth.application.port.AccessTokenIssuer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthenticationProperties properties;
    private final Clock clock;

    public JwtAccessTokenIssuer(JwtEncoder encoder, AuthenticationProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String issue(String userId, String sessionId) {
        Instant issuedAt = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer().toString())
                .audience(List.of(properties.jwt().audience()))
                .subject(userId)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(properties.jwt().accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("sid", sessionId)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Override
    public int expiresInSeconds() {
        return Math.toIntExact(properties.jwt().accessTokenTtl().toSeconds());
    }
}
