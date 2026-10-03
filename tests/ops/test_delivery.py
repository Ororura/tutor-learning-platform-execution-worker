"""Operational checks use mock HTTP and Docker, never production credentials."""

import contextlib
import io
import json
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import deploy_image as deploy
import smoke_deployment as smoke


class SmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fail_path = None
        cls.fail_remaining = 0
        cls.slow = False
        cls.malformed = False
        cls.requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                cls.requests.append(self.path)
                if cls.slow:
                    time.sleep(0.3)
                failing = self.path == cls.fail_path and cls.fail_remaining != 0
                if cls.fail_remaining > 0 and failing:
                    cls.fail_remaining -= 1
                code = 503 if failing else 200
                if self.path.endswith("/actuator/health/readiness"):
                    body = {"status": "DOWN" if failing else "UP", "secret": "DO_NOT_LOG"}
                elif self.path.endswith("/registration-settings"):
                    body = {"registrationMode": "OPEN"}
                else:
                    body = {"page": "ok", "secret": "DO_NOT_LOG"}
                payload = b"not JSON DO_NOT_LOG" if cls.malformed else json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                try:
                    self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *_):
                pass

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        type(self).fail_path = None
        type(self).fail_remaining = 0
        type(self).slow = False
        type(self).malformed = False
        type(self).requests = []

    def args(self, *extra):
        return smoke.parser().parse_args([
            "--frontend-url", self.base + "/frontend", "--backend-url", self.base + "/backend",
            "--worker-url", self.base + "/worker", "--public-base-url", self.base + "/public",
            "--attempts", "2", "--request-timeout", "0.1", "--timeout", "2", "--delay", "0.01",
            *extra])

    def verify(self, *extra):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch.object(smoke, "container_states"):
            result = smoke.verify(self.args(*extra))
        self.assertNotIn("DO_NOT_LOG", output.getvalue())
        return result, output.getvalue()

    def test_success(self):
        result, output = self.verify()
        self.assertEqual(0, result)
        self.assertEqual(5, output.count("result=PASS"))
        self.assertEqual(5, len(self.requests))

    def test_each_service_failure(self):
        for path in ("/frontend/", "/backend/actuator/health/readiness",
                     "/worker/actuator/health/readiness", "/public/",
                     "/public/api/v1/public/registration-settings"):
            with self.subTest(path=path):
                type(self).fail_path = path
                type(self).fail_remaining = -1
                result, output = self.verify()
                self.assertEqual(1, result)
                self.assertIn("HTTP=503", output)

    def test_retry_then_success(self):
        type(self).fail_path = "/backend/actuator/health/readiness"
        type(self).fail_remaining = 1
        result, output = self.verify()
        self.assertEqual(0, result)
        self.assertIn("attempt=2/2", output)

    def test_global_timeout(self):
        type(self).slow = True
        start = time.monotonic()
        result, output = self.verify("--attempts", "60", "--timeout", "0.2")
        self.assertEqual(1, result)
        self.assertLess(time.monotonic() - start, 0.8)
        self.assertIn("HTTP=000", output)
        self.assertLessEqual(len(self.requests), 3)

    def test_request_timeout(self):
        type(self).slow = True
        result, output = self.verify("--attempts", "1")
        self.assertEqual(1, result)
        self.assertIn("HTTP=000", output)

    def test_finite_retries(self):
        type(self).fail_path = "/frontend/"
        type(self).fail_remaining = -1
        result, output = self.verify("--attempts", "3")
        self.assertEqual(1, result)
        self.assertEqual(15, len(self.requests))
        self.assertNotIn("attempt=4", output)

    def test_invalid_health_body(self):
        type(self).malformed = True
        result, output = self.verify()
        self.assertEqual(1, result)
        self.assertIn("invalid JSON", output)

    def test_invalid_configuration(self):
        for value in ("https://user:password@example.com", "file:///tmp/data", "http://localhost/?secret=abc", "http://localhost:abc"):
            with self.subTest(value=value), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.args("--frontend-url", value)
        for count in ("0", "61"):
            with self.assertRaises(ValueError):
                smoke.verify(self.args("--attempts", count))

    def test_http_200_but_readiness_down(self):
        with patch.object(smoke, "request", return_value=(True, "200", b'{"status":"DOWN"}')):
            result, output = self.verify()
        self.assertEqual(1, result)
        self.assertIn('"status": "DOWN"', output)

    def test_frontend_probe_follows_nextjs_listener_hostname(self):
        import os
        override = {"services": {name: {} for name in deploy.IMAGES}}
        deploy.healthchecks(override)
        probe = override["services"]["frontend"]["healthcheck"]["test"][3]
        probe = probe.replace("3000", str(self.server.server_port))
        for hostname, expected in (("127.0.0.1", 0), ("127.0.0.2", 1)):
            with self.subTest(hostname=hostname):
                result = subprocess.run(["node", "-e", probe], env=dict(os.environ, HOSTNAME=hostname),
                                        capture_output=True, timeout=6)
                self.assertEqual(expected, result.returncode)

    def test_worker_uses_container_network(self):
        completed = subprocess.CompletedProcess([], 0, stdout=b'{"status":"UP"}\n200')
        with patch.object(smoke.subprocess, "run", return_value=completed) as runner:
            self.assertEqual((True, "200", b'{"status":"UP"}'),
                             smoke.request("http://127.0.0.1:8090/actuator/health/readiness", 1, Path.cwd(), True))
        self.assertEqual(["docker", "compose", "exec", "-T", "execution-worker", "curl"],
                         runner.call_args.args[0][:6])
        self.assertEqual(1, runner.call_args.kwargs["timeout"])


class DeliveryTests(unittest.TestCase):
    sha = "a" * 40
    digest = "sha256:" + "b" * 64

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "compose.yml").write_text("services: {}\n")
        self.commands = []
        self.config = {"services": {name: {"healthcheck": {"test": ["CMD", "probe"]}} for name in
                                    (*deploy.IMAGES, "postgres")}}
        for name, dependency in (("backend", "postgres"), ("frontend", "backend")):
            self.config["services"][name]["depends_on"] = {dependency: {"condition": "service_healthy"}}

    def args(self, **kwargs):
        values = dict(directory=self.root, service="backend", sha=self.sha, digest=self.digest,
                      environment="production", attempts=1, timeout=120)
        values.update(kwargs)
        return argparse_namespace(**values)

    def fake_run(self, command, directory, timeout=60):
        self.commands.append(command)
        if "up" in command:
            self.assertGreater(timeout, 120)
        if command[:3] == ["docker", "image", "inspect"]:
            return json.dumps([{"Id": "expected-image-id", "RepoDigests": [deploy.IMAGES["backend"] + "@" + self.digest]}])
        if "config" in command:
            return json.dumps(self.config)
        return ""

    def execute(self, smoke_result=0, **kwargs):
        with patch.object(deploy, "run", side_effect=self.fake_run), \
             patch.object(deploy, "pinned_running_image", side_effect=lambda _, name: deploy.IMAGES[name] + "@sha256:" + "c" * 64), \
             patch.object(deploy, "running_image", return_value={"Image": "expected-image-id", "State": {"Status": "running", "Health": {"Status": "healthy"}}}), \
             patch.object(deploy, "verify", return_value=smoke_result), contextlib.redirect_stdout(io.StringIO()):
            return deploy.deploy(self.args(**kwargs))

    def test_exact_service_image_and_history(self):
        self.assertEqual(0, self.execute())
        override = json.loads((self.root / "compose.override.yml").read_text())
        expected = deploy.IMAGES["backend"] + ":" + self.sha + "@" + self.digest
        self.assertEqual(expected, override["services"]["backend"]["image"])
        self.assertNotIn("latest", json.dumps(override))
        self.assertEqual(0, self.execute())  # Managed state can be reused by another run.
        history = json.loads((self.root / ".delivery-history.json").read_text())
        self.assertTrue(history[-1]["verified"])
        self.assertIn("previous", history[-1])
        for command in self.commands:
            self.assertNotIn("down", command)
            self.assertNotIn("prune", command)
            if "up" in command:
                self.assertIn("--no-deps", command)
                self.assertIn("--no-build", command)
                self.assertIn("--wait", command)
                self.assertEqual("backend", command[-1])

    def test_failed_smoke_records_unverified_candidate(self):
        self.assertEqual(1, self.execute(smoke_result=1))
        history = json.loads((self.root / ".delivery-history.json").read_text())
        self.assertFalse(history[-1]["verified"])
        self.assertEqual(1, len([command for command in self.commands if "up" in command]))

    def test_failed_startup_diagnoses_once_and_remains_failed(self):
        for failure in (RuntimeError("Compose unhealthy"),
                        subprocess.TimeoutExpired(["docker", "compose", "up"], 135)):
            with self.subTest(failure=type(failure).__name__):
                original_run = self.fake_run

                def fail_up(command, directory, timeout=60):
                    result = original_run(command, directory, timeout)
                    if "up" in command:
                        raise failure
                    return result

                with patch.object(self, "fake_run", side_effect=fail_up), \
                     self.assertRaises(type(failure)):
                    self.execute(smoke_result=0)
                history = json.loads((self.root / ".delivery-history.json").read_text())
                self.assertFalse(history[-1]["verified"])
                self.assertNotIn("down", [part for command in self.commands for part in command])

    def test_startup_diagnostics_have_bounded_budget(self):
        def fail_up(command, directory, timeout=60):
            result = self.fake_run(command, directory, timeout)
            if "up" in command:
                raise RuntimeError("Compose unhealthy")
            return result

        with patch.object(deploy, "run", side_effect=fail_up), \
             patch.object(deploy, "pinned_running_image", side_effect=lambda _, name: deploy.IMAGES[name] + "@" + self.digest), \
             patch.object(deploy, "verify", return_value=1) as verifier, \
             contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            deploy.deploy(self.args(attempts=12))
        verifier.assert_called_once()
        diagnostics = verifier.call_args.args[0]
        self.assertEqual(1, diagnostics.attempts)
        self.assertEqual(15, diagnostics.timeout)

    def test_wrong_running_artifact_cannot_be_verified(self):
        with patch.object(deploy, "run", side_effect=self.fake_run), \
             patch.object(deploy, "pinned_running_image", side_effect=lambda _, name: deploy.IMAGES[name] + "@" + self.digest), \
             patch.object(deploy, "running_image", return_value={"Image": "different-image-id", "State": {"Status": "running", "Health": {"Status": "healthy"}}}), \
             patch.object(deploy, "verify", return_value=0), \
             contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            deploy.deploy(self.args())
        history = json.loads((self.root / ".delivery-history.json").read_text())
        self.assertFalse(history[-1]["verified"])

    def test_manual_rollback_resolves_old_sha(self):
        self.assertEqual(0, self.execute(sha="d" * 40, digest=None))
        first_pull = next(command for command in self.commands if command[:2] == ["docker", "pull"])
        self.assertTrue(first_pull[-1].endswith(":" + "d" * 40))

    def test_legacy_rollback_uses_recorded_digest(self):
        reference = deploy.IMAGES["backend"] + "@" + self.digest
        self.assertEqual(0, self.execute(sha=None, digest=None, rollback_reference=reference))
        first_pull = next(command for command in self.commands if command[:2] == ["docker", "pull"])
        self.assertEqual(reference, first_pull[-1])

    def test_invalid_sha_has_no_docker_calls(self):
        with self.assertRaises(ValueError):
            self.execute(sha="latest")
        self.assertEqual([], self.commands)

    def test_digest_mismatch_preserves_existing_compose(self):
        with self.assertRaises(RuntimeError):
            self.execute(digest="sha256:" + "e" * 64)
        self.assertFalse((self.root / "compose.override.yml").exists())

    def test_unmanaged_override_is_preserved(self):
        override = self.root / "compose.override.yml"
        override.write_text("services:\n  backend: {}\n")
        with self.assertRaises(RuntimeError):
            self.execute()
        self.assertEqual("services:\n  backend: {}\n", override.read_text())

    def test_public_database_or_worker_is_rejected(self):
        for name in ("postgres", "execution-worker"):
            with self.subTest(name=name):
                self.config["services"][name]["ports"] = [{"host_ip": "0.0.0.0", "published": "5432"}]
                with self.assertRaises(RuntimeError):
                    deploy.validate_compose(self.config)
                del self.config["services"][name]["ports"]

    def test_missing_health_dependency_is_rejected(self):
        self.config["services"]["backend"]["depends_on"]["postgres"]["condition"] = "service_started"
        with self.assertRaises(RuntimeError):
            deploy.validate_compose(self.config)


class ComposeTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("docker"), "Docker Compose CLI unavailable")
    def test_production_and_demo_overrides(self):
        fixture = Path(__file__).parent / "compose.yml"
        with tempfile.TemporaryDirectory() as directory:
            override = {deploy.MARKER: 1, "services": {
                name: {"image": repository + ":" + "a" * 40 + "@sha256:" + "b" * 64}
                for name, repository in deploy.IMAGES.items()}}
            deploy.healthchecks(override)
            candidate = Path(directory) / "override.json"
            candidate.write_text(json.dumps(override))
            for frontend_port, backend_port in ((3000, 8080), (3001, 8081)):
                with self.subTest(frontend_port=frontend_port):
                    import os
                    environment = dict(os.environ, FRONTEND_PORT=str(frontend_port), BACKEND_PORT=str(backend_port))
                    result = subprocess.run(["docker", "compose", "-f", str(fixture), "-f", str(candidate),
                                             "config", "--format", "json"], env=environment,
                                            capture_output=True, text=True, timeout=10, check=True)
                    config = json.loads(result.stdout)
                    deploy.validate_compose(config)
                    self.assertEqual(override["services"]["backend"]["image"], config["services"]["backend"]["image"])
                    self.assertEqual(str(frontend_port), config["services"]["frontend"]["ports"][0]["published"])
                    self.assertFalse(config["services"]["execution-worker"].get("ports"))
                    self.assertIn("/actuator/health/readiness", " ".join(config["services"]["backend"]["healthcheck"]["test"]))


def argparse_namespace(**kwargs):
    from argparse import Namespace
    return Namespace(**kwargs)


if __name__ == "__main__":
    unittest.main()
