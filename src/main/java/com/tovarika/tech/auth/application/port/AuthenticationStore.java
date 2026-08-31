package com.tovarika.tech.auth.application.port;

import com.tovarika.tech.auth.domain.AuthIdentity;
import com.tovarika.tech.auth.domain.AuthProvider;
import com.tovarika.tech.auth.domain.AuthenticationSession;
import com.tovarika.tech.auth.domain.EmailCredentials;
import com.tovarika.tech.auth.domain.OAuthAttempt;
import com.tovarika.tech.auth.domain.OneTimeToken;
import com.tovarika.tech.auth.domain.RefreshContext;
import com.tovarika.tech.auth.domain.RefreshCredential;
import com.tovarika.tech.auth.domain.UserAccount;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AuthenticationStore {

    Optional<UserAccount> findUserById(String userId);

    Optional<EmailCredentials> findEmailCredentials(String normalizedEmail);

    Optional<AuthIdentity> findIdentity(AuthProvider provider, String providerUserId);

    Set<AuthProvider> findProviders(String userId);

    void createUserWithIdentity(UserAccount user, AuthIdentity identity);

    void createIdentity(AuthIdentity identity);

    void updateUserProfile(String userId, String displayName, Instant now);

    void updatePassword(String identityId, String passwordHash, Instant now);

    void revokePasswordResetTokens(String identityId, Instant now);

    void createPasswordResetToken(OneTimeToken token);

    Optional<OneTimeToken> lockPasswordResetToken(String tokenHash);

    boolean consumePasswordResetToken(String tokenId, Instant now);

    void createSession(AuthenticationSession session, RefreshCredential credential);

    Optional<RefreshContext> lockRefreshCredential(String tokenHash);

    boolean replaceRefreshCredential(String oldCredentialId, RefreshCredential replacement, Instant now);

    void touchSession(String sessionId, Instant now, String deviceName, String approximateIp);

    void revokeFamily(String tokenFamilyId, Instant now);

    void revokeAllUserSessions(String userId, Instant now);

    void revokeAllUserSessionsExcept(String userId, String retainedSessionId, Instant now);

    boolean isAccessSessionActive(String userId, String sessionId, Instant now, Instant inactivityCutoff);

    Optional<AuthenticationSession> findSession(String sessionId);

    List<AuthenticationSession> findUserSessions(String userId);

    boolean revokeOwnedSession(String userId, String sessionId, Instant now);

    void createOAuthAttempt(OAuthAttempt attempt);

    Optional<OAuthAttempt> lockOAuthAttempt(String correlationHash);

    boolean consumeOAuthAttempt(String attemptId, Instant now);

    boolean convertTrial(String trialTokenHash, String userId, Instant now);
}
