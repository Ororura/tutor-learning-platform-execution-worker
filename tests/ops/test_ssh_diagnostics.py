"""SSH diagnostics must disclose stages without disclosing credentials or debug text."""

import contextlib
import io
import os
from pathlib import Path
import socket
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import ssh_diagnostics as diagnostics


class SSHDiagnosticsTests(unittest.TestCase):
    environment = {
        "SERVER_HOST": "do-not-log.example.com", "SERVER_USER": "private-user",
        "SERVER_SSH_KEY": "DO_NOT_LOG_PRIVATE_KEY", "SERVER_SSH_PORT": "22",
    }
    public = b"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICdDvpZpwMLiLtj8u3Jz4gJtkkpbvkbMyD+SPkstFmAJ"

    def test_success_hides_debug_output_and_removes_restricted_key(self):
        key_paths = []

        def run(command, **kwargs):
            if command[0] == "ssh-keygen":
                key = Path(command[-1])
                key_paths.append(key)
                self.assertEqual(0o600, key.stat().st_mode & 0o777)
                self.assertEqual("DO_NOT_LOG_PRIVATE_KEY\n", key.read_text())
                return subprocess.CompletedProcess(command, 0, self.public)
            if command[0] == "ssh-keyscan":
                return subprocess.CompletedProcess(command, 0, b"DO_NOT_LOG_HOST_KEY\n")
            self.assertEqual("printf authenticated", command[-1])
            return subprocess.CompletedProcess(command, 0, "DO_NOT_LOG_REMOTE_STDOUT",
                                               "Authenticated to DO_NOT_LOG_HOST with DO_NOT_LOG_PRIVATE_KEY")

        output = io.StringIO()
        with patch.dict(os.environ, self.environment, clear=True), \
             patch.object(diagnostics.socket, "getaddrinfo", return_value=[(socket.AF_INET,)]), \
             patch.object(diagnostics.subprocess, "run", side_effect=run), \
             contextlib.redirect_stdout(output):
            self.assertEqual(0, diagnostics.main())
        self.assertIn('"matches_verified_mac_key": true', output.getvalue())
        self.assertIn('"authenticated": true', output.getvalue())
        for secret in ("DO_NOT_LOG", self.environment["SERVER_HOST"], self.environment["SERVER_USER"]):
            self.assertNotIn(secret, output.getvalue())
        self.assertTrue(key_paths)
        self.assertTrue(all(not key.exists() for key in key_paths))

    def test_failed_connections_only_print_fixed_stage_and_reason(self):
        output = io.StringIO()
        failed = subprocess.CompletedProcess([], 255, "DO_NOT_LOG",
            "Connection established. Connection timed out during banner exchange DO_NOT_LOG")
        results = [subprocess.CompletedProcess([], 0, self.public),
                   subprocess.CompletedProcess([], 0, b"DO_NOT_LOG\n"), *[failed] * 4]
        with patch.dict(os.environ, self.environment, clear=True), \
             patch.object(diagnostics.socket, "getaddrinfo", return_value=[(socket.AF_INET,)]), \
             patch.object(diagnostics.subprocess, "run", side_effect=results) as runner, \
             contextlib.redirect_stdout(output):
            self.assertEqual(1, diagnostics.main())
        self.assertEqual(6, runner.call_count)
        self.assertEqual(4, output.getvalue().count('"reason": "banner_exchange_timeout"'))
        self.assertNotIn("DO_NOT_LOG", output.getvalue())
        self.assertTrue(all(call.args[0][-1] == "printf authenticated" for call in runner.call_args_list[2:]))


if __name__ == "__main__":
    unittest.main()
