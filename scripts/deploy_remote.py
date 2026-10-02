#!/usr/bin/env python3
"""Upload this workflow's operational scripts and deploy its scanned digest via SSH."""

import os
from pathlib import Path
import re
import shlex
import subprocess

from deploy_image import DEFAULT_SERVICE, DIGEST, SHA
from smoke_deployment import url


def remote_command(arguments):
    return shlex.join(str(argument) for argument in arguments)


def main():
    sha = os.environ["IMAGE_SHA"]
    digest = os.environ["IMAGE_DIGEST"]
    if not SHA.fullmatch(sha) or not DIGEST.fullmatch(digest):
        raise ValueError("Workflow must supply full commit SHA and scanned image digest")
    host, user = os.environ["SERVER_HOST"], os.environ["SERVER_USER"]
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]*", host) or not re.fullmatch(r"[a-zA-Z0-9_][a-zA-Z0-9_-]*", user):
        raise ValueError("Invalid SSH host/user")
    target = user + "@" + host
    key = Path.home() / ".ssh/tutorplatform_deploy"
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-i", str(key), target]
    public_urls = {"production": url(os.environ["PRODUCTION_PUBLIC_BASE_URL"]),
                   "demo": url(os.environ["DEMO_PUBLIC_BASE_URL"])}
    scripts = Path(__file__).resolve().parent
    for environment, directory, frontend_port, backend_port in (
            ("production", "/opt/tutorplatform", 3000, 8080),
            ("demo", "/opt/tutorplatform-demo", 3001, 8081)):
        operations = directory + "/ops/" + DEFAULT_SERVICE
        subprocess.run([*ssh, remote_command(["mkdir", "-p", operations])], check=True, timeout=30)
        subprocess.run(["scp", "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-i", str(key),
                        str(scripts / "deploy_image.py"), str(scripts / "smoke_deployment.py"),
                        target + ":" + operations + "/"], check=True, timeout=30)
        command = ["python3", operations + "/deploy_image.py", "--directory", directory,
                   "--environment", environment, "--sha", sha, "--digest", digest,
                   "--frontend-url", f"http://127.0.0.1:{frontend_port}",
                   "--backend-url", f"http://127.0.0.1:{backend_port}",
                   "--public-base-url", public_urls[environment]]
        subprocess.run([*ssh, remote_command(command)], check=True, timeout=1200)


if __name__ == "__main__":
    main()
