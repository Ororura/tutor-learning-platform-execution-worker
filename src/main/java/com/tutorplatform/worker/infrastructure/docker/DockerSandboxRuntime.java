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
import java.util.Base64;
import java.util.Map;
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
            'PATH': '/opt/java/openjdk/bin:/usr/local/bin:/usr/bin:/bin',
            'PYTHONDONTWRITEBYTECODE': '1',
            'PYTHONHASHSEED': '0',
            'PYTHONUNBUFFERED': '1',
        }

        try:
            process = subprocess.Popen(
                sys.argv[2:],
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
            if return_code == 125 and '/workspace/compiler.py' in sys.argv[2:]:
                raise SystemExit(125)
            raise SystemExit(0 if return_code == 0 else 1)
        except SystemExit:
            raise
        except BaseException:
            raise SystemExit(125)
        """;

    private static final String JAVA_COMPILER = """
        import base64
        import os
        import subprocess
        import sys
        try:
            os.mkdir('/tmp/classes')
            heap = max(16, int(sys.argv[1]) - 80)
            compiled = subprocess.run([
                '/opt/java/openjdk/bin/javac', '-J-XX:+UseSerialGC', '-J-XX:ActiveProcessorCount=1',
                '-J-Xmx' + str(heap) + 'm', '-J-XX:-UsePerfData', '-proc:none', '--release', '21',
                '-encoding', 'UTF-8', '-d', '/tmp/classes', '/workspace/Main.java'
            ], stdout=sys.stderr)
            if compiled.returncode:
                raise SystemExit(1)
            if not os.path.isfile('/tmp/classes/Main.class'):
                print('Use class Main without a package declaration.', file=sys.stderr)
                raise SystemExit(1)
            # A bounded archive crosses the sandbox boundary. It is never unpacked on the host.
            archived = subprocess.run([
                '/opt/java/openjdk/bin/jar', '-J-XX:+UseSerialGC', '-J-XX:ActiveProcessorCount=1',
                '-J-Xmx' + str(heap) + 'm', '-J-XX:-UsePerfData',
                '--create', '--file', '/tmp/program.jar', '-C', '/tmp/classes', '.'
            ], stdout=sys.stderr)
            if archived.returncode:
                raise SystemExit(1)
            with open('/tmp/program.jar', 'rb') as artifact:
                data = artifact.read(int(sys.argv[2]) + 1)
            if len(data) > int(sys.argv[2]):
                print('Compiled program exceeds the workspace limit.', file=sys.stderr)
                raise SystemExit(1)
            sys.stdout.write(base64.b64encode(data).decode('ascii'))
        except SystemExit:
            raise
        except BaseException:
            raise SystemExit(125)
        """;
    private final Map<ExecutionLanguage, LanguageRuntimes.Runtime> runtimes;
    private final DockerCommandRunner commandRunner;
    private final ExecutionWorkerProperties properties;

    DockerSandboxRuntime(DockerCommandRunner commandRunner, ExecutionWorkerProperties properties) {
        this.commandRunner = commandRunner;
        this.properties = properties;
        this.runtimes = LanguageRuntimes.registry(properties.runtime());
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        try (var prepared = prepare(request)) {
            return prepared.preparationResult() == null
                ? prepared.execute(request.stdin()) : prepared.preparationResult();
        }
    }

    @Override
    public PreparedExecution prepare(SandboxRequest request) {
        Path workspace;
        try {
            workspace = createWorkspace(request);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot prepare workspace", exception);
        }
        var language = runtimes.get(request.language());
        SandboxResult failure = null;
        try {
            if (language.compilationRequired()) {
                // Compilation uses the same sandbox limits, with its own fixed wall deadline.
                long existingBytes;
                try (var files = Files.list(workspace)) {
                    existingBytes = 0;
                    for (var file : files.toList()) existingBytes += Files.size(file);
                }
                int artifactLimit = Math.toIntExact(properties.runtime().workspaceMaxBytes() - existingBytes);
                var compileRequest = new SandboxRequest(request.executionId(), request.language(),
                    request.sourceCode(), null, 10_000, request.memoryLimitMb(), 4 * ((artifactLimit + 2) / 3));
                var compiled = executeInWorkspace(compileRequest, workspace, List.of("python3", "-I", "-B",
                    "/workspace/compiler.py", Integer.toString(request.memoryLimitMb()), Integer.toString(artifactLimit)));
                if (compiled.status() != SandboxResult.Status.COMPLETED) {
                    failure = safeCompilationFailure(compiled, request.outputLimitBytes());
                } else if (compiled.stdoutTruncated()) {
                    failure = new SandboxResult(SandboxResult.Status.RUNTIME_ERROR, compiled.executionTimeMs(),
                        null, "Compiled program exceeds the workspace limit.", false, false);
                } else {
                    byte[] artifact = Base64.getDecoder().decode(compiled.stdoutExcerpt());
                    var target = workspace.resolve("program.jar");
                    Files.write(target, artifact);
                    setRuntimeReadablePermissions(workspace, target, target);
                }
            }
        } catch (IOException | RuntimeException exception) {
            cleanup(null, false, workspace, request.executionId());
            throw new IllegalStateException("Cannot prepare program", exception);
        }
        var preparationFailure = failure;
        return new PreparedExecution() {
            public SandboxResult preparationResult() { return preparationFailure; }
            public SandboxResult execute(String stdin) {
                return executeInWorkspace(new SandboxRequest(request.executionId(), request.language(),
                    request.sourceCode(), stdin, request.timeLimitMs(), request.memoryLimitMb(), request.outputLimitBytes()),
                    workspace, language.command(request.memoryLimitMb()));
            }
            public void close() {
                if (!cleanup(null, false, workspace, request.executionId())) {
                    throw new IllegalStateException("Cannot clean workspace");
                }
            }
        };
    }

    private static SandboxResult safeCompilationFailure(SandboxResult result, int outputLimit) {
        var diagnostics = result.stderrExcerpt();
        if (diagnostics != null) {
            diagnostics = diagnostics.replace("/workspace/", "").replace("/tmp/", "");
            diagnostics = com.tutorplatform.worker.application.BoundedTextAccumulator.truncate(diagnostics, outputLimit);
        }
        return new SandboxResult(result.status(), result.executionTimeMs(), null, diagnostics,
            false, result.stderrTruncated());
    }

    private SandboxResult executeInWorkspace(SandboxRequest request, Path workspace, List<String> programCommand) {
        String containerName = null;
        var containerCreated = false;
        SandboxResult result;
        try {
            containerName = containerName(request.executionId());
            // Cleanup by the server-generated name even if create times out after daemon acceptance.
            containerCreated = true;
            var createResult = commandRunner.run(
                createCommand(containerName, workspace, request, programCommand),
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

        var cleanupSucceeded = cleanup(containerName, containerCreated, null, request.executionId());
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
            return logExecution(request, runResult, new SandboxResult(
                SandboxResult.Status.TIMEOUT,
                runResult.durationMs(),
                nullIfEmpty(runResult.stdout()),
                nullIfEmpty(runResult.stderr()),
                runResult.stdoutTruncated(),
                runResult.stderrTruncated()
            ));
        }

        var state = inspectState(containerName, request.executionId());
        if (state == null) {
            return logExecution(request, runResult, systemError());
        }
        if (state.exitCode() == 0) {
            return logExecution(request, runResult, new SandboxResult(
                SandboxResult.Status.COMPLETED,
                runResult.durationMs(),
                nullIfEmpty(runResult.stdout()),
                nullIfEmpty(runResult.stderr()),
                runResult.stdoutTruncated(),
                runResult.stderrTruncated()
            ));
        }
        if (state.exitCode() == 124) {
            return logExecution(request, runResult, new SandboxResult(
                SandboxResult.Status.TIMEOUT,
                runResult.durationMs(),
                nullIfEmpty(runResult.stdout()),
                nullIfEmpty(runResult.stderr()),
                runResult.stdoutTruncated(),
                runResult.stderrTruncated()
            ));
        }
        if (state.oomKilled()) {
            return logExecution(request, runResult, new SandboxResult(
                SandboxResult.Status.RUNTIME_ERROR,
                runResult.durationMs(),
                nullIfEmpty(runResult.stdout()),
                nullIfEmpty(runResult.stderr()),
                runResult.stdoutTruncated(),
                runResult.stderrTruncated()
            ));
        }
        if (state.exitCode() == 125 || state.exitCode() == 126 || state.exitCode() == 127) {
            logInfrastructureFailure(request.executionId(), "fixed-runtime-start");
            return logExecution(request, runResult, systemError());
        }
        return logExecution(request, runResult, new SandboxResult(
            SandboxResult.Status.RUNTIME_ERROR,
            runResult.durationMs(),
            nullIfEmpty(runResult.stdout()),
            nullIfEmpty(runResult.stderr()),
            runResult.stdoutTruncated(),
            runResult.stderrTruncated()
        ));
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
        try {
            var source = workspace.resolve(runtimes.get(request.language()).sourceFile());
            var runner = workspace.resolve("runner.py");
            Files.writeString(source, request.sourceCode(), StandardCharsets.UTF_8);
            Files.writeString(runner, SANDBOX_RUNNER, StandardCharsets.UTF_8);
            if (runtimes.get(request.language()).compilationRequired()) {
                var compiler = workspace.resolve("compiler.py");
                Files.writeString(compiler, JAVA_COMPILER, StandardCharsets.UTF_8);
                setRuntimeReadablePermissions(workspace, compiler, compiler);
            }
            setRuntimeReadablePermissions(workspace, source, runner);
            return workspace;
        } catch (IOException | RuntimeException exception) {
            deleteWorkspace(workspace);
            throw exception;
        }
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

    private List<String> createCommand(String containerName, Path workspace, SandboxRequest request, List<String> programCommand) {
        var runtime = properties.runtime();
        var command = new ArrayList<String>(List.of(
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
            runtimes.get(request.language()).image(),
            "python3", "-I", "-B", "/workspace/runner.py", Integer.toString(request.timeLimitMs())
        ));
        command.addAll(programCommand);
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

    private static SandboxResult logExecution(
        SandboxRequest request,
        DockerCommandResult runResult,
        SandboxResult result
    ) {
        log.info(
            "Sandbox execution completed: executionId={}, exitCode={}, timeout={}, durationMs={}, status={}",
            request.executionId(),
            runResult.exitCode(),
            runResult.timedOut(),
            runResult.durationMs(),
            result.status()
        );
        return result;
    }

    private static void logInfrastructureFailure(UUID executionId, String stage) {
        log.warn("Sandbox infrastructure failure: executionId={}, stage={}", executionId, stage);
    }

    private record ContainerState(int exitCode, boolean oomKilled) {
    }
}
