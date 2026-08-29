package com.shortvideo.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The FFmpeg media worker (brief section 11.1).
 *
 * <p>Deliberately has no database. It cannot join a transaction and therefore
 * cannot own an outbox, so it emits results as commands and never as
 * authoritative absolute-state events (Rule 16). The Video module consumes
 * media.results.v1 through its inbox and owns the READY transition.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
