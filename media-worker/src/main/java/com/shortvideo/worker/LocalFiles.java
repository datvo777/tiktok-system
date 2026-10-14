package com.shortvideo.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class LocalFiles {

    private LocalFiles() {}

    /** Best-effort: a file that cannot be removed, or a directory that is already gone, is not an error. */
    static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort local cleanup.
                }
            });
        } catch (IOException ignored) {
            // Directory may already be gone.
        }
    }
}
