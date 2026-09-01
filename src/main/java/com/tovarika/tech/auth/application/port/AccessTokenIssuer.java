package com.tovarika.tech.auth.application.port;

public interface AccessTokenIssuer {

    String issue(String userId, String sessionId);

    int expiresInSeconds();
}
