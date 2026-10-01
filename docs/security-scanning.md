# Production image security gate

The existing CI Docker job builds the final stage of `Dockerfile` on pull requests
to `main` and pushes to `main`/`develop`, then scans the locally loaded image tagged
with the commit SHA. The builder stage and source tree are not scan targets. The separate Python
sandbox image remains covered by the existing sandbox integration job; this gate
checks the worker service runtime image.
On `main`, GHCR login and publication happen only after a successful scan; the
same loaded image is pushed without rebuilding. Deployment depends on that job.

Trivy action v0.36.0 is pinned to commit
`ed142fd0673e97e23eac54620cfb913e5ce36c25`; the scanner is fixed at v0.70.0.
OS, Java, and shipped Docker CLI library HIGH/CRITICAL findings fail CI, including findings
without fixes. Vulnerability tables appear in the job log and step summary even
when the gate fails. Only the vulnerability scanner runs, so reports do not
include secret contents. There is no ignore list or `ignore-unfixed` suppression.

The action caches the vulnerability and Java databases while allowing Trivy to
refresh them. CI logs record the image ID, scanner version, and DB metadata.
Findings can change when upstream advisories change. To reproduce
an investigation exactly, retain the image (or its immutable digest), scanner
version, and vulnerability/Java DB cache with its metadata. Do not freeze an old
DB permanently in CI. Docker base tags are refreshed at build time with `pull`.

## Local verification

Using Trivy v0.70.0 and actionlint:

```sh
actionlint .github/workflows/ci.yml
docker build --pull -t tutorplatform-execution-worker:security .
trivy image --image-src docker --scanners vuln --pkg-types os,library \
  --severity HIGH,CRITICAL --exit-code 1 --ignore-unfixed=false \
  --timeout 10m tutorplatform-execution-worker:security
```

If an upstream finding cannot be patched in this change, record the CVE, package,
installed/fixed versions, upstream link, and why an upgrade must be separate.
Any proposed exception must be scoped to that CVE and package path, include an
owner and expiry, and retain visibility in a separate unfiltered report.
Do not add a global severity bypass or blanket unfixed-vulnerability suppression.

