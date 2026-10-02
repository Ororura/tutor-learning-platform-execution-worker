#!/usr/bin/env python3
"""Bounded, credential-free GET checks for a deployed platform."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.parse import urlsplit


def positive(value):
    number = float(value)
    if not 0 < number <= 600:
        raise argparse.ArgumentTypeError("must be greater than 0 and at most 600")
    return number


def url(value):
    parts = urlsplit(value)
    if (parts.scheme not in ("http", "https") or not parts.hostname
            or parts.username or parts.password or parts.query or parts.fragment
            or any(c.isspace() or ord(c) < 32 for c in value)):
        raise argparse.ArgumentTypeError("use an HTTP(S) URL without credentials, query or fragment")
    try:
        parts.port
    except ValueError as error:
        raise argparse.ArgumentTypeError("invalid URL port") from error
    return value.rstrip("/")


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    for name in ("frontend", "backend", "public-base"):
        result.add_argument(f"--{name}-url", type=url,
                            default=os.environ.get(name.upper().replace("-", "_") + "_URL"),
                            required=not os.environ.get(name.upper().replace("-", "_") + "_URL"))
    result.add_argument("--worker-url", type=url, default=os.environ.get("WORKER_URL"))
    result.add_argument("--directory", type=Path, default=Path.cwd())
    result.add_argument("--attempts", type=int, default=12)
    result.add_argument("--request-timeout", type=positive, default=5)
    result.add_argument("--timeout", type=positive, default=120)
    result.add_argument("--delay", type=positive, default=5)
    return result


def compose_command(directory):
    directory = Path(directory)
    command = ["docker", "compose"]
    if (directory / "compose.yml").exists():
        command.extend(["-f", "compose.yml"])
        if (directory / "compose.override.yml").exists():
            command.extend(["-f", "compose.override.yml"])
    return command


def container_states(directory):
    """Only print operational fields; never environment, logs or full inspect."""
    try:
        result = subprocess.run([*compose_command(directory), "ps", "--all", "--format", "json"],
                                cwd=directory, capture_output=True, text=True, timeout=5, check=True)
        output = result.stdout.strip()
        rows = json.loads(output) if output.startswith("[") else [json.loads(line) for line in output.splitlines()]
        for row in rows:
            print("container " + json.dumps({key: row.get(key) for key in
                  ("Service", "State", "Health", "ExitCode", "Image")}), flush=True)
    except (OSError, ValueError, subprocess.SubprocessError):
        print("container state unavailable: run docker compose ps --all on the VPS", flush=True)


def request(target, timeout, directory, private_worker=False):
    command = ["curl", "--disable", "--silent", "--location", "--max-redirs", "5",
               "--proto", "=http,https", "--proto-redir", "=http,https",
               "--connect-timeout", str(timeout), "--max-time", str(timeout),
               "--max-filesize", "16384", "--write-out", "\n%{http_code}", target]
    if private_worker:
        command = [*compose_command(directory), "exec", "-T", "execution-worker", *command]
    try:
        result = subprocess.run(command, cwd=directory, capture_output=True, timeout=timeout)
        body, _, status = result.stdout.rpartition(b"\n")
        code = status.decode("ascii") if len(status) == 3 and status.isdigit() else "000"
        return result.returncode == 0, code, body[:16384]
    except (OSError, subprocess.SubprocessError):
        return False, "000", b""


def verify(args):
    if not 1 <= args.attempts <= 60:
        raise ValueError("attempts must be between 1 and 60")
    checks = [
        ("frontend", args.frontend_url + "/", "page", False),
        ("backend readiness", args.backend_url + "/actuator/health/readiness", "health", False),
        ("worker readiness", (args.worker_url or "http://127.0.0.1:8090") + "/actuator/health/readiness",
         "health", not args.worker_url),
        ("public frontend", args.public_base_url + "/", "page", False),
        ("public API", args.public_base_url + "/api/v1/public/registration-settings", "api", False),
    ]
    deadline = time.monotonic() + args.timeout
    for attempt in range(1, args.attempts + 1):
        passed = True
        for service, target, kind, private in checks:
            remaining = deadline - time.monotonic()
            ok, status, body = request(target, min(args.request_timeout, remaining), args.directory, private) if remaining > 0 else (False, "000", b"")
            ok = ok and status == "200"
            fragment = ""
            if kind in ("health", "api"):
                try:
                    response = json.loads(body)
                    if kind == "health":
                        health = response.get("status")
                        # Print only known health tokens; other response data may contain secrets.
                        fragment = json.dumps({"status": health if health in
                                              ("UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN") else "invalid"})
                        ok = ok and health == "UP"
                    else:
                        ok = ok and response.get("registrationMode") in ("OPEN", "INVITE_ONLY")
                        fragment = "registration-settings schema=" + ("valid" if ok else "invalid")
                except (ValueError, AttributeError, TypeError):
                    ok = False
                    fragment = "invalid JSON response"
            print(f"attempt={attempt}/{args.attempts} service={service} url={target} "
                  f"transport={'container' if private else 'host'} HTTP={status} "
                  f"result={'PASS' if ok else 'FAIL'} {fragment}", flush=True)
            passed = passed and ok
        if passed:
            print("Smoke verification passed", flush=True)
            return 0
        remaining = deadline - time.monotonic()
        if attempt < args.attempts and remaining > 0:
            time.sleep(min(args.delay, remaining))
        else:
            break
    print("Smoke verification failed; no rollback or cleanup was performed", flush=True)
    container_states(args.directory)
    return 1


def main():
    arguments = parser().parse_args()
    try:
        return verify(arguments)
    except ValueError as error:
        print(f"Invalid configuration: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
