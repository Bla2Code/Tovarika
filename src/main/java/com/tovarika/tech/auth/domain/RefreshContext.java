package com.tovarika.tech.auth.domain;

public record RefreshContext(RefreshCredential credential, AuthenticationSession session, UserAccount user) {}
