package com.tutorplatform.worker.infrastructure.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DockerReadinessCommandTest {
    @TempDir
    Path directory;
    private final DockerCommandRunner runner = new DockerCommandRunner();

    @Test
    void successfulDaemonCommandDiscardsOutputAndDiagnostics() throws Exception {
        var executable = script("printf 'private-daemon-data'; printf 'SECRET=private' >&2; exit 0");
        assertThat(runner.checkReadiness(List.of(executable.toString()), Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void failedDaemonCommandIsUnavailable() throws Exception {
        var executable = script("printf 'private connection error' >&2; exit 1");
        assertThat(runner.checkReadiness(List.of(executable.toString()), Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void missingExecutableIsAnInfrastructureFailure() {
        assertThatThrownBy(() -> runner.checkReadiness(
            List.of(directory.resolve("missing-docker").toString()), Duration.ofSeconds(1)
        )).isInstanceOf(IOException.class);
    }

    @Test
    void stalledCommandIsBoundedAndTerminated() throws Exception {
        var executable = script("echo $$ > \"$1\"; exec sleep 30");
        var pidFile = directory.resolve("pid");
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThat(runner.checkReadiness(
                List.of(executable.toString(), pidFile.toString()), DockerRuntimeHealthIndicator.TIMEOUT
            )).isFalse();
            var process = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()));
            if (process.isPresent()) {
                process.get().onExit().get(1, TimeUnit.SECONDS);
                assertThat(process.get().isAlive()).isFalse();
            }
        });
    }

    private Path script(String body) throws IOException {
        var path = directory.resolve("docker-stub");
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }
}
