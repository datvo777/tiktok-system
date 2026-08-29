package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void capturesStdoutOnSuccess() throws IOException {
        ProcessResult result =
                runner.run(List.of("/bin/sh", "-c", "echo hello"), Duration.ofSeconds(10), null);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdoutTail()).contains("hello");
    }

    @Test
    void reportsNonZeroExit() throws IOException {
        ProcessResult result =
                runner.run(List.of("/bin/sh", "-c", "echo boom >&2; exit 3"), Duration.ofSeconds(10), null);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderrTail()).contains("boom");
    }

    /**
     * The regression that matters. FFmpeg writes progress to stderr continuously;
     * with no drain thread the OS pipe buffer fills, the child blocks forever, and
     * no exit-based timeout ever fires. This floods stderr with far more than one
     * pipe buffer and must still complete well inside the deadline.
     */
    @Test
    @Timeout(60)
    void doesNotDeadlockOnAFloodOfStderr() throws IOException {
        String flood = "i=0; while [ $i -lt 100000 ]; do echo noisy-progress-line >&2; i=$((i+1)); done";

        ProcessResult result = runner.run(List.of("/bin/sh", "-c", flood), Duration.ofSeconds(45), null);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        // Only the tail is retained, so a chatty process cannot grow the heap.
        assertThat(result.stderrTail().length()).isLessThanOrEqualTo(8192);
    }

    @Test
    @Timeout(60)
    void killsAProcessThatOutlivesItsDeadline() throws IOException {
        ProcessResult result =
                runner.run(List.of("/bin/sh", "-c", "sleep 60"), Duration.ofSeconds(2), null);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    @Timeout(60)
    void killsTheWholeProcessTree() throws IOException, InterruptedException {
        // The grandchild would survive a plain destroy() on the direct child.
        ProcessResult result =
                runner.run(List.of("/bin/sh", "-c", "sleep 60 & wait"), Duration.ofSeconds(2), null);

        assertThat(result.timedOut()).isTrue();
        Thread.sleep(500);
    }
}
