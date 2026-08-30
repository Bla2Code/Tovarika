package com.tovarika.tech.auth.application;

public record OAuthCompletion(String returnPath, SessionGrant grant) {}
