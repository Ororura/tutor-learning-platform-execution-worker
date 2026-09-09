package com.tutorplatform.worker.application;

public record SandboxResult(
    Status status,
    long executionTimeMs,
    String stdoutExcerpt,
    String stderrExcerpt,
    boolean stdoutTruncated,
    boolean stderrTruncated
) {
    public enum Status {
        COMPLETED,
        TIMEOUT,
        RUNTIME_ERROR,
        SYSTEM_ERROR
    }
}
