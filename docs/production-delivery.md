# Execution-Worker production delivery

GHCR repository: `ghcr.io/ororura/tutorplatform-execution-worker`.

## Artifact and deployment contract

The workflow builds once, scans the loaded runtime image with Trivy (HIGH/CRITICAL,
including unfixed findings), then pushes the same image as `latest` and the full
commit SHA. Production and demo deploy `:<sha>@sha256:<digest>` from that successful
workflow. The digest also protects deployment against a tag changing on a rerun.
OCI labels record source repository and revision; logs and the Actions summary
record commit, image repository, tag, digest and environment.

Each deployment changes only `execution-worker` using `pull` and
`up -d --no-deps --no-build --pull never --wait --wait-timeout 120`.
The two existing stack directories remain `/opt/tutorplatform` and
`/opt/tutorplatform-demo`. All three repositories take the same per-stack
`.delivery.lock` (bounded 180-second wait), preventing concurrent state updates.

The first deployment snapshots **running image digests** for all three applications
and writes a nonsecret, M16-managed `compose.override.yml` (JSON is valid YAML).
This file pins every application; normal `docker compose` also loads it automatically.
Existing server environments, networks, ports, databases, volumes and Caddy remain
in the base `compose.yml`. An existing unmanaged override is rejected for manual
integration. Local development Compose is unchanged.

The override adds bounded HTTP healthchecks for the applications. Deployment also
requires PostgreSQL health, backend's healthy PostgreSQL dependency and frontend's
healthy backend dependency, and rejects externally published PostgreSQL/worker
ports. Existing restart policies remain; bounded health/smoke verification exposes
failures instead of reporting success during a restart loop.

## Inspect and smoke

On the VPS, choose the stack directory:

```bash
cd /opt/tutorplatform
docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q execution-worker)"
cat .delivery-history.json
python3 ops/execution-worker/smoke_deployment.py \
  --directory /opt/tutorplatform \
  --frontend-url http://127.0.0.1:3000 \
  --backend-url http://127.0.0.1:8080 \
  --public-base-url https://tutor.ororura.site
```

The same checker is available locally as `python3 scripts/smoke_deployment.py`.
`FRONTEND_URL`, `BACKEND_URL`, `PUBLIC_BASE_URL` and optional `WORKER_URL`
can replace URL arguments. Without `WORKER_URL`, readiness runs through
`docker compose exec -T execution-worker curl` inside the worker container.
Do not publish worker or database ports for checks.

Checks use credential-free GETs: local frontend, backend readiness, worker readiness,
public frontend, and `/api/v1/public/registration-settings` through the proxy.
Readiness requires HTTP 200 and JSON `status=UP`; the API must return its expected
registration settings schema. No login, sessions, mutations or user-code executions
are involved. Curl ignores `.curlrc`, verifies TLS and has bounded redirects,
response size and request duration. Logs show status and allowlisted health tokens,
never cookies, arbitrary response bodies or environment values.

Defaults: 12 attempts, 5 seconds/request, 5 seconds between attempts, a 120-second
smoke deadline, plus at most 5 seconds for failure container diagnostics.
Override with `--attempts`, `--request-timeout`, `--delay`, `--timeout`.
Failure exits nonzero, prints safe container states, and keeps the attempted pin
and history entry marked `verified=false`. There is no automatic rollback or cleanup.

## Manual rollback

**Check that applied database migrations are backward-compatible with the selected
old application image before rolling back.** Stop/suspend overlapping delivery jobs
while selecting the previous version. Use the previous successful SHA from Actions
or `.delivery-history.json`, and the service's installed script:

```bash
PREVIOUS_SHA=<full-40-character-commit-sha>
python3 /opt/tutorplatform/ops/execution-worker/deploy_image.py \
  --directory /opt/tutorplatform --environment production \
  --sha "$PREVIOUS_SHA" \
  --frontend-url http://127.0.0.1:3000 \
  --backend-url http://127.0.0.1:8080 \
  --public-base-url https://tutor.ororura.site
```

For an exact previously recorded artifact, replace `--sha "$PREVIOUS_SHA"` with
`--rollback-reference '<previous reference from .delivery-history.json>'`.
This supports the initial legacy snapshot that has a digest but no known SHA.
An optional `--digest sha256:...` with `--sha` also pins a known workflow artifact.
The script defaults to `execution-worker`; other installed service scripts default to their
own service. Every rollback pulls that service's selected reference, waits for health,
and runs the same smoke checks. It never rolls database migrations back, removes
volumes, deletes S3 objects, runs `down`, or prunes previous images.

For demo, use directory `/opt/tutorplatform-demo`, `--environment demo`, frontend
port `3001`, backend port `8081`, and `https://demo.ororura.site` in both commands.

## CI and VPS prerequisites

Existing GitHub secrets: `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_KEY`.
Optional repository variables: `PRODUCTION_PUBLIC_BASE_URL`, `DEMO_PUBLIC_BASE_URL`;
the defaults above were verified on adminvps. All repositories must deploy to the
same host/user and stack paths. The user needs Docker access, registry pull access,
and write access to stack directories and `ops/execution-worker`. The workflow installs its
operational scripts there; no extra secret or deployment environment file is needed.
The audited `adminvps` login (`ororura`) cannot write the root-owned stack directories.
Before first rollout, an administrator should confirm the actual `SERVER_USER` is
in the existing `deploy` group, then grant that group directory write access:

```bash
sudo chgrp deploy /opt/tutorplatform /opt/tutorplatform-demo
sudo chmod g+w /opt/tutorplatform /opt/tutorplatform-demo
```

These commands are nonrecursive; they do not change existing file or volume owners.
Do not use world-writable permissions. No VPS permission changes were made by this PR.

Python 3.8+, host curl, Docker Compose with `--wait`, backend wget and worker curl
are required. Stack bootstrap needs one existing container with a GHCR digest for
each application. Keep earlier images/tags available in GHCR for rollback.

Run `python3 -m compileall -q scripts tests/ops` and
`python3 -m unittest discover -s tests/ops -v`. CI also validates Actions YAML and
embedded shell with actionlint 1.7.12. Mock HTTP tests cover each failure, malformed
health, HTTP-200/DOWN, retries, request/global timeouts and finite attempts; mocked
Docker tests cover pins, manual rollback, failure history and configuration refusal.
Real Compose rendering covers the production/demo relationships observed on VPS.
