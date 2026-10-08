#!/usr/bin/env python3
"""Compact native test runners; Python 3.10+, standard library only."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DOMAINS = ("node", "auth", "ingest", "visits", "admin", "schema", "system")


def backend_selector(value):
    # Surefire silently ignores unmatched members of composite selections.
    # Keep this interface to one exact class/method; domains provide unions.
    if not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:#[A-Za-z_$][\w$]*)?", value):
        raise argparse.ArgumentTypeError("select one exact class or Class#method; use --domain for unions")
    return value


def xml_results(files):
    executed = 0
    failures = []
    for file in files:
        root = ET.parse(file).getroot()
        for case in root.iter("testcase"):
            if case.find("skipped") is not None:
                continue
            executed += 1
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                name = ".".join(filter(None, [case.get("classname"), case.get("name")]))
                failures.append((name, problem.get("message") or problem.text or problem.tag))
    return executed, failures


def json_results(file):
    report = json.loads(file.read_text(encoding="utf-8"))
    executed = 0
    failures = []
    for suite in report.get("testResults", []):
        for case in suite.get("assertionResults", []):
            if case["status"] in ("passed", "failed"):
                executed += 1
            if case["status"] == "failed":
                failures.append((case["fullName"], "\n".join(case.get("failureMessages", []))))
        if suite.get("message") and suite.get("status") == "failed":
            failures.append((suite["name"], suite["message"]))
    return executed, failures


def excerpt(message, limit=6):
    # Bound both lines and line length; stacks remain in reports and the full log.
    return "\n".join(line[:400] for line in message.strip().splitlines()[:limit])


def diagnostics(log):
    pattern = re.compile(r"\[ERROR\]|error:|Error:|Caused by:|FAIL|No tests|not found|Cannot find|CMake Error", re.I)
    lines = []
    with log.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            if pattern.search(line):
                line = line.strip()[:400]
                if line not in lines:
                    lines.append(line)
                if len(lines) == 12:
                    break
    return "\n".join(lines)


def command_for(args, run):
    if args.subsystem == "backend":
        # A fresh, unique Surefire directory isolates current XML from previous runs.
        command = [str(ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw")),
                   "-B", "-ntp", "test", f"-Dtest.reportsDirectory={run / 'surefire'}"]
        if args.test:
            command.append(f"-Dtest={args.test}")
        if args.domain:
            # Surefire accepts comma-separated tags as a union. Unlike a pipe,
            # this is also safe when Windows launches Maven's .cmd wrapper.
            command.append("-Dgroups=" + ",".join(args.domain))
        return command, ROOT / "backend"
    if args.subsystem == "frontend":
        npm = shutil.which("npm.cmd" if os.name == "nt" else "npm") or "npm"
        return [npm, "test", "--", *args.paths, "--reporter=json",
                f"--outputFile={run / 'vitest.json'}"], ROOT / "app"
    command = ["ctest", "--test-dir", str(args.build_dir.resolve()),
               "--no-tests=error", "--output-on-failure", "--output-junit", str(run / "ctest.xml")]
    if args.label:
        command.extend(["-L", args.label])
    if args.test:
        command.extend(["-R", args.test])
    if args.config:
        command.extend(["-C", args.config])
    return command, ROOT


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="subsystem", required=True)
    backend = subs.add_parser("backend", help="Maven class/method, domain union, or full suite")
    selection = backend.add_mutually_exclusive_group()
    selection.add_argument("--test", type=backend_selector, help="Exact class or Class#method, e.g. NodeClaimTest#memberCannotClaim")
    selection.add_argument("--domain", nargs="+", choices=DOMAINS)
    frontend = subs.add_parser("frontend", help="Native Vitest file/directory filters")
    frontend.add_argument("paths", nargs="*")
    firmware = subs.add_parser("firmware", help="CTest in an already configured and built directory")
    firmware.add_argument("--build-dir", type=Path, default=ROOT / "build")
    firmware.add_argument("--label", help="Native CTest label regex")
    firmware.add_argument("--test", help="Native CTest test name regex")
    firmware.add_argument("--config", help="Configuration for multi-config generators")
    args = parser.parse_args(argv)
    selection = getattr(args, "domain", None) or getattr(args, "test", None) or getattr(args, "paths", None) or getattr(args, "label", None) or "all"
    if isinstance(selection, list):
        selection = "+".join(selection)
    title = f"{args.subsystem}/{selection}"
    base = ROOT / "test-results" / "compact"
    base.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-")
    run = Path(tempfile.mkdtemp(prefix=stamp, dir=base))
    log = run / "output.log"
    command, cwd = command_for(args, run)
    (run / "command.json").write_text(json.dumps({"argv": command, "cwd": str(cwd)}, indent=2), encoding="utf-8")
    start = time.monotonic()
    with log.open("w", encoding="utf-8") as output:
        try:
            code = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT, check=False).returncode
        except OSError as error:
            output.write(str(error))
            code = 127
    count, failures = 0, []
    try:
        if args.subsystem == "backend":
            count, failures = xml_results((run / "surefire").glob("TEST-*.xml"))
        elif args.subsystem == "frontend":
            count, failures = json_results(run / "vitest.json")
        else:
            count, failures = xml_results([run / "ctest.xml"])
    except (OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError) as error:
        failures.append(("Report unavailable or invalid", str(error)))
    # Preserve any native failure code. Only turn a false success into a failure.
    if code == 0 and (count == 0 or failures):
        code = 1
    print(f"{'PASS' if code == 0 else 'FAIL'} {title}")
    print(f"Tests: {count}" + (f" | Failed: {len(failures)}" if failures else ""))
    print(f"Duration: {time.monotonic() - start:.1f}s")
    if code:
        if count == 0:
            print("No tests executed; selection, build, startup, or report generation failed.")
        if failures:
            print("\nFailures:")
            for name, message in failures[:10]:
                print(f"- {name}\n{excerpt(message)}")
            if len(failures) > 10:
                print(f"... {len(failures) - 10} more failures in reports")
        details = diagnostics(log)
        if details:
            print("\nDiagnostics:\n" + details)
        print(f"\nDetailed logs: {log}\nReports: {run}")
    return code


if __name__ == "__main__":
    sys.exit(main())
