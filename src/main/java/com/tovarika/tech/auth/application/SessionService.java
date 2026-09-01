package com.tovarika.tech.auth.application;

import com.tovarika.tech.auth.application.port.AccessTokenIssuer;
import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import com.tovarika.tech.auth.domain.AuthenticationSession;
import com.tovarika.tech.auth.domain.RefreshContext;
import com.tovarika.tech.auth.domain.RefreshCredential;
import com.tovarika.tech.auth.domain.UserAccount;
import com.tovarika.tech.auth.domain.UserView;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

    private final AuthenticationStore store;
    private final OpaqueTokenService opaqueTokens;
    private final AccessTokenIssuer accessTokens;
    private final AuthenticationProperties properties;
    private final Clock clock;

    public SessionService(
            AuthenticationStore store,
            OpaqueTokenService opaqueTokens,
            AccessTokenIssuer accessTokens,
            AuthenticationProperties properties,
            Clock clock) {
        this.store = store;
        this.opaqueTokens = opaqueTokens;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public SessionGrant create(UserAccount user, RequestMetadata metadata) {
        if (!user.isActive()) {
            throw AuthException.forbidden(AuthErrorCode.FORBIDDEN, "User is not active");
        }
        Instant now = clock.instant();
        String sessionId = AuthenticationIds.sessionId();
        String rawRefresh = opaqueTokens.generate();
        AuthenticationSession session = new AuthenticationSession(
                sessionId,
                user.id(),
                AuthenticationIds.familyId(),
                now,
                now,
                now.plus(properties.refresh().absoluteTtl()),
                null,
                metadata.deviceName(),
                metadata.approximateIp());
        RefreshCredential credential = new RefreshCredential(
                AuthenticationIds.tokenId(),
                sessionId,
                opaqueTokens.hash(rawRefresh),
                now,
                null,
                null,
                null);
        store.createSession(session, credential);
        return grant(user, sessionId, rawRefresh);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public SessionGrant rotate(String rawRefreshToken, RequestMetadata metadata) {
        RefreshContext context = lockAndValidate(rawRefreshToken, null, null);
        return rotateLocked(context, metadata);
    }

    public SessionGrant rotateCurrent(
            String userId, String accessSessionId, String rawRefreshToken, RequestMetadata metadata) {
        RefreshContext context = lockAndValidate(rawRefreshToken, userId, accessSessionId);
        return rotateLocked(context, metadata);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        store.lockRefreshCredential(opaqueTokens.hash(rawRefreshToken))
                .ifPresent(context -> store.revokeFamily(context.session().tokenFamilyId(), clock.instant()));
    }

    @Transactional
    public void logoutAll(String userId) {
        store.revokeAllUserSessions(userId, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<AuthenticationSession> list(String userId) {
        return store.findUserSessions(userId);
    }

    @Transactional
    public void revoke(String userId, String sessionId) {
        if (!store.revokeOwnedSession(userId, sessionId, clock.instant())) {
            throw new AuthException(AuthErrorCode.SESSION_NOT_FOUND, 404, "Authentication session was not found");
        }
    }

    private RefreshContext lockAndValidate(String rawRefreshToken, String requiredUserId, String requiredSessionId) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw AuthException.unauthorized(AuthErrorCode.AUTHENTICATION_REQUIRED, "Refresh cookie is required");
        }
        RefreshContext context = store.lockRefreshCredential(opaqueTokens.hash(rawRefreshToken))
                .orElseThrow(() -> AuthException.unauthorized(
                        AuthErrorCode.SESSION_NOT_FOUND, "Refresh session was not found"));
        if (context.credential().isConsumed()) {
            store.revokeFamily(context.session().tokenFamilyId(), clock.instant());
            throw new RefreshReuseDetectedException();
        }
        if (requiredUserId != null
                && (!requiredUserId.equals(context.user().id())
                        || !requiredSessionId.equals(context.session().id()))) {
            throw AuthException.forbidden(AuthErrorCode.FORBIDDEN, "Refresh session does not match access token");
        }
        Instant now = clock.instant();
        AuthenticationSession session = context.session();
        if (!context.user().isActive()
                || session.isRevoked()
                || !now.isBefore(session.absoluteExpiresAt())
                || !now.isBefore(session.lastActivityAt().plus(properties.refresh().inactivityTtl()))) {
            store.revokeFamily(session.tokenFamilyId(), now);
            throw AuthException.unauthorized(AuthErrorCode.SESSION_REVOKED, "Refresh session is not active");
        }
        return context;
    }

    private SessionGrant rotateLocked(RefreshContext context, RequestMetadata metadata) {
        Instant now = clock.instant();
        String rawReplacement = opaqueTokens.generate();
        RefreshCredential replacement = new RefreshCredential(
                AuthenticationIds.tokenId(),
                context.session().id(),
                opaqueTokens.hash(rawReplacement),
                now,
                null,
                null,
                null);
        if (!store.replaceRefreshCredential(context.credential().id(), replacement, now)) {
            store.revokeFamily(context.session().tokenFamilyId(), now);
            throw new RefreshReuseDetectedException();
        }
        store.touchSession(
                context.session().id(), now, metadata.deviceName(), metadata.approximateIp());
        return grant(context.user(), context.session().id(), rawReplacement);
    }

    private SessionGrant grant(UserAccount user, String sessionId, String rawRefresh) {
        String jwt = accessTokens.issue(user.id(), sessionId);
        return new SessionGrant(
                jwt,
                accessTokens.expiresInSeconds(),
                rawRefresh,
                sessionId,
                new UserView(user, store.findProviders(user.id())));
    }
}
