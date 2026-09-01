package com.tovarika.tech.auth.application;

import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.auth.domain.UserAccount;
import com.tovarika.tech.auth.domain.UserView;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAccountService {

    private final AuthenticationStore store;
    private final Clock clock;

    public UserAccountService(AuthenticationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserView get(String userId) {
        UserAccount user = store.findUserById(userId)
                .filter(UserAccount::isActive)
                .orElseThrow(() -> AuthException.forbidden(AuthErrorCode.FORBIDDEN, "User is not active"));
        return new UserView(user, store.findProviders(userId));
    }

    @Transactional
    public UserView updateDisplayName(String userId, String displayName) {
        String normalized = displayName == null ? null : displayName.strip();
        if (normalized == null || normalized.isEmpty()) {
            throw AuthException.unprocessable("Display name must not be blank");
        }
        store.updateUserProfile(userId, normalized, clock.instant());
        return get(userId);
    }
}
