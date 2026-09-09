package com.tutorplatform.worker.application;

import java.util.List;
import java.util.UUID;

public record ExecutionOutcome(
    UUID executionId,
    ExecutionStatus status,
    int passedTests,
    int totalTests,
    long executionTimeMs,
    String stdoutExcerpt,
    String stderrExcerpt,
    List<TestResult> testResults
) {
    public record TestResult(
        UUID testCaseId,
        boolean passed,
        long executionTimeMs,
        String stdoutExcerpt,
        String stderrExcerpt
    ) {
    }
}
