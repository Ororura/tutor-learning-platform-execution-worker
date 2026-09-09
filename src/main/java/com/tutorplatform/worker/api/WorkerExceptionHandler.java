package com.tutorplatform.worker.api;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
class WorkerExceptionHandler {

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        InvalidExecutionRequestException.class
    })
    ResponseEntity<WorkerApiError> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new WorkerApiError(
            "INVALID_EXECUTION_REQUEST",
            "Execution request is invalid",
            Instant.now()
        ));
    }
}
