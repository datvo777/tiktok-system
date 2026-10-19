package com.shortvideo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ReflectionUtils;

/**
 * The reconciliation sweeps run under a scheduler lock, so two instances do not sweep at once and
 * collide on the records the other is creating. Asserted on the real proxy and the real lock table:
 * an annotation that was not picked up would pass any test that calls the method directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReconciliationJobLocksIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    private void assertRunsUnderLock(String bean, String lockName) {
        Object job = context.getBean(bean);
        assertThat(AopUtils.isAopProxy(job)).as("lock advice is applied to %s", bean).isTrue();
        var sweep = ReflectionUtils.findMethod(job.getClass(), "reconcile");
        ReflectionUtils.makeAccessible(sweep);

        ReflectionUtils.invokeMethod(sweep, job);

        // lockAtLeastFor keeps the row locked after a fast sweep, so another instance's sweep is skipped.
        Boolean heldIntoTheFuture = jdbc.queryForObject(
                "SELECT lock_until > (now() AT TIME ZONE 'UTC') FROM upload.shedlock WHERE name = ?",
                Boolean.class,
                lockName);
        assertThat(heldIntoTheFuture).isTrue();
    }

    @Test
    void theUploadCompletionSweepRunsUnderASchedulerLock() {
        assertRunsUnderLock("uploadCompletionReconciliationJob", "uploadCompletionReconciliation");
    }

    @Test
    void theHeldReviewSweepRunsUnderASchedulerLock() {
        assertRunsUnderLock("heldReviewReconciliationJob", "heldReviewReconciliation");
    }
}
