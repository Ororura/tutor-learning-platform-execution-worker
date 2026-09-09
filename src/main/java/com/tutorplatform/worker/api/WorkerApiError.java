package com.tutorplatform.worker.api;

import java.time.Instant;

record WorkerApiError(String code, String message, Instant timestamp) {
}
