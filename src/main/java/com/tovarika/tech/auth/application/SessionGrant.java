package com.tovarika.tech.auth.application;

import com.tovarika.tech.auth.domain.UserView;

public record SessionGrant(String accessToken, int expiresIn, String rawRefreshToken, String sessionId, UserView user) {}
