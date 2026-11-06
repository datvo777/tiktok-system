package com.shortvideo.worker;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps what a job leaves behind when it never reached its own cleanup (brief section 14.1): a
 * worker killed mid-job leaves objects under {@code processing-temp/} and a {@code media-worker-*}
 * directory in the temp dir. Shutdown hooks do not run on SIGKILL, so something else has to.
 *
 * <p>It runs shortly after startup and then periodically, because a worker can run for weeks and
 * a job can be killed at any point in that time. A staging prefix in MinIO is also expired by the
 * bucket's lifecycle rule, so that side has a second net; the local disk has none.
 *
 * <p>Only what is older than any job can be is removed. A job's staging and its directory are
 * written within the job's own budget, so anything older than the job timeout (twice that, for the
 * directory, which is created before the download and outlives the transcode) belongs to nobody;
 * anything newer may be a job this or another worker is running right now.
 */
@Component
class TempSweeper {

    private static final Logger log = LoggerFactory.getLogger(TempSweeper.class);
    private static final String PREFIX = "processing-temp/";
    static final String LOCAL_DIR_PREFIX = "media-worker-";

    private final MinioObjectStore store;
    private final WorkerProperties properties;
    private final Path tempDir;

    TempSweeper(MinioObjectStore store, WorkerProperties properties) {
        this(store, properties, Path.of(System.getProperty("java.io.tmpdir")));
    }

    TempSweeper(MinioObjectStore store, WorkerProperties properties, Path tempDir) {
        this.store = store;
        this.properties = properties;
        this.tempDir = tempDir;
    }

    @Scheduled(
            initialDelayString = "${shortvideo.worker.sweep-initial-delay:10s}",
            fixedDelayString = "${shortvideo.worker.sweep-interval:1h}")
    public void sweep() {
        // Independent: the local disk is swept even when MinIO is down, which is when it matters most.
        sweepStaging();
        sweepLocal();
    }

    private void sweepStaging() {
        try {
            var keys = store.listKeysUnder(PREFIX, Instant.now().minus(properties.getJobTimeout()));
            int failed = store.deleteAll(keys);
            if (!keys.isEmpty()) {
                log.info("Swept {} orphaned processing-temp object(s); {} could not be removed",
                        keys.size() - failed, failed);
            }
        } catch (Exception e) {
            log.warn("processing-temp sweep failed; continuing", e);
        }
    }

    private void sweepLocal() {
        Instant cutoff = Instant.now().minus(properties.getJobTimeout().multipliedBy(2));
        int swept = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(tempDir, LOCAL_DIR_PREFIX + "*")) {
            for (Path dir : dirs) {
                if (Files.isDirectory(dir) && Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) {
                    LocalFiles.deleteRecursively(dir);
                    swept++;
                }
            }
        } catch (IOException e) {
            log.warn("Local temp sweep of {} failed; continuing: {}", tempDir, e.getMessage());
        }
        if (swept > 0) {
            log.info("Swept {} orphaned local job director{}", swept, swept == 1 ? "y" : "ies");
        }
    }
}
