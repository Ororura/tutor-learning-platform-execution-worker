package com.tutorplatform.worker.application;

import java.util.List;
import java.util.UUID;

public record ExecutionCommand(
    UUID executionId,
    ExecutionLanguage language,
    String sourceCode,
    int timeLimitMs,
    int memoryLimitMb,
    int outputLimitBytes,
    List<TestCase> testCases
) {
    public record TestCase(
        UUID id,
        String inputText,
        String expectedOutput,
        ComparisonMode comparisonMode
    ) {
    }
}
