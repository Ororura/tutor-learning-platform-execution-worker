package com.tutorplatform.worker.infrastructure.docker;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
class DockerCommandRunner {

    DockerCommandResult run(List<String> command, String stdin, Duration timeout, int outputMaxBytes)
        throws IOException, InterruptedException {
        var startedAt = System.nanoTime();
        var process = new ProcessBuilder(command).start();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stdout = executor.submit(new BoundedStreamCollector(process.getInputStream(), outputMaxBytes));
            var stderr = executor.submit(new BoundedStreamCollector(process.getErrorStream(), outputMaxBytes));
            var input = executor.submit(() -> {
                try (var target = process.getOutputStream()) {
                    if (stdin != null) {
                        target.write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // A program may close stdin or exit before consuming all input.
                }
            });

            var completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            input.get(2, TimeUnit.SECONDS);
            var capturedStdout = stdout.get(2, TimeUnit.SECONDS);
            var capturedStderr = stderr.get(2, TimeUnit.SECONDS);
            return new DockerCommandResult(
                completed ? process.exitValue() : -1,
                !completed,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                capturedStdout.value(),
                capturedStderr.value(),
                capturedStdout.truncated(),
                capturedStderr.truncated()
            );
        } catch (ExecutionException exception) {
            throw new IOException("Failed to collect Docker command output", exception.getCause());
        } catch (java.util.concurrent.TimeoutException exception) {
            process.destroyForcibly();
            throw new IOException("Docker command streams did not close", exception);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
