package com.tutorplatform.worker.api;

class InvalidExecutionRequestException extends RuntimeException {
    InvalidExecutionRequestException(String message) {
        super(message);
    }
}
