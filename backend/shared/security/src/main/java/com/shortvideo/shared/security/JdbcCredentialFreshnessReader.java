package com.shortvideo.shared.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads {@code account.account.password_changed_at} directly by SQL rather than
 * through the account module's JPA repository — this module sits below {@code
 * account} in the dependency graph (the account module depends on security, not
 * the other way around), so the contract is the column, not the entity class.
 */
@Component
public class JdbcCredentialFreshnessReader implements CredentialFreshnessReader {

    private static final String SELECT =
            "SELECT password_changed_at FROM account.account WHERE account_id = ?";

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
                SELECT, (rs, rowNum) -> rs.getTimestamp("password_changed_at").toInstant(), id);
        return rows.stream().findFirst();
    }
}
