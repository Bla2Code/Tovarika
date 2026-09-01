package com.tovarika.tech.auth.application;

import java.util.UUID;

public final class AuthenticationIds {

    private AuthenticationIds() {}

    public static String userId() {
        return id("usr_");
    }

    public static String identityId() {
        return id("aid_");
    }

    public static String sessionId() {
        return id("ses_");
    }

    public static String familyId() {
        return id("fam_");
    }

    public static String tokenId() {
        return id("tok_");
    }

    public static String oauthAttemptId() {
        return id("oaa_");
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
