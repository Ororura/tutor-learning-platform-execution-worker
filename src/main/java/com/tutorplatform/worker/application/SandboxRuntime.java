package com.tutorplatform.worker.application;

/** Isolated runtime boundary. Implementations must not execute user code in the worker JVM. */
public interface SandboxRuntime {

    SandboxResult execute(SandboxRequest request);
}
