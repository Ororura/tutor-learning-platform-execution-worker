#!/usr/bin/env python3
"""Deploy or manually roll back one service, then verify the whole stack."""

import argparse
import copy
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

from smoke_deployment import compose_command, container_states, parser as smoke_parser, verify


DEFAULT_SERVICE = "execution-worker"
IMAGES = {name: "ghcr.io/ororura/tutorplatform-" + name for name in
          ("backend", "frontend", "execution-worker")}
MARKER = "x-m16-delivery"
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")


def run(command, directory, timeout=60):
    # Capture output: Docker config and inspect can contain environment secrets.
    result = subprocess.run(command, cwd=directory, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("Command failed: " + " ".join(command[:4]) +
                           f" (exit={result.returncode}; sensitive output suppressed)")
    return result.stdout.strip()


def atomic_json(path, value):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(value, stream, indent=2)
            stream.write("\n")
        temporary.replace(path)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def running_image(directory, service):
    ids = run([*compose_command(directory), "ps", "--all", "--quiet", service], directory).splitlines()
    if len(ids) != 1:
        raise RuntimeError(f"Expected one existing {service} container; bootstrap it before delivery")
    container = json.loads(run(["docker", "inspect", ids[0]], directory))[0]
    return container


def pinned_running_image(directory, service):
    container = running_image(directory, service)
    image = json.loads(run(["docker", "image", "inspect", container["Image"]], directory))[0]
    repository = IMAGES[service]
    for reference in image.get("RepoDigests", []):
        if reference.startswith(repository + "@") and DIGEST.fullmatch(reference.split("@", 1)[1]):
            configured = container["Config"]["Image"].split("@", 1)[0]
            if configured.startswith(repository + ":") and SHA.fullmatch(configured.rsplit(":", 1)[1]):
                return configured + "@" + reference.split("@", 1)[1]
            return reference
    raise RuntimeError(f"No registry digest found for running {service}; refusing a latest fallback")


def managed_override(directory):
    path = directory / "compose.override.yml"
    if path.exists():
        try:
            value = json.loads(path.read_text())
        except (ValueError, OSError) as error:
            raise RuntimeError("Existing compose.override.yml is not M16-managed; integrate it manually") from error
        if value.get(MARKER) != 1 or set(value.get("services", {})) != set(IMAGES):
            raise RuntimeError("Existing compose.override.yml is not M16-managed")
        for name, spec in value["services"].items():
            reference = spec.get("image", "")
            if not re.fullmatch(re.escape(IMAGES[name]) + r"(?::[0-9a-f]{40})?@sha256:[0-9a-f]{64}", reference):
                raise RuntimeError(f"Invalid pinned image for {name}")
        return value
    return {MARKER: 1, "services": {name: {"image": pinned_running_image(directory, name)}
                                  for name in IMAGES}}


def healthchecks(override):
    probes = {
        "backend": ["CMD", "wget", "-q", "-T", "4", "-O", "/dev/null",
                    "http://127.0.0.1:8080/actuator/health/readiness"],
        "execution-worker": ["CMD", "curl", "--fail", "--silent", "--max-time", "4",
                             "http://127.0.0.1:8090/actuator/health/readiness"],
        "frontend": ["CMD", "node", "-e", "const r=require('http').get({hostname:process.env.HOSTNAME||'127.0.0.1',port:3000,path:'/'},res=>{res.resume();process.exit(res.statusCode>=200&&res.statusCode<400?0:1)});r.setTimeout(4000,()=>{r.destroy();process.exit(1)});r.on('error',()=>process.exit(1));"],
    }
    for name, test in probes.items():
        override["services"][name]["healthcheck"] = {
            "test": test, "interval": "10s", "timeout": "5s", "start_period": "60s", "retries": 6}


def validate_compose(config):
    services = config["services"]
    for name in (*IMAGES, "postgres"):
        if name not in services:
            raise RuntimeError(f"Compose lacks required service: {name}")
        health = services[name].get("healthcheck", {})
        if not health.get("test") or health.get("disable") or health["test"][0] == "NONE":
            raise RuntimeError(f"Compose lacks enabled healthcheck: {name}")
    for name in ("postgres", "execution-worker"):
        for port in services[name].get("ports", []):
            if port.get("host_ip") not in ("127.0.0.1", "::1"):
                raise RuntimeError(f"Refusing externally published {name} port")
        if services[name].get("network_mode") == "host":
            raise RuntimeError(f"Refusing host networking for {name}")
    for service, dependency in (("backend", "postgres"), ("frontend", "backend")):
        if services[service].get("depends_on", {}).get(dependency, {}).get("condition") != "service_healthy":
            raise RuntimeError(f"{service} must depend on healthy {dependency}")


def deploy(args):
    directory = args.directory.resolve()
    if not (directory / "compose.yml").is_file():
        raise ValueError("directory must contain the existing compose.yml")
    rollback_reference = getattr(args, "rollback_reference", None)
    if rollback_reference:
        repository = IMAGES[args.service]
        match = re.fullmatch(re.escape(repository) + r"(?::([0-9a-f]{40}))?@(sha256:[0-9a-f]{64})", rollback_reference)
        if not match or (args.digest and args.digest != match[2]):
            raise ValueError("rollback-reference must be a pinned digest from this service's delivery history")
        args.sha, args.digest = match[1], match[2]
    elif not args.sha or not SHA.fullmatch(args.sha):
        raise ValueError("sha must be a full lowercase 40-character commit SHA")
    if args.digest and not DIGEST.fullmatch(args.digest):
        raise ValueError("digest must be sha256 followed by 64 lowercase hexadecimal characters")
    if not 1 <= args.attempts <= 60:
        raise ValueError("attempts must be between 1 and 60")
    if rollback_reference or not args.digest:
        print("Manual image selection: verify DB migrations are backward-compatible before rollback", flush=True)
    with (directory / ".delivery.lock").open("a") as lock:
        deadline = time.monotonic() + 180
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Another deployment holds the stack lock")
                time.sleep(1)
        previous = pinned_running_image(directory, args.service)
        override = managed_override(directory)
        repository = IMAGES[args.service]
        tag_reference = repository + (":" + args.sha if args.sha else "")
        reference = tag_reference + ("@" + args.digest if args.digest else "")
        print(f"environment={args.environment} commit={args.sha or 'legacy-unknown'} repository={repository} "
              f"tag={args.sha or 'legacy-unknown'} digest={args.digest or 'resolve from GHCR'} previous={previous}", flush=True)
        # Pull immutable reference before changing persistent Compose state.
        run(["docker", "pull", reference], directory, timeout=300)
        image = json.loads(run(["docker", "image", "inspect", reference], directory))[0]
        digests = [item.split("@", 1)[1] for item in image.get("RepoDigests", [])
                   if item.startswith(repository + "@")]
        digest = args.digest or (digests[0] if digests else "")
        if not DIGEST.fullmatch(digest) or digest not in digests:
            raise RuntimeError("Pulled image does not match the expected GHCR digest")
        reference = tag_reference + "@" + digest
        override["services"][args.service]["image"] = reference
        healthchecks(override)
        # Validate the candidate without exposing interpolated environment values.
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", dir=directory) as candidate:
            json.dump(override, candidate)
            candidate.flush()
            config = json.loads(run(["docker", "compose", "-f", "compose.yml", "-f", candidate.name,
                                     "config", "--format", "json"], directory))
            validate_compose(config)
        history_path = directory / ".delivery-history.json"
        history = json.loads(history_path.read_text()) if history_path.exists() else []
        entry = {"service": args.service, "environment": args.environment, "previous": previous,
                 "requested": reference, "verified": False, "time": int(time.time())}
        history.append(entry)
        atomic_json(history_path, history)
        # JSON is valid YAML. Compose automatically loads this override on later operations.
        atomic_json(directory / "compose.override.yml", override)
        print(f"Deploying {reference}; manual rollback reference={previous}", flush=True)
        run([*compose_command(directory), "pull", args.service], directory, timeout=300)
        try:
            # Allow Compose to report its own bounded health timeout before killing the CLI.
            run([*compose_command(directory), "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "120",
                 args.service], directory, timeout=135)
        except (RuntimeError, OSError, subprocess.SubprocessError):
            print(f"Container startup failed service={args.service}; collecting read-only HTTP diagnostics", flush=True)
            diagnostics = copy.copy(args)
            diagnostics.attempts = 1
            diagnostics.timeout = min(args.timeout, 15)
            verify(diagnostics)
            # Even if HTTP recovered, the failed Compose command must fail delivery.
            raise
        if verify(args):
            return 1
        container = running_image(directory, args.service)
        if (container["Image"] != image["Id"] or container["State"]["Status"] != "running"
                or container["State"].get("Health", {}).get("Status") != "healthy"):
            raise RuntimeError("Running container image/state/health does not match the requested artifact")
        entry["verified"] = True
        atomic_json(history_path, history)
        print(f"Deployment verified environment={args.environment} image={reference}", flush=True)
        return 0


def main():
    arguments = smoke_parser()
    arguments.description = __doc__
    arguments.add_argument("--service", choices=IMAGES, default=DEFAULT_SERVICE)
    selection = arguments.add_mutually_exclusive_group(required=True)
    selection.add_argument("--sha")
    selection.add_argument("--rollback-reference", help="exact previous reference from .delivery-history.json")
    arguments.add_argument("--digest")
    arguments.add_argument("--environment", choices=("production", "demo"), required=True)
    args = arguments.parse_args()
    try:
        return deploy(args)
    except (ValueError, KeyError, OSError, TypeError, RuntimeError, subprocess.SubprocessError) as error:
        # Known exceptions contain no Docker stdout/stderr; suppress other sensitive diagnostics.
        message = str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__
        print(f"Deployment failed: {message}; consult .delivery-history.json for manual rollback", file=sys.stderr)
        container_states(args.directory)
        return 1


if __name__ == "__main__":
    sys.exit(main())
