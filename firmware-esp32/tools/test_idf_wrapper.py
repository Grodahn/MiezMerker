"""Windows wrapper contract with a fake IDF, no SDK downloads or compilation."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('idf.ps1')
SHELL = os.environ.get('IDF_TEST_POWERSHELL', 'pwsh')


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


@unittest.skipUnless(os.name == 'nt' and shutil.which(SHELL), 'Windows PowerShell required')
class IdfWrapperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='idf wrapper ')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.tools = cls.root / 'tools'
        subprocess.run([sys.executable, '-m', 'venv', '--without-pip',
                        str(cls.tools / 'python/v6.1/venv')], check=True, capture_output=True)
        for folder in ('cmake/1/bin', 'ninja/1', 'riscv32-esp-elf/1/riscv32-esp-elf/bin',
                       'riscv32-esp-elf-gdb/1/riscv32-esp-elf-gdb/bin'):
            (cls.tools / folder).mkdir(parents=True)
        (cls.root / 'idf/tools').mkdir(parents=True)
        (cls.root / 'idf/tools/idf.py').write_text(
            "import json, os, sys\n"
            "from pathlib import Path\n"
            "Path(os.environ['WRAPPER_REPORT']).write_text(json.dumps({"
            "'args': sys.argv[1:], 'env': dict(os.environ)}))\n"
            "sys.exit(int(os.environ.get('WRAPPER_EXIT', '0')))\n")

    def invoke(self, *options, cache=True, exit_code=0):
        cache_bin = self.root / 'cache bin'
        cache_bin.mkdir(exist_ok=True)
        (cache_bin / 'ccache.cmd').write_text('@exit /b 0\n')
        report = self.root / 'report.json'
        report.unlink(missing_ok=True)
        env = dict(os.environ, IDF_PATH=str(self.root / 'idf'),
                   IDF_TOOLS_PATH=str(self.tools), IDF_CCACHE_ENABLE='0',
                   CCACHE_COMPILERCHECK='mtime', CCACHE_MAXSIZE='2G',
                   CCACHE_DIR=str(self.root / 'compiler cache'),
                   WRAPPER_REPORT=str(report), WRAPPER_EXIT=str(exit_code))
        env['PATH'] = str(Path(os.environ['SystemRoot']) / 'System32')
        if cache:
            env['PATH'] = str(cache_bin) + ';' + env['PATH']
        command = (
            "$ErrorActionPreference = 'Stop'; "
            "$before = @{}; Get-ChildItem Env: | ForEach-Object { $before[$_.Name] = $_.Value }; "
            f"& {quote(SCRIPT)} {' '.join(options)} -IdfArguments "
            "@('-B','build-production','-D','SDKCONFIG=sdkconfig.local-production',"
            "'-D','SDKCONFIG_DEFAULTS=sdkconfig.defaults','-D','MM_DEVELOPMENT_BUILD=OFF','app'); "
            "$code = $LASTEXITCODE; "
            "$after = @{}; Get-ChildItem Env: | ForEach-Object { $after[$_.Name] = $_.Value }; "
            "foreach ($key in @($before.Keys) + @($after.Keys)) { "
            "if ($key -ne '_' -and $before[$key] -cne $after[$key]) { throw ('Environment leaked: ' + $key) } }; "
            "exit $code")
        result = subprocess.run([shutil.which(SHELL), '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
                                env=env, capture_output=True, text=True)
        return result, json.loads(report.read_text()) if report.exists() else None

    def test_opt_in_environment_arguments_and_native_failure(self):
        result, data = self.invoke('-Ccache', exit_code=37)
        self.assertEqual(37, result.returncode, result.stderr)
        self.assertEqual(['-B', 'build-production', '-D', 'SDKCONFIG=sdkconfig.local-production',
                          '-D', 'SDKCONFIG_DEFAULTS=sdkconfig.defaults', '-D',
                          'MM_DEVELOPMENT_BUILD=OFF', 'app'], data['args'])
        self.assertEqual('1', data['env']['IDF_CCACHE_ENABLE'])
        self.assertEqual('content', data['env']['CCACHE_COMPILERCHECK'])
        self.assertEqual('1G', data['env']['CCACHE_MAXSIZE'])
        self.assertEqual(str(self.root / 'compiler cache'), data['env']['CCACHE_DIR'])

    def test_no_opt_in_preserves_existing_cache_policy(self):
        result, data = self.invoke(cache=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('0', data['env']['IDF_CCACHE_ENABLE'])
        self.assertEqual('mtime', data['env']['CCACHE_COMPILERCHECK'])
        self.assertEqual('2G', data['env']['CCACHE_MAXSIZE'])

    def test_missing_explicit_cache_fails_before_idf(self):
        result, data = self.invoke('-Ccache', cache=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('ccache is missing', result.stderr)
        self.assertIsNone(data)

    def test_eim_cache_detection_and_explicit_directory(self):
        cache = self.tools / 'ccache/1/ccache-test/ccache.exe'
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(b'not executed: fake IDF only inspects environment')
        try:
            result, data = self.invoke('-Ccache', '-CcacheDir', quote(self.root / 'override'), cache=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(str(self.root / 'override'), data['env']['CCACHE_DIR'])
            self.assertIn(str(cache.parent), data['env']['PATH'])
        finally:
            cache.unlink()


if __name__ == '__main__':
    unittest.main()
