package com.tovarika.tech.auth.application.port;

public interface OpaqueTokenService {

    String generate();

    String hash(String rawToken);
}
