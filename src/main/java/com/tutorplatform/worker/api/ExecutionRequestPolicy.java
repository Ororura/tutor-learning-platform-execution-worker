package com.tutorplatform.worker.api;

import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
class ExecutionRequestPolicy {
    private static final int TRUSTED_WORKSPACE_OVERHEAD_BYTES = 4_096;
    private final ExecutionWorkerProperties properties;

    ExecutionRequestPolicy(ExecutionWorkerProperties properties) {
        this.properties = properties;
    }

    void validate(WorkerExecutionRequest request) {
        if (request.testCases().size() > properties.worker().maxTestCases()) {
            throw new InvalidExecutionRequestException("Too many test cases");
        }
        if (utf8Size(request.sourceCode())
            > properties.runtime().workspaceMaxBytes() - TRUSTED_WORKSPACE_OVERHEAD_BYTES) {
            throw new InvalidExecutionRequestException("Source code exceeds the workspace limit");
        }
        for (var testCase : request.testCases()) {
            if (testCase.inputText() != null
                && utf8Size(testCase.inputText()) > properties.runtime().workspaceMaxBytes()) {
                throw new InvalidExecutionRequestException("Test input exceeds the request limit");
            }
            if (utf8Size(testCase.expectedOutput()) > properties.runtime().workspaceMaxBytes()) {
                throw new InvalidExecutionRequestException("Expected output exceeds the request limit");
            }
        }
    }

    private static int utf8Size(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
