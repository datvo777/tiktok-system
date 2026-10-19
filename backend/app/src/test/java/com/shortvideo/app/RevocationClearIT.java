package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shortvideo.shared.revocation.DurableRevocationWriter;
import com.shortvideo.shared.revocation.RevocationClearCommand;
import com.shortvideo.shared.revocation.RevocationCommand;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Clearing a revocation against real PostgreSQL. The clear is guarded by the version that set the
 * block, so a caller that is wrong about it must find out: otherwise its own record says the subject
 * is reinstated while the table the media gateway reads still says blocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RevocationClearIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired DurableRevocationWriter writer;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;

    private final String subject = UUID.randomUUID().toString();

    private void block(long version) {
        transaction.executeWithoutResult(s -> writer.activate(
                new RevocationCommand("VIDEO", subject, "MODERATION", version, "SPAM_DECEPTIVE")));
    }

    private void clear(long expectedVersion) {
        transaction.executeWithoutResult(
                s -> writer.clear(new RevocationClearCommand("VIDEO", subject, "MODERATION", expectedVersion)));
    }

    private boolean active() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM platform.revocation WHERE subject_type = 'VIDEO' AND subject_id = ?"
                        + " AND source_type = 'MODERATION'",
                Boolean.class,
                subject));
    }

    @Test
    void clearingTheBlockThatWasSetClearsIt() {
        block(5);

        clear(5);

        assertThat(active()).isFalse();
    }

    @Test
    void clearingAtTheWrongVersionIsRefusedAndTheBlockStays() {
        block(5);

        assertThatThrownBy(() -> clear(4)).isInstanceOf(IllegalStateException.class).hasMessageContaining("version 4");

        assertThat(active()).isTrue();
    }

    @Test
    void clearingWhatIsAlreadyClearedIsNotAnError() {
        block(5);
        clear(5);

        assertThatCode(() -> clear(5)).doesNotThrowAnyException();
        assertThat(active()).isFalse();
    }

    @Test
    void clearingASubjectThatWasNeverBlockedIsNotAnError() {
        assertThatCode(() -> clear(1)).doesNotThrowAnyException();
    }
}
