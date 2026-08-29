package com.shortvideo.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortvideo.shared.events.EventEnvelope;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class JdbcOutboxWriter implements OutboxWriter {

    private static final String INSERT = """
            INSERT INTO platform.outbox_event (
                event_id, aggregate_type, aggregate_id, event_type, schema_version,
                aggregate_version, payload, occurred_at, available_at, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'PENDING')
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcOutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(EventEnvelope<?> envelope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxWriter.append must run inside the transaction that writes the "
                            + "authoritative state (brief section 10)");
        }
        if (!envelope.isAuthoritative()) {
            throw new IllegalArgumentException(
                    "Outbox events must carry an aggregateVersion. A stateless producer "
                            + "emits commands/results directly, not outbox events (Rule 16)");
        }
        Timestamp occurred = Timestamp.from(envelope.occurredAt());
        jdbc.update(
                INSERT,
                envelope.eventId(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.eventType(),
                envelope.schemaVersion(),
                envelope.aggregateVersion(),
                serialize(envelope),
                occurred,
                occurred);
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload is not serialisable: " + envelope.eventType(), e);
        }
    }
}
