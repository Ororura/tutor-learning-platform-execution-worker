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

## Health probes

| Endpoint | Purpose | Checks |
| --- | --- | --- |
| `/actuator/health/liveness` | Whether the worker application is alive | Spring application liveness state only; no Docker calls |
| `/actuator/health/readiness` | Whether the worker can accept execution requests | Spring readiness state and Docker CLI/daemon connectivity |
| `/actuator/prometheus` | Prometheus metrics scrape | Existing JVM and application metrics; no Docker calls |

Readiness runs the configured Docker CLI's `version` command with a constant output format,
using the same Docker socket/context as task execution. The CLI process has a fixed one-second
timeout and is forcibly terminated on timeout or interruption. All stdout/stderr is discarded;
failures return `DOWN` without logging exceptions, environment values or daemon metadata.
Missing CLI, socket permission errors, unreachable daemon and timeouts produce HTTP 503.
Liveness stays independent of Docker outages, so a daemon outage does not trigger worker restarts.
Spring's refusing-traffic state also makes readiness return HTTP 503 during shutdown.
Probe responses contain only `status`; details and component lists remain hidden in all profiles.

Health checks never call `SandboxRuntime`, execute user code, create containers/workspaces, pull
images, or query the core database. The worker has no datasource/JDBC dependency or DB health check;
do not supply it with `SPRING_DATASOURCE_*` settings or database credentials. Connectivity readiness
does not validate the Python image or execution resources: provision the pinned image and workspace
mount before accepting tasks. A successful probe is not a full sandbox execution test.

The Docker image healthcheck polls readiness every 10 seconds, with a two-second HTTP limit,
three-second Docker healthcheck timeout, 30-second startup grace and three retries. Platform Compose
inherits this healthcheck from the image; the frontend E2E Compose setup already polls the same
readiness endpoint. Compose consumers can use `depends_on: {execution-worker: {condition:
service_healthy}}` when they must wait for readiness. Keep worker port 8090 private, attach the
trusted Docker control socket and the shared workspace volume as in platform Compose, and never
mount the socket into user containers. For network isolation from Postgres, put backend and worker
on a dedicated execution network and attach only backend and Postgres to the database network;
the platform's current default shared network does not provide this network isolation.

Fast tests:

```bash
./gradlew test
```

Real sandbox integration tests (requires Docker and the configured Python image):

```bash
./gradlew sandboxIntegrationTest
```

## Immutable production delivery

See [production delivery](docs/production-delivery.md) for exact image selection,
smoke verification, deployed SHA inspection and manual rollback.
