package com.shortvideo.shared.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records an administrative act (brief section 18).
 *
 * <p>Deliberately not asynchronous and not best-effort. Called from inside the
 * caller's {@code @Transactional} method, it joins that transaction, so the
 * audit row and the state change it describes commit or roll back together.
 * An audit log that can disagree with the thing it audits is worse than none:
 * it is the record you reach for precisely when something went wrong, which is
 * exactly when a fire-and-forget write is most likely to have been the thing
 * that failed.
 *
 * <p>The cost is that a failure here fails the admin action. That is the right
 * trade for a moderation console — an unattributable enforcement decision is
 * not an outcome worth keeping.
 */
@Component
public class AdminActionRecorder {

    private static final String INSERT = """
            INSERT INTO platform.admin_action
                (action_id, actor_account_id, action, target_type, target_id,
                 policy_category, reason, correlation_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** Matches the column; a longer note is stored truncated rather than failing the decision. */
    private static final int REASON_LIMIT = 500;

    private final JdbcTemplate jdbc;

    public AdminActionRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(AdminAction action) {
        jdbc.update(
                INSERT,
                UUID.randomUUID(),
                UUID.fromString(action.actorAccountId()),
                action.action(),
                action.targetType(),
                action.targetId(),
                action.policyCategory(),
                truncate(action.reason()),
                MDC.get("correlationId"),
                Timestamp.from(Instant.now()));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= REASON_LIMIT ? reason : reason.substring(0, REASON_LIMIT);
    }
}
