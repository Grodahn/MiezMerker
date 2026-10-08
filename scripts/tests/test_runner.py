"""Runner failure-path checks with synthetic native reports and processes."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("compact_runner", Path(__file__).parents[1] / "test.py")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.root_patch = patch.object(runner, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def invoke(self, args, code=0, report=None, log="", missing_tool=False):
        def native(command, **kwargs):
            kwargs["stdout"].write(log)
            if missing_tool:
                raise FileNotFoundError("test executable not found")
            run = Path(kwargs["stdout"].name).parent
            if report is not None:
                if args[0] == "backend":
                    directory = run / "surefire"
                    directory.mkdir()
                    (directory / "TEST-current.xml").write_text(report, encoding="utf-8")
                elif args[0] == "frontend":
                    (run / "vitest.json").write_text(json.dumps(report), encoding="utf-8")
                else:
                    (run / "ctest.xml").write_text(report, encoding="utf-8")
            return subprocess.CompletedProcess(command, code)

        output = io.StringIO()
        with patch.object(runner.subprocess, "run", side_effect=native), contextlib.redirect_stdout(output):
            code = runner.main(args)
        return code, output.getvalue()

    def test_success_is_three_lines_and_full_log_is_retained(self):
        code, output = self.invoke(["backend", "--domain", "node", "auth"],
                                   report='<testsuite><testcase name="ok"/></testsuite>', log="full noisy log\n")
        self.assertEqual(code, 0)
        self.assertEqual(len(output.splitlines()), 3)
        self.assertIn("PASS backend/node+auth\nTests: 1", output)
        run = next((self.root / "test-results/compact").iterdir())
        self.assertEqual((run / "output.log").read_text(), "full noisy log\n")
        command = json.loads((run / "command.json").read_text())["argv"]
        self.assertIn("-Dgroups=node,auth", command)

    def test_assertion_failure_preserves_native_code_and_details(self):
        xml = '<testsuite><testcase classname="NodeTest" name="reject"><failure message="expected 403 but was 200">full stack</failure></testcase></testsuite>'
        code, output = self.invoke(["backend", "--test", "NodeTest"], code=7, report=xml)
        self.assertEqual(code, 7)
        self.assertIn("NodeTest.reject", output)
        self.assertIn("expected 403 but was 200", output)
        self.assertIn("Detailed logs:", output)

    def test_compile_failure_ignores_stale_reports(self):
        previous_code, _ = self.invoke(["backend"], report='<testsuite><testcase name="previous"/></testsuite>')
        self.assertEqual(previous_code, 0)
        stale = self.root / "backend/target/surefire-reports"
        stale.mkdir(parents=True)
        (stale / "TEST-old.xml").write_text('<testsuite><testcase name="old"/></testsuite>')
        code, output = self.invoke(["backend"], code=3, log="[ERROR] compilation failed: missing symbol\n")
        self.assertEqual(code, 3)
        self.assertIn("Tests: 0", output)
        self.assertIn("missing symbol", output)
        self.assertNotIn("PASS", output)

    def test_empty_skipped_missing_and_malformed_reports_fail_closed(self):
        for xml in (None, '<testsuite/>', '<testsuite><testcase name="skip"><skipped/></testcase></testsuite>', '<broken'):
            with self.subTest(xml=xml):
                code, output = self.invoke(["backend"], report=xml)
                self.assertEqual(code, 1)
                self.assertIn("FAIL", output)

    def test_report_failure_overrules_native_false_success(self):
        code, _ = self.invoke(["backend"], report='<testsuite><testcase name="oops"><error message="startup"/></testcase></testsuite>')
        self.assertEqual(code, 1)

    def test_vitest_filter_and_failure(self):
        report = {"testResults": [{"assertionResults": [
            {"status": "passed", "fullName": "ok"},
            {"status": "pending", "fullName": "skip"},
            {"status": "failed", "fullName": "collector rejects", "failureMessages": ["bad frame"]}]}]}
        code, output = self.invoke(["frontend", "src/collector"], code=1, report=report)
        self.assertEqual(code, 1)
        self.assertIn("Tests: 2", output)
        self.assertIn("collector rejects", output)
        command = json.loads(next((self.root / "test-results/compact").glob("*/command.json")).read_text())["argv"]
        self.assertIn("src/collector", command)

    def test_ctest_failure_and_no_tests(self):
        code, output = self.invoke(["firmware", "--label", "ble"], code=8,
                                   report='<testsuite><testcase name="ble"><failure message="assertion failed"/></testcase></testsuite>')
        self.assertEqual(code, 8)
        self.assertIn("assertion failed", output)
        code, output = self.invoke(["firmware", "--label", "typo"], report="<testsuite/>")
        self.assertEqual(code, 1)
        self.assertIn("No tests executed", output)

    def test_missing_tool(self):
        code, output = self.invoke(["backend"], missing_tool=True)
        self.assertEqual(code, 127)
        self.assertIn("executable not found", output)

    def test_real_child_nonstandard_exit_is_preserved(self):
        command = [sys.executable, "-c", "import sys; print('Error: child failed'); sys.exit(23)"]
        output = io.StringIO()
        with patch.object(runner, "command_for", return_value=(command, self.root)), contextlib.redirect_stdout(output):
            code = runner.main(["backend"])
        self.assertEqual(code, 23)
        self.assertIn("Error: child failed", output.getvalue())

    def test_mixed_valid_invalid_domain_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            runner.main(["backend", "--domain", "node", "typo"])
        self.assertEqual(error.exception.code, 2)

    def test_posix_native_commands(self):
        # Exercise the POSIX branch even on a Windows development host.
        args = runner.argparse.Namespace(subsystem="backend", test="NodeTest", domain=None)
        with patch.object(runner.os, "name", "posix"):
            command, cwd = runner.command_for(args, self.root)
        self.assertTrue(command[0].endswith("mvnw"))
        self.assertEqual(cwd, self.root / "backend")
        self.assertIn("-Dtest=NodeTest", command)
        args = runner.argparse.Namespace(subsystem="frontend", paths=["src/platform"])
        with patch.object(runner.os, "name", "posix"), patch.object(runner.shutil, "which", return_value="/usr/bin/npm"):
            command, _ = runner.command_for(args, self.root)
        self.assertEqual(command[:5], ["/usr/bin/npm", "test", "--", "src/platform", "--reporter=json"])


if __name__ == "__main__":
    unittest.main()
