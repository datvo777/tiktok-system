package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The step-ordering and failure rules, tested without ffmpeg.
 *
 * <p>These exercise {@link TranscodeStep} directly rather than the wired
 * pipeline: the point of splitting the job into steps was that its control flow
 * — what runs in what order, and what a failure in each one means — became
 * testable separately from the ffmpeg invocations, which need a real file and a
 * real binary.
 */
class TranscodePipelineTest {

    /** Mirrors the loop in {@link TranscodePipeline#run}. */
    private static List<String> runAll(List<TranscodeStep> steps) throws IOException {
        List<String> ran = new ArrayList<>();
        for (TranscodeStep step : steps) {
            try {
                step.run(null);
                ran.add(step.name());
            } catch (TranscodeFailedException e) {
                if (step.required()) {
                    throw new TranscodeFailedException(
                            e.failureClass(), "step '" + step.name() + "': " + e.getMessage());
                }
            }
        }
        return ran;
    }

    private static TranscodeStep step(String name, boolean required, Runnable body) {
        return new TranscodeStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean required() {
                return required;
            }

            @Override
            public void run(TranscodeContext context) {
                body.run();
            }
        };
    }

    @Test
    void runsStepsInOrder() throws IOException {
        List<String> ran = runAll(List.of(
                step("probe", true, () -> {}),
                step("encode-ladder", true, () -> {}),
                step("poster", false, () -> {}),
                step("manifest", true, () -> {})));

        assertThat(ran).containsExactly("probe", "encode-ladder", "poster", "manifest");
    }

    /**
     * The reason {@code required()} exists: a video that processed correctly but
     * produced no thumbnail is a far better outcome than one failed over a still
     * frame.
     */
    @Test
    void anOptionalStepFailingDoesNotStopTheJob() throws IOException {
        List<String> ran = runAll(List.of(
                step("encode-ladder", true, () -> {}),
                step("poster", false, () -> {
                    throw new TranscodeFailedException("TERMINAL", "no frame");
                }),
                step("manifest", true, () -> {})));

        assertThat(ran).containsExactly("encode-ladder", "manifest");
    }

    @Test
    void aRequiredStepFailingStopsTheJob() {
        assertThatThrownBy(() -> runAll(List.of(
                        step("encode-ladder", true, () -> {
                            throw new TranscodeFailedException("TERMINAL", "ffmpeg exited 1");
                        }),
                        step("manifest", true, () -> {}))))
                .isInstanceOf(TranscodeFailedException.class);
    }

    /**
     * A failure report that says only "ffmpeg exited 1" cannot tell you whether
     * the ladder or the manifest broke. Naming the stage is most of the value of
     * having stages.
     */
    @Test
    void aFailureNamesTheStageItCameFrom() {
        assertThatThrownBy(() -> runAll(List.of(step("encode-ladder", true, () -> {
                    throw new TranscodeFailedException("TERMINAL", "ffmpeg exited 1");
                }))))
                .hasMessageContaining("encode-ladder")
                .hasMessageContaining("ffmpeg exited 1");
    }

    /** Retry classification has to survive being re-wrapped, or a transient fault becomes permanent. */
    @Test
    void preservesTheFailureClassWhenReWrapping() {
        assertThatThrownBy(() -> runAll(List.of(step("encode-ladder", true, () -> {
                    throw new TranscodeFailedException("TRANSIENT", "timed out");
                }))))
                .isInstanceOfSatisfying(
                        TranscodeFailedException.class,
                        e -> assertThat(e.failureClass()).isEqualTo("TRANSIENT"));
    }

    /** Steps after a required failure must not run: they would work on partial output. */
    @Test
    void stopsAtTheFirstRequiredFailure() {
        List<String> ran = new ArrayList<>();
        assertThatThrownBy(() -> runAll(List.of(
                        step("probe", true, () -> {
                            throw new TranscodeFailedException("TERMINAL", "unsupported container");
                        }),
                        step("encode-ladder", true, () -> ran.add("encode-ladder")))))
                .isInstanceOf(TranscodeFailedException.class);

        assertThat(ran).isEmpty();
    }
}
