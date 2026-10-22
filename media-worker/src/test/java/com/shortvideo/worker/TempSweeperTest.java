package com.shortvideo.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TempSweeperTest {

    private final MinioObjectStore store = mock(MinioObjectStore.class);
    private final WorkerProperties properties = new WorkerProperties(); // job timeout 15 minutes

    private static Path directory(Path tempDir, String name, Instant modified) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(dir.resolve("source"), "bytes");
        Files.setLastModifiedTime(dir, FileTime.from(modified));
        return dir;
    }

    @Test
    void anOrphanedLocalDirectoryIsRemovedAndAFreshOneIsKept(@TempDir Path tmp) throws IOException {
        Path orphan = directory(tmp, "media-worker-v1-4-abc", Instant.now().minusSeconds(3 * 3600));
        Path running = directory(tmp, "media-worker-v2-1-def", Instant.now().minusSeconds(60));

        new TempSweeper(store, properties, tmp).sweep();

        assertThat(orphan).doesNotExist();
        assertThat(running).exists();
    }

    @Test
    void aDirectoryOlderThanOneJobButYoungerThanTwoIsKept(@TempDir Path tmp) throws IOException {
        // The directory is created before the download, and the job's own budget starts after it.
        Path slow = directory(tmp, "media-worker-v1-4-abc", Instant.now().minusSeconds(20 * 60));

        new TempSweeper(store, properties, tmp).sweep();

        assertThat(slow).exists();
    }

    @Test
    void onlyDirectoriesTheWorkerCreatedAreTouched(@TempDir Path tmp) throws IOException {
        Path other = directory(tmp, "somebody-elses-dir", Instant.now().minusSeconds(3 * 3600));
        Path file = Files.writeString(tmp.resolve("media-worker-notes.txt"), "x");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3 * 3600)));

        new TempSweeper(store, properties, tmp).sweep();

        assertThat(other).exists();
        assertThat(file).exists();
    }

    @Test
    void theLocalDiskIsSweptEvenWhenMinioCannotBeReached(@TempDir Path tmp) throws IOException {
        Path orphan = directory(tmp, "media-worker-v1-4-abc", Instant.now().minusSeconds(3 * 3600));
        when(store.listKeysUnder(any(), any())).thenThrow(new IllegalStateException("MinIO down"));

        new TempSweeper(store, properties, tmp).sweep();

        assertThat(orphan).doesNotExist();
    }

    @Test
    void oldStagingObjectsAreDeletedInOneBatch(@TempDir Path tmp) {
        List<String> keys = List.of("processing-temp/j1/a.ts", "processing-temp/j1/b.ts");
        when(store.listKeysUnder(any(), any())).thenReturn(keys);

        new TempSweeper(store, properties, tmp).sweep();

        verify(store).deleteAll(keys);
    }
}
