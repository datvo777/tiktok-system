package com.shortvideo.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded subprocess execution for FFmpeg and FFprobe (brief section 14.1, Rule 20).
 *
 * <p>Three things go wrong with naive {@code Process} use, and all three are
 * handled here:
 *
 * <ol>
 *   <li><b>Undrained pipes.</b> FFmpeg writes progress to stderr continuously. If
 *       nothing reads it, the OS pipe buffer fills and FFmpeg blocks forever — the
 *       job hangs and a timeout based on its exit never fires. Both streams are
 *       drained on dedicated threads for the whole lifetime of the process.
 *   <li><b>Unbounded waits.</b> {@code waitFor()} with no timeout is how a stuck
 *       transcode becomes a stuck consumer. Every run has a deadline.
 *   <li><b>Surviving children.</b> {@code destroy()} on the direct child leaves
 *       helpers running, so the whole process tree is destroyed.
 * </ol>
 *
 * <p>A JVM shutdown hook kills anything still running. Shutdown hooks do not run
 * on SIGKILL, which is why the worker also sweeps orphaned temp prefixes at
 * startup — an interrupted job leaves no half-published output because the
 * processed/ prefix is only written after validation.
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    /** Keep the tail only: FFmpeg can emit megabytes of progress lines. */
    private static final int TAIL_LIMIT = 8192;

    private final Set<Process> running = ConcurrentHashMap.newKeySet();

    public ProcessRunner() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroyAll, "process-runner-shutdown"));
    }

    public ProcessResult run(List<String> command, Duration timeout, Path workingDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(false);

        Process process = builder.start();
        running.add(process);

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outPump = pump(process.getInputStream(), stdout, "stdout");
        Thread errPump = pump(process.getErrorStream(), stderr, "stderr");

        try {
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                log.warn("Process exceeded {} and will be killed: {}", timeout, command.get(0));
                destroyTree(process);
                joinQuietly(outPump);
                joinQuietly(errPump);
                return new ProcessResult(-1, tail(stdout), tail(stderr), true);
            }
            joinQuietly(outPump);
            joinQuietly(errPump);
            return new ProcessResult(process.exitValue(), tail(stdout), tail(stderr), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            return new ProcessResult(-1, tail(stdout), tail(stderr), true);
        } finally {
            running.remove(process);
        }
    }

    private Thread pump(InputStream stream, StringBuilder sink, String name) {
        Thread thread = new Thread(
                () -> {
                    char[] buffer = new char[4096];
                    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        int read;
                        while ((read = reader.read(buffer)) != -1) {
                            synchronized (sink) {
                                sink.append(buffer, 0, read);
                                // Trim as we go so a chatty process cannot grow the heap.
                                if (sink.length() > TAIL_LIMIT * 2) {
                                    sink.delete(0, sink.length() - TAIL_LIMIT);
                                }
                            }
                        }
                    } catch (IOException e) {
                        log.debug("Stream pump {} closed: {}", name, e.getMessage());
                    }
                },
                "process-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void destroyAll() {
        for (Process process : running) {
            log.warn("Destroying process still running at shutdown");
            destroyTree(process);
        }
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String tail(StringBuilder sink) {
        synchronized (sink) {
            int length = sink.length();
            return length <= TAIL_LIMIT ? sink.toString() : sink.substring(length - TAIL_LIMIT);
        }
    }
}
