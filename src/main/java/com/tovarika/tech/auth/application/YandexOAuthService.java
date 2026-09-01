package com.tovarika.tech.auth.application;

import com.tovarika.tech.auth.application.port.AuthenticationStore;
import com.tovarika.tech.auth.application.port.OpaqueTokenService;
import com.tovarika.tech.auth.application.port.YandexOAuthClient;
import com.tovarika.tech.auth.domain.AuthIdentity;
import com.tovarika.tech.auth.domain.AuthProvider;
import com.tovarika.tech.auth.domain.OAuthAttempt;
import com.tovarika.tech.auth.domain.UserAccount;
import com.tovarika.tech.auth.domain.UserStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class YandexOAuthService {

    private final AuthenticationStore store;
    private final OpaqueTokenService opaqueTokens;
    private final YandexOAuthClient yandexClient;
    private final SessionService sessions;
    private final AuthenticationProperties properties;
    private final Clock clock;

    public YandexOAuthService(
            AuthenticationStore store,
            OpaqueTokenService opaqueTokens,
            YandexOAuthClient yandexClient,
            SessionService sessions,
            AuthenticationProperties properties,
            Clock clock) {
        this.store = store;
        this.opaqueTokens = opaqueTokens;
        this.yandexClient = yandexClient;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public OAuthStart start(String requestedReturnPath) {
        AuthenticationProperties.OAuth.Yandex yandex = properties.oauth().yandex();
        requireConfigured(yandex);
        String returnPath = validateReturnPath(requestedReturnPath == null ? "/editor" : requestedReturnPath);
        String rawState = opaqueTokens.generate();
        String rawCorrelation = opaqueTokens.generate();
        String pkceVerifier = opaqueTokens.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(yandex.attemptTtl());
        store.createOAuthAttempt(new OAuthAttempt(
                AuthenticationIds.oauthAttemptId(),
                AuthProvider.YANDEX,
                opaqueTokens.hash(rawState),
                opaqueTokens.hash(rawCorrelation),
                pkceVerifier,
                returnPath,
                now,
                expiresAt,
                null));
        URI authorizationUrl = UriComponentsBuilder.fromUri(yandex.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", yandex.clientId())
                .queryParam("redirect_uri", yandex.redirectUri())
                .queryParam("scope", "login:email login:info")
                .queryParam("state", rawState)
                .queryParam("code_challenge", codeChallenge(pkceVerifier))
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUri();
        return new OAuthStart(authorizationUrl, expiresAt, rawCorrelation);
    }

    @Transactional
    public OAuthCompletion complete(
            String rawCorrelation,
            String returnedState,
            String code,
            String providerError,
            String rawTrialToken,
            RequestMetadata metadata) {
        if (rawCorrelation == null || returnedState == null) {
            throw AuthException.unauthorized(AuthErrorCode.OAUTH_STATE_INVALID, "OAuth state is invalid");
        }
        OAuthAttempt attempt = store.lockOAuthAttempt(opaqueTokens.hash(rawCorrelation))
                .orElseThrow(() -> AuthException.unauthorized(
                        AuthErrorCode.OAUTH_STATE_INVALID, "OAuth state is invalid"));
        Instant now = clock.instant();
        if (!attempt.isUsableAt(now)
                || !constantTimeEquals(attempt.stateHash(), opaqueTokens.hash(returnedState))) {
            throw AuthException.unauthorized(AuthErrorCode.OAUTH_STATE_INVALID, "OAuth state is invalid");
        }
        if (providerError != null || code == null || code.isBlank()) {
            throw AuthException.badRequest(AuthErrorCode.OAUTH_PROVIDER_ERROR, "Yandex authorization failed");
        }
        YandexOAuthClient.YandexProfile profile = yandexClient.exchangeCode(code, attempt.pkceVerifier());
        if (!validProviderProfile(profile)) {
            throw AuthException.badRequest(AuthErrorCode.OAUTH_PROVIDER_ERROR, "Yandex profile is incomplete");
        }
        if (!store.consumeOAuthAttempt(attempt.id(), now)) {
            throw AuthException.unauthorized(AuthErrorCode.OAUTH_STATE_INVALID, "OAuth attempt was already used");
        }
        UserAccount user = store.findIdentity(AuthProvider.YANDEX, profile.providerUserId())
                .flatMap(identity -> store.findUserById(identity.userId()))
                .orElseGet(() -> createYandexUser(profile, now));
        if (!user.isActive()) {
            throw AuthException.forbidden(AuthErrorCode.FORBIDDEN, "User is not active");
        }
        if (rawTrialToken != null && !rawTrialToken.isBlank()) {
            store.convertTrial(opaqueTokens.hash(rawTrialToken), user.id(), now);
        }
        return new OAuthCompletion(attempt.returnPath(), sessions.create(user, metadata));
    }

    String validateReturnPath(String returnPath) {
        if (!returnPath.matches("^/(?!/)[A-Za-z0-9/_?&=.%~-]*$")) {
            throw AuthException.badRequest(AuthErrorCode.VALIDATION_ERROR, "OAuth returnPath is invalid");
        }
        boolean allowed = properties.oauth().yandex().allowedReturnPathPrefixes().stream()
                .anyMatch(prefix -> returnPath.equals(prefix)
                        || returnPath.startsWith(prefix + "/")
                        || returnPath.startsWith(prefix + "?"));
        if (!allowed) {
            throw AuthException.badRequest(AuthErrorCode.VALIDATION_ERROR, "OAuth returnPath is not allowed");
        }
        return returnPath;
    }

    private UserAccount createYandexUser(YandexOAuthClient.YandexProfile profile, Instant now) {
        String email = EmailNormalizer.normalize(profile.email());
        UserAccount user = new UserAccount(
                AuthenticationIds.userId(),
                email,
                true,
                profile.displayName() == null ? null : profile.displayName().strip(),
                null,
                UserStatus.ACTIVE,
                now,
                now);
        AuthIdentity identity = new AuthIdentity(
                AuthenticationIds.identityId(),
                user.id(),
                AuthProvider.YANDEX,
                profile.providerUserId(),
                null,
                now,
                now);
        // Deliberately no email-based lookup/linking: equal email strings are not proof of account ownership.
        store.createUserWithIdentity(user, identity);
        return user;
    }

    private String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private void requireConfigured(AuthenticationProperties.OAuth.Yandex yandex) {
        if (yandex.clientId() == null
                || yandex.clientId().isBlank()
                || yandex.clientSecret() == null
                || yandex.clientSecret().isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ERROR, 500, "Yandex OAuth is not configured");
        }
    }

    private boolean validProviderProfile(YandexOAuthClient.YandexProfile profile) {
        if (profile.providerUserId() == null || profile.providerUserId().isBlank() || profile.email() == null) {
            return false;
        }
        String email = EmailNormalizer.normalize(profile.email());
        if (email.length() > 320 || !email.matches("^[^\\s@]+@[^\\s@]+$")) {
            return false;
        }
        if (profile.displayName() != null) {
            String displayName = profile.displayName().strip();
            if (displayName.isEmpty() || displayName.codePointCount(0, displayName.length()) > 100) {
                return false;
            }
        }
        return true;
    }
}
