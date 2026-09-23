package com.tovarika.tech.trial.infrastructure;

import com.tovarika.tech.trial.application.TrialSessionStore;
import com.tovarika.tech.trial.domain.TrialSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTrialSessionStore implements TrialSessionStore {
    private final JdbcTemplate jdbc;

    public JdbcTrialSessionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void create(TrialSession session, String tokenHash) {
        jdbc.update("""
                insert into trial_sessions(id, token_hash, created_at, expires_at, generation_limit, generations_used)
                values (?, ?, ?, ?, ?, ?)
                """, session.id(), tokenHash, Timestamp.from(session.createdAt()), Timestamp.from(session.expiresAt()),
                session.generationLimit(), session.generationsUsed());
    }

    @Override
    public Optional<TrialSession> findAvailable(String tokenHash, Instant now) {
        return jdbc.query("""
                select id, generation_limit, generations_used, created_at, expires_at from trial_sessions
                where token_hash = ? and owner_user_id is null and converted_at is null and expires_at > ?
                """, (row, index) -> new TrialSession(row.getString("id"), row.getInt("generation_limit"),
                row.getInt("generations_used"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("expires_at").toInstant()), tokenHash, Timestamp.from(now)).stream().findFirst();
    }
}
