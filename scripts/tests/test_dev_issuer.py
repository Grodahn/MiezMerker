"""Exercise the actual PowerShell setup, without printing any private material."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'dev-issuer.ps1'


@unittest.skipUnless(shutil.which('pwsh'), 'PowerShell 7.2+ required')
class DevIssuerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'issuer'
        self.env = os.environ.copy()
        for name in ('ISSUER_PRIVATE_PKCS8_B64', 'ISSUER_PUBLIC_SPKI_B64'):
            self.env.pop(name, None)

    def run_setup(self, *args, directory=None, env=None):
        return subprocess.run(['pwsh', '-NoProfile', '-File', str(SCRIPT),
                               '-KeyDirectory', str(directory or self.directory), *args],
                              env=env or self.env, capture_output=True, text=True)

    def success(self, result):
        # The script's errors and public output must never contain key material.
        self.assertEqual(0, result.returncode, result.stderr)
        pair = json.loads((self.directory / 'issuer.json').read_text())
        self.assertNotIn(pair['private'], result.stdout + result.stderr)
        return pair

    def test_repeated_setup_preserves_pair_pin_and_defaults_closed(self):
        first = self.success(self.run_setup('-AttestHealthyUtc'))
        store = (self.directory / 'issuer.json').read_bytes()
        defaults = (self.directory / 'sdkconfig.issuer.defaults').read_bytes()
        second = self.run_setup('-CheckFirmwarePin', str(self.directory / 'sdkconfig.issuer.defaults'))
        self.assertEqual(first, self.success(second))
        self.assertEqual(store, (self.directory / 'issuer.json').read_bytes())
        self.assertEqual(defaults, (self.directory / 'sdkconfig.issuer.defaults').read_bytes())
        self.assertIn('false (no live NTP monitoring)', second.stdout)
        self.assertEqual(130, len(defaults.decode().split('"')[1]))
        props = (self.directory / 'backend-issuer.properties').read_text()
        self.assertIn('private-key-pkcs8-b64=' + first['private'], props)
        self.assertNotIn('clock-healthy', props)

    def test_missing_wrong_pin_and_corrupt_store_are_never_replaced(self):
        self.success(self.run_setup())
        store = (self.directory / 'issuer.json').read_bytes()
        config = Path(self.temp.name) / 'sdkconfig'
        for pin in ('', '04' + '00' * 64):
            config.write_text('CONFIG_MM_ISSUER_PUBLIC_KEY_HEX="' + pin + '"\n')
            result = self.run_setup('-CheckFirmwarePin', str(config))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('missing or mismatched', result.stderr)
            self.assertEqual(store, (self.directory / 'issuer.json').read_bytes())
        (self.directory / 'issuer.json').write_text('{broken')
        self.assertNotEqual(0, self.run_setup().returncode)
        self.assertEqual('{broken', (self.directory / 'issuer.json').read_text())

    def test_explicit_import_and_override_rejection(self):
        pair = self.success(self.run_setup())
        imported = Path(self.temp.name) / 'imported'
        env = dict(self.env, ISSUER_PRIVATE_PKCS8_B64=pair['private'], ISSUER_PUBLIC_SPKI_B64=pair['public'])
        self.assertNotEqual(0, self.run_setup(directory=imported, env=env).returncode)
        self.assertEqual(0, self.run_setup('-ImportFromEnvironment', directory=imported, env=env).returncode)
        self.assertEqual(pair, json.loads((imported / 'issuer.json').read_text()))
        other = Path(self.temp.name) / 'other'
        self.assertEqual(0, self.run_setup(directory=other).returncode)
        other_pair = json.loads((other / 'issuer.json').read_text())
        env['ISSUER_PUBLIC_SPKI_B64'] = other_pair['public']
        self.assertNotEqual(0, self.run_setup('-ImportFromEnvironment', env=env).returncode)
        self.assertEqual(pair, json.loads((self.directory / 'issuer.json').read_text()))
        self.assertNotEqual(0, self.run_setup('-ImportFromEnvironment', directory=Path(self.temp.name) / 'bad', env=env).returncode)

    def test_repository_secret_storage_refused(self):
        self.assertNotEqual(0, self.run_setup(directory=SCRIPT.parent / 'issuer').returncode)

    def test_shell_environment_and_private_storage_permissions(self):
        self.success(self.run_setup())
        def quote(value):
            return "'" + str(value).replace("'", "''") + "'"
        command = f"& {quote(SCRIPT)} -KeyDirectory {quote(self.directory)} -AttestHealthyUtc; "
        command += "if ($env:TIME_BOOTSTRAP_CLOCK_HEALTHY -ne 'true') { throw 'attestation not exported' }; "
        command += "if (!$env:SPRING_CONFIG_ADDITIONAL_LOCATION.StartsWith('file:')) { throw 'missing import' }; "
        command += "if (Get-Variable pair -ErrorAction SilentlyContinue) { throw 'secret escaped script scope' }; "
        command += f"& {quote(SCRIPT)} -KeyDirectory {quote(self.directory)}; "
        command += "if ($env:TIME_BOOTSTRAP_CLOCK_HEALTHY -ne 'false') { throw 'unchecked attestation stayed open' }; "
        command += f"$dir = {quote(self.directory)}; "
        command += "if ($IsWindows) { $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; "
        command += "foreach ($item in @((Get-Item -LiteralPath $dir)) + @(Get-ChildItem -LiteralPath $dir -File)) { "
        command += "$acl=Get-Acl -LiteralPath $item.FullName; "
        command += "foreach ($rule in $acl.Access) { if ($rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid) { throw 'foreign ACL principal' } } } "
        command += "} else { if ([int][IO.File]::GetUnixFileMode($dir) -ne 448) { throw 'directory mode' }; "
        command += "foreach ($file in Get-ChildItem -LiteralPath $dir -File) { if ([int][IO.File]::GetUnixFileMode($file.FullName) -ne 384) { throw 'file mode' } } }"
        result = subprocess.run(['pwsh', '-NoProfile', '-Command', command], env=self.env, capture_output=True, text=True)
        self.success(result)


if __name__ == '__main__':
    unittest.main()
