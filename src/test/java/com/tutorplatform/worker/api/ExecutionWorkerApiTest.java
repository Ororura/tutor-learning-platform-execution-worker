package com.tutorplatform.worker.api;

import com.tutorplatform.worker.ExecutionWorkerApplication;
import com.tutorplatform.worker.application.SandboxResult;
import com.tutorplatform.worker.application.SandboxRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ExecutionWorkerApplication.class)
@AutoConfigureMockMvc
class ExecutionWorkerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SandboxRuntime sandboxRuntime;

    @BeforeEach
    void stubRuntime() {
        given(sandboxRuntime.execute(any())).willReturn(new SandboxResult(
            SandboxResult.Status.COMPLETED, 42, "hello\n", null, false, false
        ));
    }

    @Test
    void internalEndpointExecutesThroughSandboxBoundary() throws Exception {
        var executionId = UUID.randomUUID();
        var testCaseId = UUID.randomUUID();

        mockMvc.perform(post("/internal/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest(executionId, testCaseId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionId").value(executionId.toString()))
            .andExpect(jsonPath("$.status").value("PASSED"))
            .andExpect(jsonPath("$.passedTests").value(1))
            .andExpect(jsonPath("$.totalTests").value(1))
            .andExpect(jsonPath("$.testResults[0].testCaseId").value(testCaseId.toString()));
    }

    @Test
    void malformedRequestReturnsControlledBadRequestWithoutStartingRuntime() throws Exception {
        mockMvc.perform(post("/internal/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_REQUEST"))
            .andExpect(jsonPath("$.message").value("Execution request is invalid"));

        verifyNoInteractions(sandboxRuntime);
    }

    @Test
    void unsupportedLanguageReturnsControlledBadRequestWithoutStartingRuntime() throws Exception {
        var request = validRequest(UUID.randomUUID(), UUID.randomUUID()).replace("PYTHON", "RUBY");

        mockMvc.perform(post("/internal/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_REQUEST"));

        verifyNoInteractions(sandboxRuntime);
    }

    @Test
    void unsupportedComparisonModeReturnsControlledBadRequestWithoutStartingRuntime() throws Exception {
        var request = validRequest(UUID.randomUUID(), UUID.randomUUID()).replace("NORMALIZED", "FUZZY");

        mockMvc.perform(post("/internal/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_REQUEST"));

        verifyNoInteractions(sandboxRuntime);
    }

    @Test
    void limitsOutsideContractReturnControlledBadRequest() throws Exception {
        assertInvalid(validRequest(UUID.randomUUID(), UUID.randomUUID()).replace("5000", "99"));
        assertInvalid(validRequest(UUID.randomUUID(), UUID.randomUUID()).replace("5000", "30001"));
        assertInvalid(validRequest(UUID.randomUUID(), UUID.randomUUID()).replace("128", "15"));
        assertInvalid(validRequest(UUID.randomUUID(), UUID.randomUUID()).replace("128", "1025"));
    }

    private void assertInvalid(String request) throws Exception {
        mockMvc.perform(post("/internal/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_REQUEST"));
    }

    private static String validRequest(UUID executionId, UUID testCaseId) {
        return """
            {
              "executionId": "%s",
              "language": "PYTHON",
              "sourceCode": "print(input())",
              "timeLimitMs": 5000,
              "memoryLimitMb": 128,
              "outputLimitBytes": 16384,
              "testCases": [{
                "id": "%s",
                "inputText": "hello",
                "expectedOutput": "hello",
                "comparisonMode": "NORMALIZED"
              }]
            }
            """.formatted(executionId, testCaseId);
    }
}
