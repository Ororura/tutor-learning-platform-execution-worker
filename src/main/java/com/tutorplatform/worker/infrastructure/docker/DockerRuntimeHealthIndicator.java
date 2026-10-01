package com.tutorplatform.worker.infrastructure.docker;

import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Component("dockerRuntimeHealthIndicator")
class DockerRuntimeHealthIndicator implements HealthIndicator {
    static final Duration TIMEOUT = Duration.ofSeconds(1);
    private final DockerCommandRunner commandRunner;
    private final ExecutionWorkerProperties properties;

    DockerRuntimeHealthIndicator(DockerCommandRunner commandRunner, ExecutionWorkerProperties properties) {
        this.commandRunner = commandRunner;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            // Unlike --version, version contacts the daemon using the same CLI context as task execution.
            // Only a constant is formatted; no containers, images, workspaces or user code are touched.
            var available = commandRunner.checkReadiness(
                List.of(properties.runtime().dockerExecutable(), "version", "--format={{if .Server}}ready{{end}}"),
                TIMEOUT
            );
            return available ? Health.up().build() : Health.down().build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        } catch (IOException | RuntimeException exception) {
            // Exception messages and Docker stderr may contain private paths or connection data.
            return Health.down().build();
        }
    }
}
