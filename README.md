# Execution Worker

Internal, separately deployable boundary for isolated user-code execution.

`POST /internal/v1/executions` validates the request and executes each Python test in a separate,
ephemeral Docker container through the `SandboxRuntime` boundary. The runtime uses a fixed image,
no network, a read-only root filesystem, a minimal read-only workspace, an allow-listed child
environment, dropped capabilities, and CPU, memory, PID, tmpfs, wall-time, and output limits.
Untrusted code is never executed in the Spring Boot backend JVM or as its child process.

The worker receives no core datasource configuration and owns no learning-platform data. It is
reachable only on the private Compose network; no user session, OAuth, or JWT is used for the
backend-to-worker call at this stage. The trusted worker uses the Docker control socket, but that
socket and all host configuration are absent from user runtime containers.

Fast tests:

```bash
../backend/gradlew -p . test
```

Real sandbox integration tests (requires Docker and the configured Python image):

```bash
../backend/gradlew -p . sandboxIntegrationTest
```
