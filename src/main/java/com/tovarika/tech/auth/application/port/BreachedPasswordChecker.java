package com.tovarika.tech.auth.application.port;

public interface BreachedPasswordChecker {

    boolean isCompromised(String password);
}
