package com.shortvideo.worker;

public record ProcessResult(int exitCode, String stdoutTail, String stderrTail, boolean timedOut) {

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
