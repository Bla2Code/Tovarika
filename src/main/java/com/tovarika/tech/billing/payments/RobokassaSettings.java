package com.tovarika.tech.billing.payments;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment mode controls new checkouts; existing orders retain their own mode. */
@ConfigurationProperties("tovarika.payments.robokassa")
public record RobokassaSettings(RobokassaMode mode, String merchantLogin, String testPassword1,
        String testPassword2, List<String> testUserIds, String livePassword1, String livePassword2) {
    public RobokassaSettings {
        mode = mode == null ? RobokassaMode.DISABLED : mode;
        merchantLogin = merchantLogin == null ? "" : merchantLogin;
        testPassword1 = testPassword1 == null ? "" : testPassword1;
        testPassword2 = testPassword2 == null ? "" : testPassword2;
        testUserIds = testUserIds == null ? List.of() : List.copyOf(testUserIds);
        livePassword1 = livePassword1 == null ? "" : livePassword1;
        livePassword2 = livePassword2 == null ? "" : livePassword2;
    }
    public boolean testCredentialsConfigured() {
        return merchantLogin.matches("[A-Za-z0-9_-]{1,100}") &&
                !testPassword1.isBlank() && !testPassword2.isBlank();
    }
    public boolean configured() {
        return switch (mode) {
            case DISABLED -> false;
            case TEST -> testCredentialsConfigured();
            case LIVE -> liveCredentialsConfigured();
        };
    }
    public boolean liveCredentialsConfigured() {
        return merchantLogin.matches("[A-Za-z0-9_-]{1,100}") &&
                !livePassword1.isBlank() && !livePassword2.isBlank() &&
                !livePassword1.equals(testPassword1) && !livePassword2.equals(testPassword2);
    }
    public boolean credentialsConfigured(boolean testMode) {
        return testMode ? testCredentialsConfigured() : liveCredentialsConfigured();
    }
    public String password1(boolean testMode) {
        return testMode ? testPassword1 : livePassword1;
    }
    public String password2(boolean testMode) {
        return testMode ? testPassword2 : livePassword2;
    }
    public boolean availableFor(String userId) {
        return configured() && userId != null && !userId.isBlank() &&
                (mode == RobokassaMode.LIVE || testUserIds.contains(userId));
    }
    @Override public String toString() { return "RobokassaSettings[redacted]"; }
}
