package com.tutorplatform.worker.application;

/** Isolated runtime boundary. Implementations must not execute user code in the worker JVM. */
public interface SandboxRuntime {

    SandboxResult execute(SandboxRequest request);

    default PreparedExecution prepare(SandboxRequest request) {
        return new PreparedExecution() {
            public SandboxResult preparationResult() { return null; }
            public SandboxResult execute(String stdin) {
                return SandboxRuntime.this.execute(new SandboxRequest(request.executionId(), request.language(),
                    request.sourceCode(), stdin, request.timeLimitMs(), request.memoryLimitMb(), request.outputLimitBytes()));
            }
            public void close() { }
        };
    }

    interface PreparedExecution extends AutoCloseable {
        /** Null means ready; a result means preparation failed before any test ran. */
        SandboxResult preparationResult();
        SandboxResult execute(String stdin);
        void close();
    }
}
