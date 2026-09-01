package com.tovarika.tech.auth.infrastructure.persistence;

import com.tovarika.tech.auth.domain.AuthProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface UserJpaRepository extends JpaRepository<UserEntity, String> {}

interface AuthIdentityJpaRepository extends JpaRepository<AuthIdentityEntity, String> {
    @Query("select i from AuthIdentityEntity i join fetch i.user where i.provider = :provider and i.providerUserId = :providerUserId")
    Optional<AuthIdentityEntity> findWithUser(
            @Param("provider") AuthProvider provider, @Param("providerUserId") String providerUserId);

    @Query("select i.provider from AuthIdentityEntity i where i.user.id = :userId")
    List<AuthProvider> findProviders(@Param("userId") String userId);
}

interface AuthSessionJpaRepository extends JpaRepository<AuthSessionEntity, String> {
    @Modifying
    @Query("update AuthSessionEntity s set s.lastActivityAt = :now, s.deviceName = coalesce(:deviceName, s.deviceName), s.approximateIp = coalesce(:approximateIp, s.approximateIp) where s.id = :sessionId")
    int touch(
            @Param("sessionId") String sessionId,
            @Param("now") Instant now,
            @Param("deviceName") String deviceName,
            @Param("approximateIp") String approximateIp);

    @Modifying
    @Query("update AuthSessionEntity s set s.revokedAt = :now where s.tokenFamilyId = :familyId and s.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSessionEntity s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null")
    int revokeAll(@Param("userId") String userId, @Param("now") Instant now);

    @Modifying
    @Query("update AuthSessionEntity s set s.revokedAt = :now where s.user.id = :userId and s.id <> :retainedSessionId and s.revokedAt is null")
    int revokeAllExcept(
            @Param("userId") String userId,
            @Param("retainedSessionId") String retainedSessionId,
            @Param("now") Instant now);

    @Query("select count(s) > 0 from AuthSessionEntity s where s.id = :sessionId and s.user.id = :userId and s.user.status = com.tovarika.tech.auth.domain.UserStatus.ACTIVE and s.revokedAt is null and s.absoluteExpiresAt > :now and s.lastActivityAt > :inactivityCutoff")
    boolean isActive(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("now") Instant now,
            @Param("inactivityCutoff") Instant inactivityCutoff);

    @Query("select s from AuthSessionEntity s where s.user.id = :userId order by s.createdAt desc")
    List<AuthSessionEntity> findAllByUser(@Param("userId") String userId);

    @Modifying
    @Query("update AuthSessionEntity s set s.revokedAt = coalesce(s.revokedAt, :now) where s.user.id = :userId and s.id = :sessionId")
    int revokeOwned(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("now") Instant now);
}

interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshTokenEntity t join fetch t.session s join fetch s.user where t.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> lockByHash(@Param("tokenHash") String tokenHash);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshTokenEntity t set t.consumedAt = :now, t.rotatedAt = :now, t.replacementTokenId = :replacementId where t.id = :oldId and t.consumedAt is null")
    int consume(
            @Param("oldId") String oldId,
            @Param("replacementId") String replacementId,
            @Param("now") Instant now);
}

interface PasswordResetTokenJpaRepository extends JpaRepository<PasswordResetTokenEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetTokenEntity t join fetch t.identity join fetch t.user where t.tokenHash = :tokenHash")
    Optional<PasswordResetTokenEntity> lockByHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("update PasswordResetTokenEntity t set t.consumedAt = :now where t.identity.id = :identityId and t.consumedAt is null")
    int revokeActive(@Param("identityId") String identityId, @Param("now") Instant now);

    @Modifying
    @Query("update PasswordResetTokenEntity t set t.consumedAt = :now where t.id = :tokenId and t.consumedAt is null")
    int consume(@Param("tokenId") String tokenId, @Param("now") Instant now);
}

interface OAuthAttemptJpaRepository extends JpaRepository<OAuthAttemptEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthAttemptEntity> findByCorrelationHash(String correlationHash);

    @Modifying
    @Query("update OAuthAttemptEntity a set a.consumedAt = :now where a.id = :attemptId and a.consumedAt is null")
    int consume(@Param("attemptId") String attemptId, @Param("now") Instant now);
}

interface TrialSessionJpaRepository extends JpaRepository<TrialSessionEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TrialSessionEntity> findByTokenHash(String tokenHash);
}
