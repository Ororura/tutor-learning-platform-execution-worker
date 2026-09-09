package com.tutorplatform.worker.application;

import java.util.UUID;

public record SandboxRequest(
    UUID executionId,
    ExecutionLanguage language,
    String sourceCode,
    String stdin,
    int timeLimitMs,
    int memoryLimitMb,
    int outputLimitBytes
) {
}
