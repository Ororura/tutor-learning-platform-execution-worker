package com.tutorplatform.worker.api;

import com.tutorplatform.worker.application.ExecutionOutcome;

import java.util.List;
import java.util.UUID;

record WorkerExecutionResponse(
    UUID executionId,
    Status status,
    int passedTests,
    int totalTests,
    long executionTimeMs,
    String stdoutExcerpt,
    String stderrExcerpt,
    List<TestResult> testResults
) {
    static WorkerExecutionResponse from(ExecutionOutcome outcome) {
        return new WorkerExecutionResponse(
            outcome.executionId(),
            Status.valueOf(outcome.status().name()),
            outcome.passedTests(),
            outcome.totalTests(),
            outcome.executionTimeMs(),
            outcome.stdoutExcerpt(),
            outcome.stderrExcerpt(),
            outcome.testResults().stream().map(result -> new TestResult(
                result.testCaseId(),
                result.passed(),
                result.executionTimeMs(),
                result.stdoutExcerpt(),
                result.stderrExcerpt()
            )).toList()
        );
    }

    enum Status {
        PASSED,
        FAILED,
        TIMEOUT,
        RUNTIME_ERROR,
        SYSTEM_ERROR
    }

    record TestResult(
        UUID testCaseId,
        boolean passed,
        long executionTimeMs,
        String stdoutExcerpt,
        String stderrExcerpt
    ) {
    }
}
