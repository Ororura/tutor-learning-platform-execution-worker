#!/usr/bin/env python3
"""Upload this workflow's operational scripts and deploy its scanned digest via SSH."""

import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

from deploy_image import DEFAULT_SERVICE, DIGEST, IMAGES, SHA
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
    port = os.environ.get("SERVER_SSH_PORT", "22")
    if not re.fullmatch(r"[0-9]{1,5}", port) or not 1 <= int(port) <= 65535:
        raise ValueError("SERVER_SSH_PORT must be an integer between 1 and 65535")
    key = Path.home() / ".ssh/tutorplatform_deploy"
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-p", port, "-i", str(key), target]
    public_urls = {"production": url(os.environ["PRODUCTION_PUBLIC_BASE_URL"]),
                   "demo": url(os.environ["DEMO_PUBLIC_BASE_URL"])}
    scripts = Path(__file__).resolve().parent
    for environment, directory, frontend_port, backend_port in (
            ("production", "/opt/tutorplatform", 3000, 8080),
            ("demo", "/opt/tutorplatform-demo", 3001, 8081)):
        operations = directory + "/ops/" + DEFAULT_SERVICE
        print(f"Deploy preflight environment={environment} service={DEFAULT_SERVICE} "
              f"commit={sha} repository={IMAGES[DEFAULT_SERVICE]} tag={sha} digest={digest} "
              f"directory={directory} SSH-port={port}", flush=True)
        try:
            subprocess.run([*ssh, remote_command(["test", "-w", directory])],
                           check=True, timeout=30, capture_output=True)
        except (OSError, subprocess.SubprocessError) as error:
            reason = ("stack directory requires deployment-user write access"
                      if isinstance(error, subprocess.CalledProcessError) and error.returncode == 1
                      else "SSH connection failed; verify host, port, key and runner network access")
            print(f"Deployment preflight failed environment={environment}: {reason}; "
                  "no images or stack state changed in this environment", file=sys.stderr, flush=True)
            return 1
        subprocess.run([*ssh, remote_command(["mkdir", "-p", operations])], check=True, timeout=30)
        subprocess.run(["scp", "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-P", port, "-i", str(key),
                        str(scripts / "deploy_image.py"), str(scripts / "smoke_deployment.py"),
                        target + ":" + operations + "/"], check=True, timeout=30)
        command = ["python3", operations + "/deploy_image.py", "--directory", directory,
                   "--environment", environment, "--sha", sha, "--digest", digest,
                   "--frontend-url", f"http://127.0.0.1:{frontend_port}",
                   "--backend-url", f"http://127.0.0.1:{backend_port}",
                   "--public-base-url", public_urls[environment]]
        subprocess.run([*ssh, remote_command(command)], check=True, timeout=1200)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (argparse.ArgumentTypeError, KeyError, ValueError, OSError, subprocess.SubprocessError) as error:
        # Never dump SSH/SCP command arguments or arbitrary subprocess output.
        print(f"Remote deployment failed: {type(error).__name__}; check deployment configuration "
              "and the last environment's delivery history", file=sys.stderr)
        sys.exit(1)
