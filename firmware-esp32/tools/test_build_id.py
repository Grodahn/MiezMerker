"""Cheap local build-ID checks; no ESP-IDF build or hardware required."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "idf/main/build_id.cmake"


class BuildIdTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.header = self.root / "build_id.hpp"

    def git(self, *args):
        return subprocess.check_output(
            ["git", "-C", str(self.source), *args], text=True,
            stderr=subprocess.STDOUT).strip()

    def generate(self, *options):
        subprocess.run([
            "cmake", f"-DMM_SOURCE_DIR={self.source}",
            f"-DMM_OUTPUT_FILE={self.header}", *options, "-P", str(SCRIPT)
        ], check=True, capture_output=True, text=True)
        return self.header.read_text()

    def init_repo(self):
        self.git("init", "-q")
        self.git("config", "user.name", "Build ID test")
        self.git("config", "user.email", "build-id@example.invalid")
        self.tracked = self.source / "tracked.txt"
        self.tracked.write_text("first\n")
        self.git("add", ".")
        self.git("commit", "-qm", "first")
        return self.git("rev-parse", "--short=7", "HEAD")

    def test_incremental_clean_dirty_commit_and_checkout(self):
        first = self.init_repo()
        clean = self.generate()
        self.assertIn(f'"{first}"', clean)
        # No rewrite on an idle build (use a distinctive old timestamp).
        os.utime(self.header, (1000000000, 1000000000))
        before = self.header.stat().st_mtime_ns
        self.assertEqual(self.generate(), clean)
        self.assertEqual(self.header.stat().st_mtime_ns, before)
        (self.source / "untracked").write_text("ignored by policy")
        self.assertEqual(self.generate(), clean)
        self.tracked.write_text("second\n")
        self.assertIn(f'"{first}-dirty"', self.generate())
        self.git("add", "tracked.txt")
        self.assertIn(f'"{first}-dirty"', self.generate())
        self.git("commit", "-qm", "second")
        second = self.git("rev-parse", "--short=7", "HEAD")
        self.assertNotEqual(first, second)
        self.assertIn(f'"{second}"', self.generate())
        self.git("checkout", "--detach", first)
        self.assertEqual(self.generate(), clean)

    def test_archive_fallback(self):
        self.assertIn('"unknown"', self.generate())

    def test_incremental_build_target(self):
        first = self.init_repo()
        # Exercise the actual dependency/command block without the ESP-IDF SDK.
        main_cmake = (SCRIPT.parent / "CMakeLists.txt").read_text()
        block = main_cmake.split("# Independent of PROJECT_VER:", 1)[1]
        block = "# Independent of PROJECT_VER:" + block.split(
            "target_include_directories", 1)[0]
        for name in ("build_id.cmake", "build_id.hpp.in"):
            (self.source / name).write_bytes((SCRIPT.parent / name).read_bytes())
        (self.source / "CMakeLists.txt").write_text(
            "cmake_minimum_required(VERSION 3.22)\n"
            "project(BuildIdCheck LANGUAGES NONE)\n"
            "add_custom_target(consumer)\nset(COMPONENT_LIB consumer)\n" + block)
        build = self.root / "build"
        subprocess.run(["cmake", "-G", "Ninja", "-S", str(self.source), "-B", str(build)],
                       check=True, capture_output=True, text=True)
        command = ["cmake", "--build", str(build), "--target", "consumer"]
        subprocess.run(command, check=True, capture_output=True, text=True)
        header = build / "build_id.hpp"
        self.assertIn(f'"{first}"', header.read_text())
        self.tracked.write_text("dirty\n")
        subprocess.run(command, check=True, capture_output=True, text=True)
        self.assertIn(f'"{first}-dirty"', header.read_text())
        os.utime(header, (1000000000, 1000000000))
        before = header.stat().st_mtime_ns
        subprocess.run(command, check=True, capture_output=True, text=True)
        self.assertEqual(header.stat().st_mtime_ns, before)

    def test_missing_git_fallback(self):
        self.init_repo()
        self.assertIn('"unknown"', self.generate("-DCMAKE_DISABLE_FIND_PACKAGE_Git=TRUE"))


if __name__ == "__main__":
    unittest.main()
