package com.tutorplatform.worker.infrastructure.docker;

import com.tutorplatform.worker.application.*;
import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("sandbox-integration")
class JavaSandboxRuntimeIntegrationTest {
    @TempDir Path workspace;

    @Test void helloWorldAndPackagePrivateMainUseJava21() throws Exception {
        assertThat(execute("class Main { public static void main(String[] args) { System.out.println(Runtime.version().feature()); } }", "", "21", 5000, 1024).status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(execute(program("System.out.println(\"Hello, World!\");"), "", "Hello, World!", 5000, 1024).status()).isEqualTo(ExecutionStatus.PASSED);
    }

    @Test void scannerBufferedReaderAndSystemInReceiveStdin() throws Exception {
        assertThat(execute(program("int n = new java.util.Scanner(System.in).nextInt(); System.out.println(n*n);"), "5\n", "25", 5000, 1024).status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(execute(program("System.out.println(new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine());"), "hello\n", "hello", 5000, 1024).status()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(execute(program("System.out.write(System.in.readAllBytes());"), "bytes", "bytes", 5000, 1024).status()).isEqualTo(ExecutionStatus.PASSED);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void compilesOnceAndRunsTestsWithIndependentProcessesAndFilesystems() throws Exception {
        var runner = spy(new DockerCommandRunner());
        var source = program("var f = java.nio.file.Path.of(\"/tmp/marker\"); System.out.println(java.nio.file.Files.exists(f)); java.nio.file.Files.writeString(f, \"x\"); System.out.println(new java.util.Scanner(System.in).nextInt());");
        var outcome = service(runner).execute(new ExecutionCommand(UUID.randomUUID(), ExecutionLanguage.JAVA, source, 5000, 128, 1024,
            List.of(test("5", "false\n5"), test("-3", "false\n-3"))));
        assertThat(outcome.status()).as(outcome.toString()).isEqualTo(ExecutionStatus.PASSED);
        assertThat(outcome.passedTests()).isEqualTo(2);
        var commands = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(runner, atLeastOnce()).run(commands.capture(), any(), any(), anyInt());
        assertThat(commands.getAllValues().stream().filter(c -> c.contains("/workspace/compiler.py"))).hasSize(1);
        for (var command : commands.getAllValues().stream().filter(c -> c.contains("create")).toList()) {
            assertThat(command).contains("--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--pids-limit", "--memory", "128m",
                "--memory-swap", "--cpus", "--user", "65534:65534", "--tmpfs");
            assertThat(command).noneMatch(argument -> argument.toString().contains("docker.sock") || argument.toString().contains("DATASOURCE"));
        }
        assertClean();
    }

    @Test void wrongOutputAndNormalizedComparisonSharePythonComparator() throws Exception {
        assertThat(execute(program("System.out.println(\"wrong\");"), "", "right", 5000, 1024).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execute(program("System.out.print(\"25  \\r\\n\\n\");"), "", "25", 5000, 1024).status()).isEqualTo(ExecutionStatus.PASSED);
    }

    @Test void compilationAndRuntimeFailuresAreUserErrorsWithSafeDiagnostics() throws Exception {
        var compile = execute(program("System.out.println(1)"), "", "", 5000, 1024);
        assertThat(compile.status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
        assertThat(compile.stderrExcerpt()).contains("Main.java:", "error:").doesNotContain("/workspace", "/tmp", workspace.toString(), "compiler.py", "runner.py");
        assertThat(compile.testResults()).isEmpty();
        var runtime = execute(program("throw new IllegalStateException(\"boom\");"), "", "", 5000, 1024);
        assertThat(runtime.status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
        assertThat(runtime.stderrExcerpt()).contains("boom").doesNotContain("/workspace", workspace.toString());
        assertThat(execute("package forbidden; " + program(""), "", "", 5000, 1024).status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
    }

    @Test void infiniteLoopIsTimedOutAndOutputAndCompilerDiagnosticsAreBounded() throws Exception {
        assertThat(execute(program("while (true) {}"), "", "", 300, 64).status()).isEqualTo(ExecutionStatus.TIMEOUT);
        var output = execute(program("System.out.print(\"x\".repeat(100000)); System.err.print(\"é\".repeat(100000));"), "", "x".repeat(64), 5000, 64);
        assertThat(output.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(output.stdoutExcerpt().getBytes(StandardCharsets.UTF_8)).hasSize(64);
        assertThat(output.stderrExcerpt().getBytes(StandardCharsets.UTF_8)).hasSize(64);
        var compile = execute(program("badcode"), "", "", 5000, 64);
        assertThat(compile.stderrExcerpt().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
    }

    @Test void networkAndWorkspaceRemainIsolated() throws Exception {
        var source = program("""
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("/workspace/escape"), "x"); System.out.println("writable"); }
            catch (java.io.IOException e) { System.out.println("readonly"); }
            System.out.println(System.getenv("SPRING_DATASOURCE_PASSWORD"));
            try (var s = new java.net.Socket()) { s.connect(new java.net.InetSocketAddress("1.1.1.1", 80), 200); System.out.println("network"); }
            catch (java.io.IOException e) { System.out.println("isolated"); }
            """);
        assertThat(execute(source, "", "readonly\nnull\nisolated", 5000, 1024).status()).isEqualTo(ExecutionStatus.PASSED);
    }

    private ExecutionOutcome execute(String source, String input, String output, int timeout, int limit) throws Exception {
        var result = service(new DockerCommandRunner()).execute(new ExecutionCommand(UUID.randomUUID(), ExecutionLanguage.JAVA, source, timeout, 128, limit, List.of(test(input, output))));
        assertClean();
        return result;
    }
    private ExecutionService service(DockerCommandRunner runner) {
        var properties = new ExecutionWorkerProperties(new ExecutionWorkerProperties.Worker(1, Duration.ofMillis(50), 100),
            new ExecutionWorkerProperties.Runtime("docker", "python:unused", "tutor-java-runtime:21", 1, 32, 4096,
                1048576, 16777216, Duration.ofSeconds(10), Duration.ofSeconds(2), workspace, null));
        return new ExecutionService(new DockerSandboxRuntime(runner, properties), new OutputComparator(), properties);
    }
    private void assertClean() throws Exception { try (var files = Files.list(workspace)) { assertThat(files).isEmpty(); } }
    private static ExecutionCommand.TestCase test(String input, String output) {
        return new ExecutionCommand.TestCase(UUID.randomUUID(), input, output, ComparisonMode.NORMALIZED);
    }
    private static String program(String body) {
        return "public class Main { public static void main(String[] args) throws Exception { " + body + " } }";
    }
}
