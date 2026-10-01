package com.tutorplatform.worker.infrastructure.docker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.slf4j.LoggerFactory.getLogger;

class DockerRuntimeHealthIndicatorTest {
    private final DockerCommandRunner runner = mock(DockerCommandRunner.class);

    @Test
    void privateInfrastructureFailuresAreDownWithoutDetailsOrLogs() throws Exception {
        given(runner.checkReadiness(any(), any()))
            .willThrow(new IOException("SECRET=private-daemon-connection"))
            .willThrow(new IllegalStateException("SECRET=private-docker-environment"));
        var logger = (Logger) getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (var attempt = 0; attempt < 2; attempt++) {
                var health = indicator().health();
                assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.getDetails()).isEmpty();
            }
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void interruptionReturnsDownAndRestoresInterruptFlag() throws Exception {
        given(runner.checkReadiness(any(), any())).willThrow(new InterruptedException("private-data"));
        try {
            var health = indicator().health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(runner).checkReadiness(
                List.of("configured-docker", "version", "--format={{if .Server}}ready{{end}}"),
                Duration.ofSeconds(1)
            );
            verifyNoMoreInteractions(runner);
        } finally {
            Thread.interrupted();
        }
    }

    private DockerRuntimeHealthIndicator indicator() {
        var properties = mock(ExecutionWorkerProperties.class);
        var runtime = mock(ExecutionWorkerProperties.Runtime.class);
        given(properties.runtime()).willReturn(runtime);
        given(runtime.dockerExecutable()).willReturn("configured-docker");
        return new DockerRuntimeHealthIndicator(runner, properties);
    }
}
