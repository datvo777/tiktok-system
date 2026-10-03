package com.shortvideo.shared.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the admin audit log. Both queries are index-covered and newest
 * first, matching the two questions anyone actually asks of an audit trail:
 * "what happened to this thing" and "what has this reviewer been doing".
 */
@Component
public class AdminActionReader {

    private static final String BY_TARGET = """
            SELECT action_id, actor_account_id, action, target_type, target_id,
                   policy_category, reason, correlation_id, occurred_at
            FROM platform.admin_action
            WHERE target_type = ? AND target_id = ?
            ORDER BY occurred_at DESC
            LIMIT ?
            """;

    private static final String BY_ACTOR = """
            SELECT action_id, actor_account_id, action, target_type, target_id,
                   policy_category, reason, correlation_id, occurred_at
            FROM platform.admin_action
            WHERE actor_account_id = ?
            ORDER BY occurred_at DESC
            LIMIT ?
            """;

    private static final RowMapper<AdminActionEntry> MAPPER = (rs, rowNum) -> new AdminActionEntry(
            rs.getString("action_id"),
            rs.getString("actor_account_id"),
            rs.getString("action"),
            rs.getString("target_type"),
            rs.getString("target_id"),
            rs.getString("policy_category"),
            rs.getString("reason"),
            rs.getString("correlation_id"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;

    public AdminActionReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AdminActionEntry> forTarget(String targetType, String targetId, int limit) {
        return jdbc.query(BY_TARGET, MAPPER, targetType, targetId, limit);
    }

    @Transactional(readOnly = true)
    public List<AdminActionEntry> byActor(String actorAccountId, int limit) {
        return jdbc.query(BY_ACTOR, MAPPER, UUID.fromString(actorAccountId), limit);
    }
}
