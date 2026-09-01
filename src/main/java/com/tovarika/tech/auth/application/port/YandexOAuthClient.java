package com.tovarika.tech.auth.application.port;

public interface YandexOAuthClient {

    YandexProfile exchangeCode(String code, String pkceVerifier);

    record YandexProfile(String providerUserId, String email, String displayName) {}
}
