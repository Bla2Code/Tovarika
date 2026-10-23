package com.tovarika.tech.billing.payments;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Payment mode is deployment configuration; only TEST can create payments for now. */
@ConfigurationProperties("tovarika.payments.robokassa")
public record RobokassaSettings(RobokassaMode mode, String merchantLogin, String testPassword1,
        String testPassword2, List<String> testUserIds) {
    public RobokassaSettings {
        mode = mode == null ? RobokassaMode.DISABLED : mode;
        merchantLogin = merchantLogin == null ? "" : merchantLogin;
        testPassword1 = testPassword1 == null ? "" : testPassword1;
        testPassword2 = testPassword2 == null ? "" : testPassword2;
        testUserIds = testUserIds == null ? List.of() : List.copyOf(testUserIds);
    }
    public boolean testCredentialsConfigured() {
        return merchantLogin.matches("[A-Za-z0-9_-]{1,100}") &&
                !testPassword1.isBlank() && !testPassword2.isBlank();
    }
    public boolean configured() {
        return mode == RobokassaMode.TEST && testCredentialsConfigured();
    }
    public boolean availableFor(String userId) {
        return configured() && userId != null && testUserIds.contains(userId);
    }
    @Override public String toString() { return "RobokassaSettings[redacted]"; }
}
