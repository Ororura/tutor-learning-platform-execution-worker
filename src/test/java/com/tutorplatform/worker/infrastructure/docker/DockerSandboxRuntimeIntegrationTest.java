package com.tutorplatform.worker.infrastructure.docker;

import com.tutorplatform.worker.application.ComparisonMode;
import com.tutorplatform.worker.application.ExecutionCommand;
import com.tutorplatform.worker.application.ExecutionLanguage;
import com.tutorplatform.worker.application.ExecutionOutcome;
import com.tutorplatform.worker.application.ExecutionService;
import com.tutorplatform.worker.application.ExecutionStatus;
import com.tutorplatform.worker.application.OutputComparator;
import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("sandbox-integration")
class DockerSandboxRuntimeIntegrationTest {
    private static final String PYTHON_IMAGE = "python:3.12.10-alpine3.21@sha256:"
        + "9c51ecce261773a684c8345b2d4673700055c513b4d54bc0719337d3e4ee552e";

    @TempDir
    private Path tempDirectory;

    @Test
    void executesPythonAndSuppliesStdin() throws Exception {
        var hello = execute("print('hello')", null, "hello", 5_000, 128, 1_024);
        assertThat(hello.status()).isEqualTo(ExecutionStatus.PASSED);

        var sum = execute(
            "a, b = map(int, input().split())\nprint(a + b)",
            "2 3\n",
            "5",
            5_000,
            128,
            1_024
        );
        assertThat(sum.status()).as(sum.toString()).isEqualTo(ExecutionStatus.PASSED);
    }

    @Test
    void mismatchedOutputIsFailed() throws Exception {
        var outcome = execute("print('wrong')", null, "right", 5_000, 128, 1_024);

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void syntaxErrorAndUncaughtExceptionAreRuntimeErrors() throws Exception {
        assertThat(execute("this is not python:", null, "", 5_000, 128, 1_024).status())
            .isEqualTo(ExecutionStatus.RUNTIME_ERROR);
        assertThat(execute("raise RuntimeError('boom')", null, "", 5_000, 128, 1_024).status())
            .isEqualTo(ExecutionStatus.RUNTIME_ERROR);
    }

    @Test
    void infiniteLoopTimesOutAndContainerIsDestroyed() throws Exception {
        var outcome = execute("while True:\n    pass", null, "", 300, 128, 1_024);

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.TIMEOUT);
    }

    @Test
    void childProcessesCannotEscapeTimeoutAndPidLimitIsEnforced() throws Exception {
        var childAfterTimeout = execute(
            """
            import subprocess
            subprocess.Popen(['python3', '-c', 'while True: pass'])
            while True:
                pass
            """,
            null,
            "",
            500,
            128,
            1_024
        );
        assertThat(childAfterTimeout.status()).isEqualTo(ExecutionStatus.TIMEOUT);

        var pidAbuse = execute(
            """
            import subprocess
            children = []
            try:
                for _ in range(100):
                    children.append(subprocess.Popen(['python3', '-c', 'import time; time.sleep(60)']))
                print('unlimited')
            except OSError:
                print('limited')
            """,
            null,
            "limited",
            5_000,
            128,
            1_024
        );
        assertThat(pidAbuse.status()).isEqualTo(ExecutionStatus.PASSED);
    }

    @Test
    void outputIsReadWithoutUnboundedAccumulationAndTruncated() throws Exception {
        var outcome = execute("print('x' * 1000000)", null, "ignored", 5_000, 128, 64);

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.stdoutExcerpt().getBytes(StandardCharsets.UTF_8)).hasSize(64);
        assertThat(outcome.testResults().getFirst().stdoutExcerpt().getBytes(StandardCharsets.UTF_8)).hasSize(64);
    }

    @Test
    void filesystemHostEnvironmentAndNetworkAreIsolated() throws Exception {
        var outcome = execute(
            """
            import os
            import socket

            checks = []
            try:
                open('/workspace/escape', 'w').write('x')
                checks.append('workspace-writable')
            except OSError:
                checks.append('workspace-readonly')

            checks.append('host-visible' if os.path.exists('/workspace/settings.gradle.kts') else 'host-hidden')
            leaked = any(k in os.environ for k in (
                'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
                'AWS_ACCESS_KEY_ID', 'GOOGLE_APPLICATION_CREDENTIALS'
            ))
            checks.append('secrets-leaked' if leaked else 'secrets-absent')

            try:
                socket.create_connection(('1.1.1.1', 80), timeout=0.2)
                checks.append('internet-open')
            except OSError:
                checks.append('internet-blocked')

            try:
                socket.create_connection(('172.17.0.1', 8080), timeout=0.2)
                checks.append('internal-open')
            except OSError:
                checks.append('internal-blocked')

            print(','.join(checks))
            """,
            null,
            "workspace-readonly,host-hidden,secrets-absent,internet-blocked,internal-blocked",
            5_000,
            128,
            2_048
        );

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.PASSED);
    }

    @Test
    void memoryLimitIsEnforced() throws Exception {
        var outcome = execute(
            "data = bytearray(256 * 1024 * 1024)\nprint(len(data))",
            null,
            "ignored",
            5_000,
            32,
            1_024
        );

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
    }

    private ExecutionOutcome execute(
        String source,
        String input,
        String expected,
        int timeLimitMs,
        int memoryLimitMb,
        int outputLimitBytes
    ) throws Exception {
        var executionId = UUID.randomUUID();
        var properties = properties();
        var runtime = new DockerSandboxRuntime(new DockerCommandRunner(), properties);
        var service = new ExecutionService(runtime, new OutputComparator(), properties);
        var outcome = service.execute(new ExecutionCommand(
            executionId,
            ExecutionLanguage.PYTHON,
            source,
            timeLimitMs,
            memoryLimitMb,
            outputLimitBytes,
            List.of(new ExecutionCommand.TestCase(
                UUID.randomUUID(), input, expected, ComparisonMode.NORMALIZED
            ))
        ));

        assertWorkspaceClean();
        assertNoContainer(executionId);
        return outcome;
    }

    private ExecutionWorkerProperties properties() {
        return new ExecutionWorkerProperties(
            new ExecutionWorkerProperties.Worker(2, Duration.ofMillis(50), 100),
            new ExecutionWorkerProperties.Runtime(
                "docker",
                PYTHON_IMAGE,
                0.5,
                16,
                4_096,
                1_048_576,
                4_194_304,
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                tempDirectory.resolve("workspaces").toAbsolutePath(),
                null
            )
        );
    }

    private void assertWorkspaceClean() throws IOException {
        var workspaceRoot = tempDirectory.resolve("workspaces");
        try (var children = Files.list(workspaceRoot)) {
            assertThat(children).isEmpty();
        }
    }

    private static void assertNoContainer(UUID executionId) throws Exception {
        var prefix = "tutor-exec-" + executionId.toString().replace("-", "");
        var process = new ProcessBuilder(
            "docker", "ps", "--all", "--filter", "name=" + prefix, "--format", "{{.Names}}"
        ).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        assertThat(output).isBlank();
    }
}
