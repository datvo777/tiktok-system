package com.shortvideo.shared.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads when an account's credentials last changed ({@code password_changed_at}, {@code mfa_changed_at}) directly by SQL rather than
 * through the account module's JPA repository — this module sits below {@code
 * account} in the dependency graph (the account module depends on security, not
 * the other way around), so the contract is the column, not the entity class.
 */
@Component
public class JdbcCredentialFreshnessReader implements CredentialFreshnessReader {

    /**
     * The later of the password change and the last change to the second factor: enrolling, replacing or
     * removing it must end the sessions that were signed in before, the same as a new password does.
     */
    private static final String SELECT =
            "SELECT GREATEST(password_changed_at, COALESCE(mfa_changed_at, password_changed_at)) AS changed_at "
                    + "FROM account.account WHERE account_id = ?";

    private final JdbcTemplate jdbc;

    public JdbcCredentialFreshnessReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> passwordChangedAt(String accountId) {
        UUID id;
        try {
            id = UUID.fromString(accountId);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        List<Instant> rows = jdbc.query(
                SELECT, (rs, rowNum) -> rs.getTimestamp("changed_at").toInstant(), id);
        return rows.stream().findFirst();
    }
}
