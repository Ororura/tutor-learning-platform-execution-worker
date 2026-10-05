# Execution Worker

Internal, separately deployable boundary for isolated user-code execution.

`POST /internal/v1/executions` validates the request and executes each Python or Java test in a separate,
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
does not validate either runtime image or execution resources: provision the pinned image and workspace
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

Real sandbox integration tests (requires Docker and the configured runtime images):

```bash
docker build -f Dockerfile.runtime-java -t tutor-java-runtime:21 .
./gradlew sandboxIntegrationTest
```

## Java 21 tasks

`LanguageRuntimes` selects source filename, image and command in one registry. Python keeps the
existing image and `main.py`. Java uses `Main.java` and the dedicated JDK 21 image above; override
`EXECUTION_RUNTIME_JAVA_IMAGE` with a provisioned immutable image reference in deployments.
Build/provision that image on the Docker daemon used by the worker before enabling Java tasks.
Platform local Compose and frontend E2E Compose build it automatically; worker CI builds it before
sandbox integration tests. Existing production deployment must provision this additional runtime image.

Each request prepares one isolated read-only workspace. Java compiles once with `javac --release 21`
inside a container with the same network, memory, CPU, PID, filesystem and output isolation.
Compilation has a separate 10-second deadline. Annotation processing is disabled. Class files are
packed into a bounded JAR inside the sandbox; only its bounded encoded bytes cross to the worker,
which writes the archive without unpacking it. Source, supervisor and archive share the configured
workspace byte budget. Each test runs `java -cp /workspace/program.jar Main` in a fresh container
with independent stdin, processes, temporary filesystem and timeout. Comparison and statuses are
shared with Python. All containers and the workspace are removed, including failed compilations.

Use a public or package-private `Main` with `public static void main(String[] args)` and no package.
Standard JDK libraries are available; dependencies, Maven/Gradle projects and interactive input are
not supported. The default 128 MB task budget supports the sample programs; very small budgets can
fail JVM startup. JVM heap is smaller than the container memory limit to reserve native memory.
Compiler diagnostics are bounded and remove sandbox path prefixes. Compilation errors use
`RUNTIME_ERROR`; infrastructure failures use `SYSTEM_ERROR`. Hidden-test redaction remains the
backend's responsibility because the worker contract intentionally has no visibility flag.


## Immutable production delivery

See [production delivery](docs/production-delivery.md) for exact image selection,
smoke verification, deployed SHA inspection and manual rollback.
