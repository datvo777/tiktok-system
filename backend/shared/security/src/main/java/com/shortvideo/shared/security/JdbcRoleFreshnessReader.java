package com.shortvideo.shared.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads {@code account.account.roles} by SQL; same layering reason as {@link JdbcCredentialFreshnessReader}. */
@Component
public class JdbcRoleFreshnessReader implements RoleFreshnessReader {

    private static final String SELECT = "SELECT roles FROM account.account WHERE account_id = ?";

    private final JdbcTemplate jdbc;

    public JdbcRoleFreshnessReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Set<String>> rolesOf(String accountId) {
        UUID id;
        try {
            id = UUID.fromString(accountId);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        List<Set<String>> rows = jdbc.query(SELECT, (rs, rowNum) -> RoleParser.parse(rs.getString("roles")), id);
        return rows.stream().findFirst();
    }
}
