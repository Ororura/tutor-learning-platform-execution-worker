package com.tutorplatform.worker.infrastructure.docker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tutorplatform.worker.application.ExecutionLanguage;
import com.tutorplatform.worker.application.SandboxRequest;
import com.tutorplatform.worker.application.SandboxResult;
import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slf4j.LoggerFactory.getLogger;

class DockerSandboxRuntimeTest {

    @TempDir
    Path tempDirectory;

    @Test
    void executionLogsContainOnlySafeMetadata() {
        var sourceCode = "print('do-not-log-source-code')";
        var stdin = "do-not-log-stdin";
        var stdout = "do-not-log-stdout";
        var stderr = "do-not-log-stderr";
        var runner = new StubDockerCommandRunner(
            result(0, false, 1, "container", ""),
            result(0, false, 42, stdout, stderr),
            result(0, false, 1, "0 false", ""),
            result(0, false, 1, "", "")
        );
        var runtime = new DockerSandboxRuntime(runner, properties());
        var logger = (Logger) getLogger(DockerSandboxRuntime.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        SandboxResult result;
        try {
            result = runtime.execute(new SandboxRequest(
                UUID.randomUUID(), ExecutionLanguage.PYTHON, sourceCode, stdin, 5_000, 128, 64
            ));
        } finally {
            logger.detachAppender(appender);
        }

        var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(result).extracting(SandboxResult::status, SandboxResult::executionTimeMs)
            .containsExactly(SandboxResult.Status.COMPLETED, 42L);
        assertThat(messages).anyMatch(message -> message.contains("executionId="));
        assertThat(messages).anyMatch(message -> message.contains("exitCode=0"));
        assertThat(messages).anyMatch(message -> message.contains("timeout=false"));
        assertThat(messages).anyMatch(message -> message.contains("durationMs=42"));
        assertThat(messages).anyMatch(message -> message.contains("status=COMPLETED"));
        assertThat(messages).noneMatch(message -> message.contains(sourceCode));
        assertThat(messages).noneMatch(message -> message.contains(stdin));
        assertThat(messages).noneMatch(message -> message.contains(stdout));
        assertThat(messages).noneMatch(message -> message.contains(stderr));
    }

    private ExecutionWorkerProperties properties() {
        return new ExecutionWorkerProperties(
            new ExecutionWorkerProperties.Worker(1, Duration.ofSeconds(1), 100),
            new ExecutionWorkerProperties.Runtime(
                "docker", "python:fixed", 1.0, 32, 64,
                1_048_576, 16_777_216, Duration.ofSeconds(5), Duration.ofSeconds(1),
                tempDirectory, null
            )
        );
    }

    private static DockerCommandResult result(
        int exitCode,
        boolean timedOut,
        long durationMs,
        String stdout,
        String stderr
    ) {
        return new DockerCommandResult(exitCode, timedOut, durationMs, stdout, stderr, false, false);
    }

    private static class StubDockerCommandRunner extends DockerCommandRunner {
        private final ArrayDeque<DockerCommandResult> results;

        StubDockerCommandRunner(DockerCommandResult... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        DockerCommandResult run(List<String> command, String stdin, Duration timeout, int outputMaxBytes) {
            return results.removeFirst();
        }
    }
}
