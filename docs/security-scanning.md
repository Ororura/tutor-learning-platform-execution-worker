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


## Initial baseline (2026-10-01)

The original production runtime image had six CRITICAL and 21 HIGH findings,
all in shipped Java dependencies. Update Spring Boot within the 3.5 maintenance
line from 3.5.5 to 3.5.16 to pick up patched Spring Framework and Micrometer.
Tomcat and Jackson need explicit patch overrides newer than that Boot BOM.

| Runtime package | Original | Patched | Findings |
| --- | --- | --- | --- |
| `com.fasterxml.jackson.core:jackson-core` | 2.19.2 | 2.21.7 | 1 HIGH |
| `com.fasterxml.jackson.core:jackson-databind` | 2.19.2 | 2.21.7 | 5 HIGH |
| `io.micrometer:micrometer-core` | 1.15.3 | 1.15.12 | 2 HIGH |
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.44 | 10.1.60 | 6 CRITICAL, 8 HIGH |
| `org.springframework.boot:spring-boot` | 3.5.5 | 3.5.16 | 1 HIGH |
| `org.springframework:spring-core` | 6.2.10 | 6.2.19 | 1 HIGH |
| `org.springframework:spring-expression` | 6.2.10 | 6.2.19 | 1 HIGH |
| `org.springframework:spring-webmvc` | 6.2.10 | 6.2.19 | 2 HIGH |

Baseline advisory IDs (no findings are suppressed):

- `com.fasterxml.jackson.core:jackson-core`: GHSA-r7wm-3cxj-wff9.
- `com.fasterxml.jackson.core:jackson-databind`: CVE-2026-54512, CVE-2026-54513, CVE-2026-68497, CVE-2026-91776, CVE-2026-91777.
- `io.micrometer:micrometer-core`: CVE-2026-40983, CVE-2026-40984.
- `org.apache.tomcat.embed:tomcat-embed-core`: CVE-2026-41293, CVE-2026-43512, CVE-2026-43515, CVE-2026-65182, CVE-2026-65905, CVE-2026-68525, CVE-2025-55752, CVE-2026-24734, CVE-2026-24880, CVE-2026-34483, CVE-2026-34487, CVE-2026-41284, CVE-2026-42498, CVE-2026-43513.
- `org.springframework.boot:spring-boot`: CVE-2026-40973.
- `org.springframework:spring-core`: CVE-2025-41249.
- `org.springframework:spring-expression`: CVE-2026-41850.
- `org.springframework:spring-webmvc`: CVE-2026-41842, CVE-2026-41845.

Remove the Tomcat/Jackson overrides once the Spring Boot BOM supplies at least
these patched versions. No allow-list is needed.

## CVE-2026-47884 remediation (2026-10-06)

The Java-support PR passed its unit and Docker sandbox integration checks, but
the production image scan found CVE-2026-47884 in `spring-webmvc:6.2.19`.
The [Spring advisory](https://spring.io/security/cve-2026-47884/) lists 6.2.20
as enterprise-only and 7.0.9 as the open-source fix. Rather than mixing Spring
Framework 7 into Boot 3 or suppressing the finding, the worker uses the coherent
Spring Boot 4.0.8 BOM, which manages Framework 7.0.9 and Tomcat 11.0.24.
The old Tomcat 10/Jackson 2 overrides are replaced by compatible patch overrides
for Tomcat 11.0.25 and Jackson 3.1.7. Scanning the unmodified Boot 4 BOM found
three Tomcat and five Jackson HIGH/CRITICAL findings:

- Tomcat: CVE-2026-65182, CVE-2026-65905, CVE-2026-68525.
- Jackson core: CVE-2026-89407, CVE-2026-89425.
- Jackson databind: CVE-2026-68497, CVE-2026-91776, CVE-2026-91777.

Remove these overrides when the Boot BOM manages at least those patched versions.

Boot 4's MVC and actuator test starters and relocated health/metrics APIs replace
their Boot 3 equivalents. Jackson 3 uses `spring.jackson.use-jackson2-defaults`
to preserve the existing wire behavior. The API test compares the complete JSON
execution response strictly, including UUIDs, enum values and null diagnostics,
for both Python and Java. Existing validation, health/readiness, Prometheus and
real sandbox tests continue to verify the worker boundaries.

The production image security gate and its HIGH/CRITICAL policy remain unchanged;
no vulnerability exception is introduced. Backend and frontend framework
versions are unaffected by this worker-only update.
