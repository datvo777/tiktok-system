package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.shortvideo.shared.inbox.InboxGuard;
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
 * A redelivered event, against real PostgreSQL.
 *
 * <p>PostgreSQL aborts the whole transaction when a statement fails: every later statement is
 * refused (SQLState 25P02) and the final COMMIT quietly becomes a ROLLBACK. An inbox that
 * signalled a duplicate by letting the insert fail would therefore hand its caller a transaction
 * that looks healthy and can do nothing more. Counting rows afterwards does not notice that, so
 * the tests that matter assert on the transaction itself.
 *
 * <p>Only {@link #aRefusedClaimLeavesTheTransactionUsableForTheRestOfTheMethod} fails against an
 * inbox that catches the duplicate-key error: with the driver's default settings the commit of an
 * aborted transaction does not throw, so {@link #aRedeliveryIsRefusedAndItsTransactionStillCommits}
 * only guards against that changing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InboxGuardIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired InboxGuard inbox;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;

    /** One claim in its own transaction, committed on return: the commit is part of what is tested. */
    private boolean claimInOwnTransaction(String consumer, UUID eventId) {
        return Boolean.TRUE.equals(transaction.execute(s -> inbox.claim(consumer, eventId)));
    }

    @Test
    void aFirstDeliveryIsClaimed() {
        UUID eventId = UUID.randomUUID();

        assertThat(claimInOwnTransaction("inbox-it", eventId)).isTrue();
    }

    @Test
    void aRedeliveryIsRefusedAndItsTransactionStillCommits() {
        UUID eventId = UUID.randomUUID();
        transaction.executeWithoutResult(s -> inbox.claim("inbox-it", eventId));

        // The listener's shape for a duplicate: claim comes back false, return, let the
        // @Transactional method commit. The commit is what fails if the insert aborted it.
        assertThatCode(() -> {
                    assertThat(claimInOwnTransaction("inbox-it", eventId)).isFalse();
                })
                .doesNotThrowAnyException();
    }

    @Test
    void aRefusedClaimLeavesTheTransactionUsableForTheRestOfTheMethod() {
        UUID eventId = UUID.randomUUID();
        transaction.executeWithoutResult(s -> inbox.claim("inbox-it", eventId));

        Integer afterwards = transaction.execute(s -> {
            assertThat(inbox.claim("inbox-it", eventId)).isFalse();
            // Anything a later listener does after a duplicate: refused with 25P02 if aborted.
            return jdbc.queryForObject("SELECT 1", Integer.class);
        });

        assertThat(afterwards).isEqualTo(1);
    }

    @Test
    void theSameEventIsClaimedOncePerConsumer() {
        UUID eventId = UUID.randomUUID();

        assertThat(claimInOwnTransaction("inbox-it-a", eventId)).isTrue();
        assertThat(claimInOwnTransaction("inbox-it-b", eventId)).isTrue();
        assertThat(claimInOwnTransaction("inbox-it-a", eventId)).isFalse();
    }

    @Test
    void aRolledBackClaimIsNotRemembered() {
        UUID eventId = UUID.randomUUID();

        transaction.executeWithoutResult(s -> {
            inbox.claim("inbox-it", eventId);
            s.setRollbackOnly();
        });

        // The business update did not commit, so the redelivery has to be applied after all.
        assertThat(claimInOwnTransaction("inbox-it", eventId)).isTrue();
    }
}
