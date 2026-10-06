#!/usr/bin/env python3
"""Read-only SSH probes; print fixed diagnostics, never subprocess output or secrets."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time


EXPECTED_KEY = "SHA256:xTYORUy7d7ka9Q9lUXCIJOroffP44CWwesd4e+5Zuyg"


def reason(stderr):
    text = stderr.lower()
    for pattern, label in (
        ("during banner exchange", "banner_exchange_timeout"),
        ("during key exchange", "key_exchange_timeout"),
        ("permission denied", "authentication_denied"),
        ("host key verification failed", "host_key_verification_failed"),
        ("remote host identification has changed", "host_key_changed"),
        ("too many authentication failures", "too_many_authentication_failures"),
        ("connection refused", "connection_refused"),
        ("connection timed out", "connection_timeout"),
        ("connection closed", "connection_closed"),
        ("connection reset", "connection_reset"),
        ("could not resolve hostname", "dns_failed"),
    ):
        if pattern in text:
            return label
    return "unclassified"


def main():
    host, user = os.environ["SERVER_HOST"], os.environ["SERVER_USER"]
    port = os.environ.get("SERVER_SSH_PORT", "22")
    if (not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]*", host)
            or not re.fullmatch(r"[a-zA-Z0-9_][a-zA-Z0-9_-]*", user)
            or not re.fullmatch(r"[0-9]{1,5}", port) or not 1 <= int(port) <= 65535):
        raise ValueError("Invalid SSH configuration")
    addresses = socket.getaddrinfo(host, int(port), type=socket.SOCK_STREAM)
    print(json.dumps({"dns_ipv4": sum(a[0] == socket.AF_INET for a in addresses),
                      "dns_ipv6": sum(a[0] == socket.AF_INET6 for a in addresses)}), flush=True)
    with tempfile.TemporaryDirectory() as directory:
        key = Path(directory) / "key"
        with open(key, "w", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
            stream.write(os.environ["SERVER_SSH_KEY"] + "\n")
        public = subprocess.run(["ssh-keygen", "-y", "-f", str(key)], check=True,
                                capture_output=True, timeout=10).stdout.split()[1]
        fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(base64.b64decode(public)).digest()).decode().rstrip("=")
        print(json.dumps({"matches_verified_mac_key": fingerprint == EXPECTED_KEY}), flush=True)
        scanned = subprocess.run(["ssh-keyscan", "-t", "ed25519", "-T", "10", "-p", port, "-H", host],
                                 capture_output=True, timeout=30)
        known_hosts = Path(directory) / "known_hosts"
        known_hosts.write_bytes(scanned.stdout)
        print(json.dumps({"keyscan_exit": scanned.returncode,
                          "host_key_count": len(scanned.stdout.splitlines())}), flush=True)
        ssh = ["ssh", "-vvv", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
               "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + str(known_hosts),
               "-o", "IdentitiesOnly=yes", "-o", "ControlMaster=auto", "-o", "ControlPersist=60",
               "-o", "ControlPath=" + str(Path(directory) / "control-%C"),
               "-p", port, "-i", str(key), user + "@" + host, "printf authenticated"]
        for index in range(8):
            name, options = "shared_connection_" + str(index + 1), []
            start = time.monotonic()
            result = subprocess.run([ssh[0], *options, *ssh[1:]], capture_output=True,
                                    text=True, timeout=20)
            debug = result.stderr.lower()
            print(json.dumps({"probe": name, "exit": result.returncode,
                              "seconds": round(time.monotonic() - start, 2),
                              "tcp_connected": "connection established" in debug,
                              "server_banner": "remote protocol version" in debug,
                              "key_exchange": "newkeys received" in debug,
                              "key_offered": "offering public key" in debug,
                              "authenticated": result.returncode == 0 and result.stdout == "authenticated",
                              "reason": "success" if result.returncode == 0 else reason(debug)}), flush=True)
            if result.returncode != 0:
                return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyError, ValueError, OSError, subprocess.SubprocessError) as error:
        print("SSH diagnostics unavailable: " + type(error).__name__, flush=True)
        raise SystemExit(1)
