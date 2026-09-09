package com.tutorplatform.worker.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.slf4j.LoggerFactory.getLogger;

class ExecutionServiceTest {

    @Test
    void allTestsPassedMapsToPassed() {
        var service = service(completed("first\n"), completed("second\n"));
        var outcome = service.execute(command(List.of(testCase("first"), testCase("second")), 64));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(outcome.passedTests()).isEqualTo(2);
    }

    @Test
    void mismatchedTestMapsToFailed() {
        var outcome = service(completed("wrong\n"))
            .execute(command(List.of(testCase("expected")), 64));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.passedTests()).isZero();
    }

    @Test
    void runtimeErrorMapsToRuntimeError() {
        assertThat(outcomeFor(SandboxResult.Status.RUNTIME_ERROR).status())
            .isEqualTo(ExecutionStatus.RUNTIME_ERROR);
    }

    @Test
    void timeoutMapsToTimeout() {
        assertThat(outcomeFor(SandboxResult.Status.TIMEOUT).status()).isEqualTo(ExecutionStatus.TIMEOUT);
    }

    @Test
    void infrastructureFailureMapsToSystemError() {
        var service = new ExecutionService(
            request -> { throw new IllegalStateException("runtime unavailable"); },
            new OutputComparator(),
            properties(64)
        );

        var outcome = service.execute(command(List.of(testCase("expected")), 64));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.SYSTEM_ERROR);
    }

    @Test
    void stdoutAndStderrAreTruncatedAtUtf8Boundary() {
        var service = service(4, new SandboxResult(
            SandboxResult.Status.RUNTIME_ERROR, 1, "ééé", "abcdef", false, false
        ));

        var outcome = service.execute(command(List.of(testCase("ignored")), 64));

        assertThat(outcome.stdoutExcerpt()).isEqualTo("éé");
        assertThat(outcome.stderrExcerpt()).isEqualTo("abcd");
        assertThat(outcome.testResults().getFirst().stdoutExcerpt()).isEqualTo("éé");
        assertThat(outcome.testResults().getFirst().stderrExcerpt()).isEqualTo("abcd");
    }

    @Test
    void truncatedStdoutCannotAccidentallyPassAsCompleteOutput() {
        var service = service(new SandboxResult(
            SandboxResult.Status.COMPLETED, 1, "expected", null, true, false
        ));

        var outcome = service.execute(command(List.of(testCase("expected")), 64));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void concurrencyLimitUsesBoundedRejection() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        SandboxRuntime runtime = request -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return completed("expected\n");
        };
        var properties = properties(64, 1, Duration.ofMillis(20));
        var service = new ExecutionService(runtime, new OutputComparator(), properties);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.execute(command(List.of(testCase("expected")), 64)));
            entered.await();
            var rejected = service.execute(command(List.of(testCase("expected")), 64));
            release.countDown();

            assertThat(rejected.status()).isEqualTo(ExecutionStatus.SYSTEM_ERROR);
            assertThat(first.get().status()).isEqualTo(ExecutionStatus.PASSED);
        }
    }

    @Test
    void logsDoNotContainSourceHiddenExpectedOutputOrRuntimeOutput() {
        var source = "print('do-not-log-source')";
        var expected = "do-not-log-hidden-expected";
        var runtimeOutput = "do-not-log-runtime-output";
        var service = service(completed(runtimeOutput));
        var command = new ExecutionCommand(
            UUID.randomUUID(), ExecutionLanguage.PYTHON, source, 5_000, 128, 64,
            List.of(testCase(expected))
        );
        var logger = (Logger) getLogger(ExecutionService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.execute(command);
        } finally {
            logger.detachAppender(appender);
        }

        var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).noneMatch(message -> message.contains(source));
        assertThat(messages).noneMatch(message -> message.contains(expected));
        assertThat(messages).noneMatch(message -> message.contains(runtimeOutput));
    }

    private static ExecutionOutcome outcomeFor(SandboxResult.Status status) {
        return service(new SandboxResult(status, 5, null, "bounded", false, false))
            .execute(command(List.of(testCase("expected")), 64));
    }

    private static ExecutionService service(SandboxResult... results) {
        return service(64, results);
    }

    private static ExecutionService service(int outputMaxBytes, SandboxResult... results) {
        var queue = new ArrayDeque<>(List.of(results));
        SandboxRuntime runtime = request -> queue.removeFirst();
        return new ExecutionService(runtime, new OutputComparator(), properties(outputMaxBytes));
    }

    private static SandboxResult completed(String stdout) {
        return new SandboxResult(SandboxResult.Status.COMPLETED, 5, stdout, null, false, false);
    }

    private static ExecutionCommand command(List<ExecutionCommand.TestCase> testCases, int outputLimitBytes) {
        return new ExecutionCommand(
            UUID.randomUUID(), ExecutionLanguage.PYTHON, "print('test')", 5_000, 128,
            outputLimitBytes, testCases
        );
    }

    private static ExecutionCommand.TestCase testCase(String expected) {
        return new ExecutionCommand.TestCase(
            UUID.randomUUID(), null, expected, ComparisonMode.NORMALIZED
        );
    }

    private static ExecutionWorkerProperties properties(int outputMaxBytes) {
        return properties(outputMaxBytes, 2, Duration.ofMillis(10));
    }

    private static ExecutionWorkerProperties properties(
        int outputMaxBytes,
        int maxConcurrent,
        Duration queueTimeout
    ) {
        return new ExecutionWorkerProperties(
            new ExecutionWorkerProperties.Worker(maxConcurrent, queueTimeout, 100),
            new ExecutionWorkerProperties.Runtime(
                "docker", "python:fixed", 1.0, 32, outputMaxBytes,
                1_048_576, 16_777_216, Duration.ofSeconds(5), Duration.ofSeconds(1),
                Path.of("/tmp/execution-tests"), null
            )
        );
    }
}
