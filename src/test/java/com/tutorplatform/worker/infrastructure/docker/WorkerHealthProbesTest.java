package com.tutorplatform.worker.infrastructure.docker;

import com.tutorplatform.worker.application.SandboxRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("prod")
class WorkerHealthProbesTest {
    private static final List<String> DOCKER_CHECK = List.of(
        "docker", "version", "--format={{if .Server}}ready{{end}}"
    );
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ApplicationContext context;
    @Autowired
    HealthContributorRegistry healthContributors;
    @MockitoBean
    DockerCommandRunner commandRunner;
    @MockitoBean
    SandboxRuntime sandboxRuntime;

    @BeforeEach
    void dockerAvailable() throws Exception {
        given(commandRunner.checkReadiness(DOCKER_CHECK, TIMEOUT)).willReturn(true);
    }

    @AfterEach
    void noSandboxOrOtherDockerOperations() {
        verifyNoInteractions(sandboxRuntime);
        verifyNoMoreInteractions(commandRunner);
        AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void livenessIsUpWithoutCheckingDocker() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    }

    @Test
    void livenessTracksBrokenApplicationStateWithoutCheckingDocker() throws Exception {
        AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"status\":\"DOWN\"}", JsonCompareMode.STRICT));
    }

    @Test
    void readinessIsUpWithOnlyDaemonCheckAndPreservesTraceId() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness").header("X-Trace-Id", "smoke-trace"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", "smoke-trace"))
            .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
        verify(commandRunner).checkReadiness(DOCKER_CHECK, TIMEOUT);
    }

    @Test
    void dockerUnavailableMakesReadinessDownButLivenessStaysUp() throws Exception {
        given(commandRunner.checkReadiness(DOCKER_CHECK, TIMEOUT)).willReturn(false);
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"status\":\"DOWN\"}", JsonCompareMode.STRICT));
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
        verify(commandRunner).checkReadiness(DOCKER_CHECK, TIMEOUT);
    }

    @Test
    void dockerFailureDoesNotExposeConnectionDetails() throws Exception {
        given(commandRunner.checkReadiness(DOCKER_CHECK, TIMEOUT))
            .willThrow(new IOException("DOCKER_HOST=private-daemon SECRET=do-not-expose"));
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"status\":\"DOWN\"}", JsonCompareMode.STRICT));
        verify(commandRunner).checkReadiness(DOCKER_CHECK, TIMEOUT);
    }

    @Test
    void readinessTracksRefusingTrafficEvenWithDockerAvailable() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"status\":\"OUT_OF_SERVICE\"}", JsonCompareMode.STRICT));
        verify(commandRunner).checkReadiness(DOCKER_CHECK, TIMEOUT);
    }

    @Test
    void aggregateHealthDoesNotExecuteSandboxOrExposeDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(content().json(
                "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}", JsonCompareMode.STRICT
            ));
        verify(commandRunner).checkReadiness(DOCKER_CHECK, TIMEOUT);
        assertThat(healthContributors.getContributor("db")).isNull();
        assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
    }

    @Test
    void prometheusRemainsAvailableEvenWhenDockerIsUnavailable() throws Exception {
        given(commandRunner.checkReadiness(DOCKER_CHECK, TIMEOUT)).willReturn(false);
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("jvm_memory_used_bytes")))
            .andExpect(content().string(containsString("application=\"tutor-execution-worker\"")));
    }

    @Test
    void sensitiveActuatorEndpointsRemainUnexposed() throws Exception {
        for (var endpoint : List.of("env", "configprops", "beans")) {
            mockMvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
        }
    }
}
