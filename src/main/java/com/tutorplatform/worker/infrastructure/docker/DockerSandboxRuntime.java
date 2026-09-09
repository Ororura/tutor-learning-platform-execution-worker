package com.tutorplatform.worker.infrastructure.docker;

import com.tutorplatform.worker.application.ExecutionLanguage;
import com.tutorplatform.worker.application.SandboxRequest;
import com.tutorplatform.worker.application.SandboxResult;
import com.tutorplatform.worker.application.SandboxRuntime;
import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class DockerSandboxRuntime implements SandboxRuntime {
    private static final Logger log = LoggerFactory.getLogger(DockerSandboxRuntime.class);
    private static final int INTERNAL_OUTPUT_LIMIT = 8_192;
    private static final String SANDBOX_RUNNER = """
        import os
        import signal
        import subprocess
        import sys

        timeout_seconds = int(sys.argv[1]) / 1000
        environment = {
            'PATH': '/usr/local/bin:/usr/bin:/bin',
            'PYTHONDONTWRITEBYTECODE': '1',
            'PYTHONHASHSEED': '0',
            'PYTHONUNBUFFERED': '1',
        }

        try:
            process = subprocess.Popen(
                ['/usr/local/bin/python3', '-I', '-B', '/workspace/main.py'],
                stdin=sys.stdin.buffer,
                stdout=sys.stdout.buffer,
                stderr=sys.stderr.buffer,
                env=environment,
                start_new_session=True,
            )
            try:
                return_code = process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                raise SystemExit(124)
            raise SystemExit(0 if return_code == 0 else 1)
        except SystemExit:
            raise
        except BaseException:
            raise SystemExit(125)
        """;

    private final DockerCommandRunner commandRunner;
    private final ExecutionWorkerProperties properties;

    public DockerSandboxRuntime(DockerCommandRunner commandRunner, ExecutionWorkerProperties properties) {
        this.commandRunner = commandRunner;
        this.properties = properties;
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        if (request.language() != ExecutionLanguage.PYTHON) {
            return systemError();
        }

        Path workspace = null;
        String containerName = null;
        var containerCreated = false;
        SandboxResult result;
        try {
            workspace = createWorkspace(request);
            containerName = containerName(request.executionId());
            // Cleanup by the server-generated name even if create times out after daemon acceptance.
            containerCreated = true;
            var createResult = commandRunner.run(
                createCommand(containerName, workspace, request),
                null,
                properties.runtime().operationTimeout(),
                INTERNAL_OUTPUT_LIMIT
            );
            if (createResult.timedOut() || createResult.exitCode() != 0) {
                logInfrastructureFailure(request.executionId(), "container-create");
                result = systemError();
            } else {
                result = runContainer(containerName, request);
            }
        } catch (IOException exception) {
            logInfrastructureFailure(request.executionId(), "io");
            result = systemError();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logInfrastructureFailure(request.executionId(), "interrupted");
            result = systemError();
        } catch (RuntimeException exception) {
            logInfrastructureFailure(request.executionId(), exception.getClass().getSimpleName());
            result = systemError();
        }

        var cleanupSucceeded = cleanup(containerName, containerCreated, workspace, request.executionId());
        return cleanupSucceeded ? result : systemError();
    }

    private SandboxResult runContainer(String containerName, SandboxRequest request)
        throws IOException, InterruptedException {
        var runResult = commandRunner.run(
            List.of(properties.runtime().dockerExecutable(), "start", "--attach", "--interactive", containerName),
            request.stdin(),
            Duration.ofMillis(request.timeLimitMs()).plus(properties.runtime().containerStartupGrace()),
            request.outputLimitBytes()
        );
        if (runResult.timedOut()) {
            return new SandboxResult(
                SandboxResult.Status.TIMEOUT,
                runResult.durationMs(),
                nullIfEmpty(runResult.stdout()),
                nullIfEmpty(runResult.stderr()),
                runResult.stdoutTruncated(),
                runResult.stderrTruncated()
            );
        }

        var state = inspectState(containerName, request.executionId());
        if (state == null) {
            return systemError();
        }
        if (state.exitCode() == 0) {
            return new SandboxResult(
                SandboxResult.Status.COMPLETED,
                runResult.durationMs(),
                nullIfEmpty(runResult.stdout()),
                nullIfEmpty(runResult.stderr()),
                runResult.stdoutTruncated(),
                runResult.stderrTruncated()
            );
        }
        if (state.exitCode() == 124) {
            return new SandboxResult(
                SandboxResult.Status.TIMEOUT,
                runResult.durationMs(),
                nullIfEmpty(runResult.stdout()),
                nullIfEmpty(runResult.stderr()),
                runResult.stdoutTruncated(),
                runResult.stderrTruncated()
            );
        }
        if (state.oomKilled()) {
            return new SandboxResult(
                SandboxResult.Status.RUNTIME_ERROR,
                runResult.durationMs(),
                nullIfEmpty(runResult.stdout()),
                nullIfEmpty(runResult.stderr()),
                runResult.stdoutTruncated(),
                runResult.stderrTruncated()
            );
        }
        if (state.exitCode() == 125 || state.exitCode() == 126 || state.exitCode() == 127) {
            logInfrastructureFailure(request.executionId(), "fixed-runtime-start");
            return systemError();
        }
        return new SandboxResult(
            SandboxResult.Status.RUNTIME_ERROR,
            runResult.durationMs(),
            nullIfEmpty(runResult.stdout()),
            nullIfEmpty(runResult.stderr()),
            runResult.stdoutTruncated(),
            runResult.stderrTruncated()
        );
    }

    private ContainerState inspectState(String containerName, UUID executionId)
        throws IOException, InterruptedException {
        var inspectResult = commandRunner.run(
            List.of(
                properties.runtime().dockerExecutable(),
                "inspect",
                "--format={{.State.ExitCode}} {{.State.OOMKilled}}",
                containerName
            ),
            null,
            properties.runtime().operationTimeout(),
            INTERNAL_OUTPUT_LIMIT
        );
        if (inspectResult.timedOut() || inspectResult.exitCode() != 0) {
            logInfrastructureFailure(executionId, "container-inspect");
            return null;
        }
        var parts = inspectResult.stdout().trim().split("\\s+");
        if (parts.length != 2) {
            logInfrastructureFailure(executionId, "container-state");
            return null;
        }
        try {
            return new ContainerState(Integer.parseInt(parts[0]), Boolean.parseBoolean(parts[1]));
        } catch (NumberFormatException exception) {
            logInfrastructureFailure(executionId, "container-state");
            return null;
        }
    }

    private Path createWorkspace(SandboxRequest request) throws IOException {
        Files.createDirectories(properties.runtime().workspaceDirectory());
        var workspace = Files.createTempDirectory(
            properties.runtime().workspaceDirectory(),
            "execution-" + request.executionId() + "-"
        );
        var source = workspace.resolve("main.py");
        var runner = workspace.resolve("runner.py");
        Files.writeString(source, request.sourceCode(), StandardCharsets.UTF_8);
        Files.writeString(runner, SANDBOX_RUNNER, StandardCharsets.UTF_8);
        setRuntimeReadablePermissions(workspace, source, runner);
        return workspace;
    }

    private static void setRuntimeReadablePermissions(Path workspace, Path source, Path runner) {
        try {
            Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("r--r--r--"));
            Files.setPosixFilePermissions(runner, PosixFilePermissions.fromString("r--r--r--"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Platform ACLs remain in force on non-POSIX filesystems.
        }
    }

    private List<String> createCommand(String containerName, Path workspace, SandboxRequest request) {
        var runtime = properties.runtime();
        var command = new ArrayList<String>();
        command.addAll(List.of(
            runtime.dockerExecutable(),
            "create",
            "--name", containerName,
            "--interactive",
            "--label", "com.tutorplatform.execution=true",
            "--log-driver", "none",
            "--network", "none",
            "--ipc", "none",
            "--read-only",
            "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges",
            "--pids-limit", Integer.toString(runtime.pidLimit()),
            "--memory", request.memoryLimitMb() + "m",
            "--memory-swap", request.memoryLimitMb() + "m",
            "--cpus", String.format(Locale.ROOT, "%.3f", runtime.cpuLimit()),
            "--user", "65534:65534",
            "--workdir", "/workspace",
            "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=" + runtime.tmpfsMaxBytes(),
            "--shm-size", Long.toString(runtime.tmpfsMaxBytes()),
            "--ulimit", "nofile=64:64",
            "--ulimit", "nproc=" + runtime.pidLimit() + ":" + runtime.pidLimit(),
            "--stop-timeout", "1",
            "--init",
            "--env", "PYTHONDONTWRITEBYTECODE=1",
            "--env", "PYTHONUNBUFFERED=1",
            "--env", "PYTHONHASHSEED=0",
            workspaceMount(workspace),
            runtime.pythonImage(),
            "python3", "-I", "-B", "/workspace/runner.py", Integer.toString(request.timeLimitMs())
        ));
        return List.copyOf(command);
    }

    private String workspaceMount(Path workspace) {
        var volume = properties.runtime().workspaceVolume();
        if (volume == null || volume.isBlank()) {
            return "--mount=type=bind,src=" + workspace.toAbsolutePath() + ",dst=/workspace,readonly";
        }
        return "--mount=type=volume,src=" + volume
            + ",dst=/workspace,volume-subpath=" + workspace.getFileName() + ",readonly";
    }

    private boolean cleanup(String containerName, boolean containerCreated, Path workspace, UUID executionId) {
        var succeeded = true;
        if (containerCreated) {
            try {
                var removeResult = commandRunner.run(
                    List.of(properties.runtime().dockerExecutable(), "rm", "--force", containerName),
                    null,
                    properties.runtime().operationTimeout(),
                    INTERNAL_OUTPUT_LIMIT
                );
                if (removeResult.timedOut() || removeResult.exitCode() != 0) {
                    succeeded = false;
                    logInfrastructureFailure(executionId, "container-cleanup");
                }
            } catch (IOException exception) {
                succeeded = false;
                logInfrastructureFailure(executionId, "container-cleanup");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                succeeded = false;
                logInfrastructureFailure(executionId, "container-cleanup-interrupted");
            }
        }
        if (!deleteWorkspace(workspace)) {
            succeeded = false;
            logInfrastructureFailure(executionId, "workspace-cleanup");
        }
        return succeeded;
    }

    private static boolean deleteWorkspace(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return true;
        }
        try (var paths = Files.walk(workspace)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static String containerName(UUID executionId) {
        return "tutor-exec-"
            + executionId.toString().replace("-", "")
            + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static SandboxResult systemError() {
        return new SandboxResult(SandboxResult.Status.SYSTEM_ERROR, 0, null, null, false, false);
    }

    private static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void logInfrastructureFailure(UUID executionId, String stage) {
        log.warn("Sandbox infrastructure failure: executionId={}, stage={}", executionId, stage);
    }

    private record ContainerState(int exitCode, boolean oomKilled) {
    }
}
