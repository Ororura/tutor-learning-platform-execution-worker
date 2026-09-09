package com.tutorplatform.worker.api;

import com.tutorplatform.worker.application.ExecutionCommand;
import com.tutorplatform.worker.application.ExecutionLanguage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

record WorkerExecutionRequest(
    @NotNull UUID executionId,
    @NotNull Language language,
    @NotBlank @Size(max = 1_000_000) String sourceCode,
    @Min(100) @Max(30_000) int timeLimitMs,
    @Min(16) @Max(1_024) int memoryLimitMb,
    @Min(1) @Max(1_048_576) int outputLimitBytes,
    @NotEmpty List<@Valid TestCase> testCases
) {
    ExecutionCommand toCommand() {
        return new ExecutionCommand(
            executionId,
            ExecutionLanguage.valueOf(language.name()),
            sourceCode,
            timeLimitMs,
            memoryLimitMb,
            outputLimitBytes,
            testCases.stream().map(testCase -> new ExecutionCommand.TestCase(
                testCase.id(),
                testCase.inputText(),
                testCase.expectedOutput(),
                com.tutorplatform.worker.application.ComparisonMode.valueOf(testCase.comparisonMode().name())
            )).toList()
        );
    }

    enum Language {
        PYTHON
    }

    record TestCase(
        @NotNull UUID id,
        String inputText,
        @NotNull String expectedOutput,
        @NotNull ComparisonMode comparisonMode
    ) {
    }

    enum ComparisonMode {
        EXACT,
        NORMALIZED
    }
}
