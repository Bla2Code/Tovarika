package com.tovarika.tech.auth.application;

import com.tovarika.tech.auth.application.port.AuthenticationMessageSender;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import com.tovarika.tech.auth.application.port.PasswordHasher;
import com.tovarika.tech.auth.domain.AuthIdentity;
import com.tovarika.tech.auth.domain.AuthProvider;
import com.tovarika.tech.auth.domain.EmailCredentials;
import com.tovarika.tech.auth.domain.OneTimeToken;
import com.tovarika.tech.auth.domain.UserAccount;
import com.tovarika.tech.auth.domain.UserStatus;
import com.tovarika.tech.auth.domain.UserView;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailAuthenticationService {

    private final AuthenticationStore store;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final OpaqueTokenService opaqueTokens;
    private final AuthenticationMessageSender messageSender;
    private final SessionService sessions;
    private final AuthenticationProperties properties;
    private final Clock clock;

    public EmailAuthenticationService(
            AuthenticationStore store,
            PasswordHasher passwordHasher,
            PasswordPolicy passwordPolicy,
            OpaqueTokenService opaqueTokens,
            AuthenticationMessageSender messageSender,
            SessionService sessions,
            AuthenticationProperties properties,
            Clock clock) {
        this.store = store;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.opaqueTokens = opaqueTokens;
        this.messageSender = messageSender;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public UserView register(String email, String password, String displayName, String rawTrialToken) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        passwordPolicy.validate(password);
        if (store.findEmailCredentials(normalizedEmail).isPresent()) {
            throw AuthException.conflict(AuthErrorCode.EMAIL_ALREADY_REGISTERED, "Email is already registered");
        }
        Instant now = clock.instant();
        UserAccount user = new UserAccount(
                AuthenticationIds.userId(),
                normalizedEmail,
                true,
                normalizeDisplayName(displayName),
                null,
                UserStatus.ACTIVE,
                now,
                now);
        AuthIdentity identity = new AuthIdentity(
                AuthenticationIds.identityId(),
                user.id(),
                AuthProvider.EMAIL,
                normalizedEmail,
                passwordHasher.hash(password),
                now,
                now);
        try {
            store.createUserWithIdentity(user, identity);
        } catch (DataIntegrityViolationException conflict) {
            throw AuthException.conflict(AuthErrorCode.EMAIL_ALREADY_REGISTERED, "Email is already registered");
        }
        if (rawTrialToken != null && !rawTrialToken.isBlank()) {
            store.convertTrial(opaqueTokens.hash(rawTrialToken), user.id(), now);
        }
        return new UserView(user, java.util.Set.of(AuthProvider.EMAIL));
    }

    @Transactional
    public SessionGrant login(String email, String password, RequestMetadata metadata) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        EmailCredentials credentials = store.findEmailCredentials(normalizedEmail).orElse(null);
        if (credentials == null) {
            passwordHasher.performDummyVerification(password);
            throw invalidCredentials();
        }
        if (!passwordHasher.matches(password, credentials.identity().passwordHash())) {
            throw invalidCredentials();
        }
        if (!credentials.user().isActive()) {
            throw invalidCredentials();
        }
        return sessions.create(credentials.user(), metadata);
    }

    @Transactional
    public void requestPasswordReset(String email) {
        store.findEmailCredentials(EmailNormalizer.normalize(email))
                .filter(credentials -> credentials.user().isActive())
                .ifPresent(this::issuePasswordReset);
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        Instant now = clock.instant();
        OneTimeToken token = store.lockPasswordResetToken(opaqueTokens.hash(rawToken))
                .orElseThrow(() -> AuthException.unauthorized(
                        AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID, "Password reset token is invalid"));
        if (!token.isUsableAt(now)) {
            throw AuthException.unauthorized(
                    AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID, "Password reset token is invalid");
        }
        passwordPolicy.validate(newPassword);
        if (!store.consumePasswordResetToken(token.id(), now)) {
            throw AuthException.unauthorized(
                    AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID, "Password reset token is invalid");
        }
        store.updatePassword(token.identityId(), passwordHasher.hash(newPassword), now);
        store.revokeAllUserSessions(token.userId(), now);
    }

    @Transactional(noRollbackFor = RefreshReuseDetectedException.class)
    public SessionGrant changePassword(
            String userId,
            String accessSessionId,
            String rawRefreshToken,
            String currentPassword,
            String newPassword,
            RequestMetadata metadata) {
        EmailCredentials credentials = store.findUserById(userId)
                .flatMap(user -> store.findEmailCredentials(user.email()))
                .filter(found -> found.user().id().equals(userId))
                .orElseThrow(this::invalidCredentials);
        if (!passwordHasher.matches(currentPassword, credentials.identity().passwordHash())) {
            throw invalidCredentials();
        }
        passwordPolicy.validate(newPassword);
        SessionGrant grant = sessions.rotateCurrent(userId, accessSessionId, rawRefreshToken, metadata);
        Instant now = clock.instant();
        store.updatePassword(credentials.identity().id(), passwordHasher.hash(newPassword), now);
        store.revokeAllUserSessionsExcept(userId, accessSessionId, now);
        return grant;
    }

    private void issuePasswordReset(EmailCredentials credentials) {
        Instant now = clock.instant();
        String rawToken = opaqueTokens.generate();
        store.revokePasswordResetTokens(credentials.identity().id(), now);
        store.createPasswordResetToken(new OneTimeToken(
                AuthenticationIds.tokenId(),
                credentials.user().id(),
                credentials.identity().id(),
                opaqueTokens.hash(rawToken),
                now,
                now.plus(properties.token().passwordResetTtl()),
                null));
        messageSender.sendPasswordReset(credentials.user().email(), rawToken);
    }

    private AuthException invalidCredentials() {
        return AuthException.unauthorized(AuthErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String normalized = displayName.strip();
        if (normalized.isEmpty()) {
            throw AuthException.unprocessable("Display name must not be blank");
        }
        return normalized;
    }
}
