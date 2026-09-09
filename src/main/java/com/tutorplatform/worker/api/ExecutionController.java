package com.tutorplatform.worker.api;

import com.tutorplatform.worker.application.ExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/executions")
class ExecutionController {
    private final ExecutionService executionService;
    private final ExecutionRequestPolicy requestPolicy;

    ExecutionController(ExecutionService executionService, ExecutionRequestPolicy requestPolicy) {
        this.executionService = executionService;
        this.requestPolicy = requestPolicy;
    }

    @PostMapping
    ResponseEntity<WorkerExecutionResponse> execute(@Valid @RequestBody WorkerExecutionRequest request) {
        requestPolicy.validate(request);
        return ResponseEntity.ok(WorkerExecutionResponse.from(executionService.execute(request.toCommand())));
    }
}
