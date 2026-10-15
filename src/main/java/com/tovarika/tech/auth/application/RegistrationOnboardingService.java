package com.tovarika.tech.auth.application;

import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationOnboardingService {
    private final JdbcTemplate jdbc;
    private final UserAccountService users;

    public RegistrationOnboardingService(JdbcTemplate jdbc, UserAccountService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public boolean completed(String userId) {
        users.get(userId);
        return !jdbc.queryForObject("""
                select exists(select 1 from registration_onboarding where user_id=? and completed_at is null)
                """, Boolean.class, userId);
    }

    @Transactional
    public boolean complete(String userId, String role, String frequency, String difficulty) {
        var user = users.get(userId).user();
        if (role == null || !Set.of("marketplace_seller", "aspiring_seller", "client_designer", "exploring_ai").contains(role)
                || frequency == null || !Set.of("more_than_20", "from_6_to_20", "from_1_to_5", "rarely").contains(frequency)
                || difficulty == null || !Set.of("time_consuming", "expensive_designer", "ai_quality", "difficult_edits", "no_problems").contains(difficulty)) {
            throw AuthException.unprocessable("Answer all three onboarding questions using the available options");
        }
        jdbc.update("""
                update registration_onboarding set email=?,role=?,frequency=?,difficulty=?,completed_at=clock_timestamp()
                where user_id=? and completed_at is null
                """, user.email(), role, frequency, difficulty, userId);
        return true;
    }
}
