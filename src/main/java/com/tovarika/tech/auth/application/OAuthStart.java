package com.tovarika.tech.auth.application;

import java.net.URI;
import java.time.Instant;

public record OAuthStart(URI authorizationUrl, Instant expiresAt, String rawCorrelationToken) {}
