package com.shortvideo.shared.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The durable half of the session deny-list: PostgreSQL is authoritative, Redis only answers faster.
 * Same split as {@code platform.revocation} and its cache, and for the same reason (brief section 16):
 * a Redis that restarts, is flushed or is unreachable must not be able to reinstate a signed-out token.
 */
@Component
public class RevokedSessionStore {

    private final JdbcTemplate jdbc;

    public RevokedSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the token as revoked until {@code expiresAt}.
     *
     * @return true if this call revoked it, false if it already was. The single statement is the
     *     atomic claim a refresh needs: of two concurrent holders of the same token, one gets true.
     */
    public boolean insertIfAbsent(String tokenId, Instant expiresAt) {
        return jdbc.update(
                        "INSERT INTO platform.revoked_session (token_id, expires_at) VALUES (?, ?) "
                                + "ON CONFLICT (token_id) DO NOTHING",
                        tokenId,
                        Timestamp.from(expiresAt))
                > 0;
    }

    public boolean isRevoked(String tokenId) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM platform.revoked_session WHERE token_id = ? AND expires_at > now())",
                Boolean.class,
                tokenId);
        return Boolean.TRUE.equals(found);
    }

    /** Unexpired revocations after {@code afterTokenId} (null for the first page), in token order. */
    public List<Row> findActivePage(String afterTokenId, int limit) {
        return jdbc.query(
                "SELECT token_id, expires_at FROM platform.revoked_session "
                        + "WHERE expires_at > now() AND token_id > ? ORDER BY token_id LIMIT ?",
                (rs, n) -> new Row(rs.getString("token_id"), rs.getTimestamp("expires_at").toInstant()),
                afterTokenId == null ? "" : afterTokenId,
                limit);
    }

    /** Drops revocations whose token has expired; they can no longer matter. */
    public int deleteExpired() {
        return jdbc.update("DELETE FROM platform.revoked_session WHERE expires_at <= now()");
    }

    public record Row(String tokenId, Instant expiresAt) {}
}
