package com.tovarika.tech.auth.application;

import com.tovarika.tech.auth.application.port.BreachedPasswordChecker;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

    private static final int MIN_CODE_POINTS = 12;
    private static final int MAX_CODE_POINTS = 128;
    private final BreachedPasswordChecker breachedPasswordChecker;

    public PasswordPolicy(BreachedPasswordChecker breachedPasswordChecker) {
        this.breachedPasswordChecker = breachedPasswordChecker;
    }

    public void validate(String password) {
        int characters = password.codePointCount(0, password.length());
        if (characters < MIN_CODE_POINTS || characters > MAX_CODE_POINTS) {
            throw AuthException.unprocessable("Password must contain between 12 and 128 Unicode characters");
        }
        if (breachedPasswordChecker.isCompromised(password)) {
            throw AuthException.unprocessable("Password is known to be compromised");
        }
    }
}
