package com.tovarika.tech.auth.infrastructure.persistence;

import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.auth.domain.AuthIdentity;
import com.tovarika.tech.auth.domain.AuthProvider;
import com.tovarika.tech.auth.domain.AuthenticationSession;
import com.tovarika.tech.auth.domain.EmailCredentials;
import com.tovarika.tech.auth.domain.OAuthAttempt;
import com.tovarika.tech.auth.domain.OneTimeToken;
import com.tovarika.tech.auth.domain.RefreshContext;
import com.tovarika.tech.auth.domain.RefreshCredential;
import com.tovarika.tech.auth.domain.UserAccount;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class JpaAuthenticationStore implements AuthenticationStore {

    private final UserJpaRepository users;
    private final AuthIdentityJpaRepository identities;
    private final AuthSessionJpaRepository sessions;
    private final RefreshTokenJpaRepository refreshTokens;
    private final PasswordResetTokenJpaRepository resetTokens;
    private final OAuthAttemptJpaRepository oauthAttempts;
    private final TrialSessionJpaRepository trialSessions;

    public JpaAuthenticationStore(
            UserJpaRepository users,
            AuthIdentityJpaRepository identities,
            AuthSessionJpaRepository sessions,
            RefreshTokenJpaRepository refreshTokens,
            PasswordResetTokenJpaRepository resetTokens,
            OAuthAttemptJpaRepository oauthAttempts,
            TrialSessionJpaRepository trialSessions) {
        this.users = users;
        this.identities = identities;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.resetTokens = resetTokens;
        this.oauthAttempts = oauthAttempts;
        this.trialSessions = trialSessions;
    }

    @Override
    public Optional<UserAccount> findUserById(String userId) {
        return users.findById(userId).map(this::toDomain);
    }

    @Override
    public Optional<EmailCredentials> findEmailCredentials(String normalizedEmail) {
        return identities.findWithUser(AuthProvider.EMAIL, normalizedEmail)
                .map(entity -> new EmailCredentials(toDomain(entity.user), toDomain(entity)));
    }

    @Override
    public Optional<AuthIdentity> findIdentity(AuthProvider provider, String providerUserId) {
        return identities.findWithUser(provider, providerUserId).map(this::toDomain);
    }

    @Override
    public Set<AuthProvider> findProviders(String userId) {
        return new LinkedHashSet<>(identities.findProviders(userId));
    }

    @Override
    public void createUserWithIdentity(UserAccount user, AuthIdentity identity) {
        UserEntity userEntity = toEntity(user);
        users.save(userEntity);
        identities.save(toEntity(identity, userEntity));
    }

    @Override
    public void createIdentity(AuthIdentity identity) {
        identities.save(toEntity(identity, users.getReferenceById(identity.userId())));
    }

    @Override
    public void updateUserProfile(String userId, String displayName, Instant now) {
        UserEntity user = users.getReferenceById(userId);
        user.displayName = displayName;
        user.updatedAt = now;
    }

    @Override
    public void updatePassword(String identityId, String passwordHash, Instant now) {
        AuthIdentityEntity identity = identities.getReferenceById(identityId);
        identity.passwordHash = passwordHash;
        identity.updatedAt = now;
    }

    @Override
    public void revokePasswordResetTokens(String identityId, Instant now) {
        resetTokens.revokeActive(identityId, now);
    }

    @Override
    public void createPasswordResetToken(OneTimeToken token) {
        PasswordResetTokenEntity entity = new PasswordResetTokenEntity();
        entity.id = token.id();
        entity.identity = identities.getReferenceById(token.identityId());
        entity.user = users.getReferenceById(token.userId());
        entity.tokenHash = token.tokenHash();
        entity.createdAt = token.createdAt();
        entity.expiresAt = token.expiresAt();
        entity.consumedAt = token.consumedAt();
        resetTokens.save(entity);
    }

    @Override
    public Optional<OneTimeToken> lockPasswordResetToken(String tokenHash) {
        return resetTokens.lockByHash(tokenHash).map(entity -> new OneTimeToken(
                entity.id,
                entity.user.id,
                entity.identity.id,
                entity.tokenHash,
                entity.createdAt,
                entity.expiresAt,
                entity.consumedAt));
    }

    @Override
    public boolean consumePasswordResetToken(String tokenId, Instant now) {
        return resetTokens.consume(tokenId, now) == 1;
    }

    @Override
    public void createSession(AuthenticationSession session, RefreshCredential credential) {
        AuthSessionEntity sessionEntity = toEntity(session);
        sessions.save(sessionEntity);
        refreshTokens.save(toEntity(credential, sessionEntity));
    }

    @Override
    public Optional<RefreshContext> lockRefreshCredential(String tokenHash) {
        return refreshTokens.lockByHash(tokenHash).map(entity -> new RefreshContext(
                toDomain(entity), toDomain(entity.session), toDomain(entity.session.user)));
    }

    @Override
    public boolean replaceRefreshCredential(String oldCredentialId, RefreshCredential replacement, Instant now) {
        if (refreshTokens.consume(oldCredentialId, replacement.id(), now) != 1) {
            return false;
        }
        refreshTokens.save(toEntity(replacement, sessions.getReferenceById(replacement.sessionId())));
        return true;
    }

    @Override
    public void touchSession(String sessionId, Instant now, String deviceName, String approximateIp) {
        sessions.touch(sessionId, now, deviceName, approximateIp);
    }

    @Override
    public void revokeFamily(String tokenFamilyId, Instant now) {
        sessions.revokeFamily(tokenFamilyId, now);
    }

    @Override
    public void revokeAllUserSessions(String userId, Instant now) {
        sessions.revokeAll(userId, now);
    }

    @Override
    public void revokeAllUserSessionsExcept(String userId, String retainedSessionId, Instant now) {
        sessions.revokeAllExcept(userId, retainedSessionId, now);
    }

    @Override
    public boolean isAccessSessionActive(String userId, String sessionId, Instant now, Instant inactivityCutoff) {
        return sessions.isActive(userId, sessionId, now, inactivityCutoff);
    }

    @Override
    public Optional<AuthenticationSession> findSession(String sessionId) {
        return sessions.findById(sessionId).map(this::toDomain);
    }

    @Override
    public List<AuthenticationSession> findUserSessions(String userId) {
        return sessions.findAllByUser(userId).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean revokeOwnedSession(String userId, String sessionId, Instant now) {
        return sessions.revokeOwned(userId, sessionId, now) == 1;
    }

    @Override
    public void createOAuthAttempt(OAuthAttempt attempt) {
        OAuthAttemptEntity entity = new OAuthAttemptEntity();
        entity.id = attempt.id();
        entity.provider = attempt.provider();
        entity.stateHash = attempt.stateHash();
        entity.correlationHash = attempt.correlationHash();
        entity.pkceVerifier = attempt.pkceVerifier();
        entity.returnPath = attempt.returnPath();
        entity.createdAt = attempt.createdAt();
        entity.expiresAt = attempt.expiresAt();
        entity.consumedAt = attempt.consumedAt();
        oauthAttempts.save(entity);
    }

    @Override
    public Optional<OAuthAttempt> lockOAuthAttempt(String correlationHash) {
        return oauthAttempts.findByCorrelationHash(correlationHash).map(this::toDomain);
    }

    @Override
    public boolean consumeOAuthAttempt(String attemptId, Instant now) {
        return oauthAttempts.consume(attemptId, now) == 1;
    }

    @Override
    public boolean convertTrial(String trialTokenHash, String userId, Instant now) {
        TrialSessionEntity trial = trialSessions.findByTokenHash(trialTokenHash).orElse(null);
        if (trial == null || trial.owner != null || !now.isBefore(trial.expiresAt)) {
            return false;
        }
        trial.owner = users.getReferenceById(userId);
        trial.convertedAt = now;
        return true;
    }

    private UserAccount toDomain(UserEntity entity) {
        return new UserAccount(
                entity.id,
                entity.email,
                entity.emailVerified,
                entity.displayName,
                entity.avatarUrl == null ? null : URI.create(entity.avatarUrl),
                entity.status,
                entity.createdAt,
                entity.updatedAt);
    }

    private AuthIdentity toDomain(AuthIdentityEntity entity) {
        return new AuthIdentity(
                entity.id,
                entity.user.id,
                entity.provider,
                entity.providerUserId,
                entity.passwordHash,
                entity.createdAt,
                entity.updatedAt);
    }

    private AuthenticationSession toDomain(AuthSessionEntity entity) {
        return new AuthenticationSession(
                entity.id,
                entity.user.id,
                entity.tokenFamilyId,
                entity.createdAt,
                entity.lastActivityAt,
                entity.absoluteExpiresAt,
                entity.revokedAt,
                entity.deviceName,
                entity.approximateIp);
    }

    private RefreshCredential toDomain(RefreshTokenEntity entity) {
        return new RefreshCredential(
                entity.id,
                entity.session.id,
                entity.tokenHash,
                entity.createdAt,
                entity.consumedAt,
                entity.rotatedAt,
                entity.replacementTokenId);
    }

    private OAuthAttempt toDomain(OAuthAttemptEntity entity) {
        return new OAuthAttempt(
                entity.id,
                entity.provider,
                entity.stateHash,
                entity.correlationHash,
                entity.pkceVerifier,
                entity.returnPath,
                entity.createdAt,
                entity.expiresAt,
                entity.consumedAt);
    }

    private UserEntity toEntity(UserAccount user) {
        UserEntity entity = new UserEntity();
        entity.id = user.id();
        entity.email = user.email();
        entity.emailVerified = user.emailVerified();
        entity.displayName = user.displayName();
        entity.avatarUrl = user.avatarUrl() == null ? null : user.avatarUrl().toString();
        entity.status = user.status();
        entity.createdAt = user.createdAt();
        entity.updatedAt = user.updatedAt();
        return entity;
    }

    private AuthIdentityEntity toEntity(AuthIdentity identity, UserEntity user) {
        AuthIdentityEntity entity = new AuthIdentityEntity();
        entity.id = identity.id();
        entity.user = user;
        entity.provider = identity.provider();
        entity.providerUserId = identity.providerUserId();
        entity.passwordHash = identity.passwordHash();
        entity.createdAt = identity.createdAt();
        entity.updatedAt = identity.updatedAt();
        return entity;
    }

    private AuthSessionEntity toEntity(AuthenticationSession session) {
        AuthSessionEntity entity = new AuthSessionEntity();
        entity.id = session.id();
        entity.user = users.getReferenceById(session.userId());
        entity.tokenFamilyId = session.tokenFamilyId();
        entity.createdAt = session.createdAt();
        entity.lastActivityAt = session.lastActivityAt();
        entity.absoluteExpiresAt = session.absoluteExpiresAt();
        entity.revokedAt = session.revokedAt();
        entity.deviceName = session.deviceName();
        entity.approximateIp = session.approximateIp();
        return entity;
    }

    private RefreshTokenEntity toEntity(RefreshCredential credential, AuthSessionEntity session) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.id = credential.id();
        entity.session = session;
        entity.tokenHash = credential.tokenHash();
        entity.createdAt = credential.createdAt();
        entity.consumedAt = credential.consumedAt();
        entity.rotatedAt = credential.rotatedAt();
        entity.replacementTokenId = credential.replacementTokenId();
        return entity;
    }
}
