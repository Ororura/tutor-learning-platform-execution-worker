package com.tutorplatform.worker.infrastructure.docker;

record DockerCommandResult(
    int exitCode,
    boolean timedOut,
    long durationMs,
    String stdout,
    String stderr,
    boolean stdoutTruncated,
    boolean stderrTruncated
) {
}
