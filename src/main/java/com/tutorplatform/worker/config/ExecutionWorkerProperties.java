package com.tutorplatform.worker.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "execution")
public record ExecutionWorkerProperties(
    @NotNull @Valid Worker worker,
    @NotNull @Valid Runtime runtime
) {
    public record Worker(
        @Min(1) @Max(64) int maxConcurrent,
        @NotNull Duration queueTimeout,
        @Min(1) @Max(1_000) int maxTestCases
    ) {
        public Worker {
            requirePositiveAtMost(queueTimeout, Duration.ofSeconds(30), "execution.worker.queue-timeout");
        }
    }

    public record Runtime(
        @NotBlank String dockerExecutable,
        @NotBlank String pythonImage,
        @DecimalMin("0.1") @DecimalMax("8.0") double cpuLimit,
        @Min(4) @Max(512) int pidLimit,
        @Min(1) @Max(1_048_576) int outputMaxBytes,
        @Min(8_192) @Max(10_485_760) long workspaceMaxBytes,
        @Min(1_048_576) @Max(268_435_456) long tmpfsMaxBytes,
        @NotNull Duration operationTimeout,
        @NotNull Duration containerStartupGrace,
        @NotNull Path workspaceDirectory,
        String workspaceVolume
    ) {
        public Runtime {
            requirePositiveAtMost(operationTimeout, Duration.ofSeconds(60), "execution.runtime.operation-timeout");
            requirePositiveAtMost(
                containerStartupGrace,
                Duration.ofSeconds(10),
                "execution.runtime.container-startup-grace"
            );
            if (workspaceDirectory != null && !workspaceDirectory.isAbsolute()) {
                throw new IllegalArgumentException("execution.runtime.workspace-directory must be absolute");
            }
            if (workspaceVolume != null && !workspaceVolume.isBlank()
                && !workspaceVolume.matches("[a-zA-Z0-9_.-]+")) {
                throw new IllegalArgumentException("execution.runtime.workspace-volume is invalid");
            }
        }
    }

    private static void requirePositiveAtMost(Duration value, Duration maximum, String name) {
        if (value != null && (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0)) {
            throw new IllegalArgumentException(name + " must be positive and at most " + maximum);
        }
    }
}
