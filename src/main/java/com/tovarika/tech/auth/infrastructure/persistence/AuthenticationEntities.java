package com.tovarika.tech.auth.infrastructure.persistence;

import com.tovarika.tech.auth.domain.AuthProvider;
import com.tovarika.tech.auth.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
class UserEntity {
    @Id String id;
    @Column(nullable = false, length = 320) String email;
    @Column(name = "email_verified", nullable = false) boolean emailVerified;
    @Column(name = "display_name", length = 100) String displayName;
    @Column(name = "avatar_url", length = 2048) String avatarUrl;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) UserStatus status;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected UserEntity() {}
}

@Entity
@Table(name = "auth_identities")
class AuthIdentityEntity {
    @Id String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") UserEntity user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) AuthProvider provider;
    @Column(name = "provider_user_id", nullable = false, length = 320) String providerUserId;
    @Column(name = "password_hash", length = 512) String passwordHash;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected AuthIdentityEntity() {}
}

@Entity
@Table(name = "auth_sessions")
class AuthSessionEntity {
    @Id String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") UserEntity user;
    @Column(name = "token_family_id", nullable = false, unique = true, length = 40) String tokenFamilyId;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "last_activity_at", nullable = false) Instant lastActivityAt;
    @Column(name = "absolute_expires_at", nullable = false) Instant absoluteExpiresAt;
    @Column(name = "revoked_at") Instant revokedAt;
    @Column(name = "device_name", length = 200) String deviceName;
    @Column(name = "approximate_ip", length = 64) String approximateIp;

    protected AuthSessionEntity() {}
}

@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity {
    @Id String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "session_id") AuthSessionEntity session;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "consumed_at") Instant consumedAt;
    @Column(name = "rotated_at") Instant rotatedAt;
    @Column(name = "replacement_token_id", length = 40) String replacementTokenId;

    protected RefreshTokenEntity() {}
}

@Entity
@Table(name = "email_verification_tokens")
class EmailVerificationTokenEntity {
    @Id String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") UserEntity user;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "consumed_at") Instant consumedAt;

    protected EmailVerificationTokenEntity() {}
}

@Entity
@Table(name = "password_reset_tokens")
class PasswordResetTokenEntity {
    @Id String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "identity_id") AuthIdentityEntity identity;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") UserEntity user;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "consumed_at") Instant consumedAt;

    protected PasswordResetTokenEntity() {}
}

@Entity
@Table(name = "oauth_attempts")
class OAuthAttemptEntity {
    @Id String id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) AuthProvider provider;
    @Column(name = "state_hash", nullable = false, unique = true, length = 64) String stateHash;
    @Column(name = "correlation_hash", nullable = false, unique = true, length = 64) String correlationHash;
    @Column(name = "pkce_verifier", nullable = false, length = 128) String pkceVerifier;
    @Column(name = "return_path", nullable = false, length = 1000) String returnPath;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "consumed_at") Instant consumedAt;

    protected OAuthAttemptEntity() {}
}

@Entity
@Table(name = "trial_sessions")
class TrialSessionEntity {
    @Id String id;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) String tokenHash;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "owner_user_id") UserEntity owner;
    @Column(name = "converted_at") Instant convertedAt;

    protected TrialSessionEntity() {}
}
