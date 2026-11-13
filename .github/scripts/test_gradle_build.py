import contextlib
import io
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

import gradle_build as target


def failure(detail, task=":compileJava"):
    return f"FAILURE: Build failed with an exception.\n\n* What went wrong:\nExecution failed for task '{task}'.\n{detail}\n* Try:\nRun with --info\n"


NETWORK = failure("Could not resolve all files for configuration ':compileClasspath'.\n"
                  "Could not download placeholderapi-2.12.3.jar\n"
                  "Could not get resource 'https://repo.example/a.jar'.\nRead timed out")


class RetryPolicyTest(unittest.TestCase):
    def test_release_workflow_selects_assets_from_explicit_manifest(self):
        workflow=(Path(__file__).resolve().parents[2]/".github/workflows/release.yml").read_text()
        self.assertIn(".github/scripts/release_spec.py",workflow)
        self.assertIn("steps.release-spec.outputs.kind == 'standalone'",workflow)
        self.assertIn("steps.release-spec.outputs.kind == 'full'",workflow)
        self.assertNotIn("steps.verify-version.outputs.version == '2.0.2-rc.7'",workflow)

    def test_build_workflow_runs_offline_backup_restore_regressions(self):
        workflow=(Path(__file__).resolve().parents[2]/".github/workflows/build.yml").read_text()
        self.assertIn("python3 -m unittest discover -s tools/backup -p 'test_*.py' -v",workflow)

    def test_observed_timeouts_and_truncated_dependency_downloads(self):
        for message in ["Read timed out", "Connection reset", "Connect timed out",
                        "Premature end of Content-Length delimited message body (expected: 159446; received: 4096)",
                        "Received status code 429 from server: Too Many Requests",
                        "Received status code 503 from server: unavailable"]:
            with self.subTest(message=message):
                self.assertTrue(target.retryable(NETWORK.replace("Read timed out", message)))

    def test_tests_compiler_oom_permission_missing_version_and_unknown_errors_stop(self):
        for detail in ["There were failing tests", "12 tests completed, 1 failed", "X.java:9: error: missing symbol",
                       "Compilation failed", "java.lang.OutOfMemoryError", "Could not find x:y:999.",
                       "Received status code 401", "Received status code 403", "Received status code 404",
                       "PKIX path building failed", "Task :test > example() FAILED"]:
            with self.subTest(detail=detail):
                self.assertFalse(target.retryable(NETWORK + "\n" + detail))
        self.assertFalse(target.retryable(failure("Unknown validation failure")))
        self.assertFalse(target.retryable("Read timed out"))
        self.assertFalse(target.retryable(failure("Could not download x.jar: unknown error")))
        self.assertFalse(target.retryable(failure("User code threw java.net.SocketTimeoutException: Read timed out", ":test")))

    def test_continue_mixed_failures_are_not_retried(self):
        self.assertFalse(target.retryable(NETWORK + failure("Custom validation failed", ":other:check")))
        self.assertFalse(target.retryable(NETWORK.replace("* Try:", "Execution failed for task ':other:test'.\nUnknown failure\n* Try:")))
        self.assertTrue(target.retryable(NETWORK + NETWORK.replace(":compileJava", ":other:compileJava")))

    def run_sequence(self, outputs, codes=None, **kwargs):
        calls, sleeps = [], []
        def runner(command, root, log):
            n = len(calls)
            calls.append((command, log.name))
            log.write_text(outputs[n], encoding="utf-8")
            return codes[n] if codes else (0 if outputs[n] == "BUILD SUCCESSFUL" else 1)
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()), patch.dict("os.environ", {"GITHUB_STEP_SUMMARY": ""}):
            root = Path(directory)
            code = target.build(root, ["fake-gradle"], runner=runner, sleeper=sleeps.append, **kwargs)
            final_log = (root / "build.log").read_text()
            logs = sorted(p.name for p in root.glob("build-attempt-*.log"))
        return code, calls, sleeps, final_log, logs

    def test_transport_retry_keeps_separate_logs_and_returns_real_success(self):
        code, calls, sleeps, final, logs = self.run_sequence([NETWORK, "BUILD SUCCESSFUL"])
        self.assertEqual(0, code)
        self.assertEqual(2, len(calls))
        self.assertEqual([15], sleeps)
        self.assertEqual("BUILD SUCCESSFUL", final)
        self.assertEqual(["build-attempt-1.log", "build-attempt-2.log"], logs)

    def test_retry_budget_is_bounded_and_cannot_mask_failure(self):
        code, calls, sleeps, _, _ = self.run_sequence([NETWORK] * 3)
        self.assertEqual(1, code)
        self.assertEqual(3, len(calls))
        self.assertEqual([15, 15], sleeps)

    def test_a_real_failure_after_network_failure_stops_immediately(self):
        broken = NETWORK + "\nThere were failing tests"
        code, calls, sleeps, final, _ = self.run_sequence([NETWORK, broken])
        self.assertEqual(1, code)
        self.assertEqual(2, len(calls))
        self.assertEqual([15], sleeps)
        self.assertEqual(broken, final)

    def test_signals_success_and_real_tests_are_never_retried(self):
        for text, code in [(NETWORK, -9), ("BUILD SUCCESSFUL", 0),
                           (NETWORK + "\nThere were failing tests", 7)]:
            with self.subTest(code=code):
                returned, calls, sleeps, _, _ = self.run_sequence([text], [code])
                self.assertEqual(code, returned)
                self.assertEqual(1, len(calls))
                self.assertEqual([], sleeps)

    def test_real_subprocess_preserves_failure_status_and_merged_log(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            root=Path(directory);log=root / "attempt.log"
            code=target.execute([sys.executable,"-c","import sys; print('stdout'); print('stderr',file=sys.stderr); sys.exit(7)"],root,log)
            self.assertEqual(7,code)
            self.assertIn("stdout",log.read_text())
            self.assertIn("stderr",log.read_text())

    def test_configuration_cache_transport_failure_is_retryable_but_corruption_is_not(self):
        text=NETWORK.replace("Execution failed for task ':compileJava'.", "Configuration cache state could not be cached: classpath serialization failed")
        self.assertTrue(target.retryable(text))
        self.assertFalse(target.retryable(failure("Configuration cache entry is corrupt")))

    def test_invalid_retry_settings_fail_closed(self):
        for attempts, delay in [(0, 15), (4, 15), (3, -1), (3, 61)]:
            with self.subTest(attempts=attempts, delay=delay):
                with self.assertRaises(ValueError):
                    target.build(Path("."), ["never-run"], attempts=attempts, delay=delay)


if __name__ == "__main__":
    unittest.main()
