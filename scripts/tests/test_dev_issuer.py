"""Exercise the actual PowerShell setup, without printing any private material."""
import json
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'dev-issuer.ps1'
POWERSHELL = os.environ.get('DEV_ISSUER_POWERSHELL', 'pwsh')


@unittest.skipUnless(shutil.which(POWERSHELL), 'Selected PowerShell executable required')
class DevIssuerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'issuer'
        self.env = os.environ.copy()
        for name in ('ISSUER_PRIVATE_PKCS8_B64', 'ISSUER_PUBLIC_SPKI_B64'):
            self.env.pop(name, None)

    def run_setup(self, *args, directory=None, env=None):
        return subprocess.run([POWERSHELL, '-NoProfile', '-File', str(SCRIPT),
                               '-KeyDirectory', str(directory or self.directory), *args],
                              env=env or self.env, capture_output=True, text=True)

    def success(self, result):
        # The script's errors and public output must never contain key material.
        self.assertEqual(0, result.returncode, result.stderr)
        pair = json.loads((self.directory / 'issuer.json').read_text())
        self.assertTrue(pair['private'] not in result.stdout + result.stderr, 'Private key leaked')
        return pair

    def test_repeated_setup_preserves_pair_pin_and_defaults_closed(self):
        first = self.success(self.run_setup('-AttestHealthyUtc'))
        store = (self.directory / 'issuer.json').read_bytes()
        defaults = (self.directory / 'sdkconfig.issuer.defaults').read_bytes()
        second = self.run_setup('-CheckFirmwarePin', str(self.directory / 'sdkconfig.issuer.defaults'))
        self.assertTrue(first == self.success(second), 'Pair changed')
        self.assertTrue(store == (self.directory / 'issuer.json').read_bytes(), 'Store changed')
        self.assertEqual(defaults, (self.directory / 'sdkconfig.issuer.defaults').read_bytes())
        self.assertIn('false (no live NTP monitoring)', second.stdout)
        self.assertEqual(130, len(defaults.decode().split('"')[1]))
        props = (self.directory / 'backend-issuer.properties').read_text()
        self.assertTrue('private-key-pkcs8-b64=' + first['private'] in props, 'Export differs')
        self.assertTrue('clock-healthy' not in props, 'Persistent UTC attestation')

    def test_missing_wrong_pin_and_corrupt_store_are_never_replaced(self):
        self.success(self.run_setup())
        store = (self.directory / 'issuer.json').read_bytes()
        config = Path(self.temp.name) / 'sdkconfig'
        for pin in ('', '04' + '00' * 64):
            config.write_text('CONFIG_MM_ISSUER_PUBLIC_KEY_HEX="' + pin + '"\n')
            result = self.run_setup('-CheckFirmwarePin', str(config))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('missing or mismatched', result.stderr)
            self.assertTrue(store == (self.directory / 'issuer.json').read_bytes(), 'Store changed')
        for corrupt in ('{broken', '{"private":"","public":""}'):
            (self.directory / 'issuer.json').write_text(corrupt)
            self.assertNotEqual(0, self.run_setup().returncode)
            self.assertEqual(corrupt, (self.directory / 'issuer.json').read_text())

    def test_explicit_import_and_override_rejection(self):
        pair = self.success(self.run_setup())
        imported = Path(self.temp.name) / 'imported'
        env = dict(self.env, ISSUER_PRIVATE_PKCS8_B64=pair['private'], ISSUER_PUBLIC_SPKI_B64=pair['public'])
        self.assertNotEqual(0, self.run_setup(directory=imported, env=env).returncode)
        self.assertEqual(0, self.run_setup('-ImportFromEnvironment', directory=imported, env=env).returncode)
        self.assertTrue(pair == json.loads((imported / 'issuer.json').read_text()), 'Import differs')
        other = Path(self.temp.name) / 'other'
        self.assertEqual(0, self.run_setup(directory=other).returncode)
        other_pair = json.loads((other / 'issuer.json').read_text())
        env['ISSUER_PUBLIC_SPKI_B64'] = other_pair['public']
        self.assertNotEqual(0, self.run_setup('-ImportFromEnvironment', env=env).returncode)
        self.assertTrue(pair == json.loads((self.directory / 'issuer.json').read_text()), 'Store changed')
        self.assertNotEqual(0, self.run_setup('-ImportFromEnvironment', directory=Path(self.temp.name) / 'bad', env=env).returncode)

    def test_pin_conversion_and_fingerprint_match_spki_point(self):
        pair = self.success(self.run_setup())
        # P-256 named-curve SPKI ends in the uncompressed 65-byte point.
        point = base64.b64decode(pair['public'])[-65:]
        self.assertEqual(4, point[0])
        pin = (self.directory / 'sdkconfig.issuer.defaults').read_text().split('"')[1]
        self.assertEqual(point.hex(), pin)
        result = self.run_setup()
        self.assertIn(hashlib.sha256(point).hexdigest(), result.stdout)

    def test_conflicting_exports_and_environment_fail_before_writes(self):
        pair = self.success(self.run_setup())
        paths = [self.directory / name for name in
                 ('issuer.json', 'backend-issuer.properties', 'sdkconfig.issuer.defaults')]
        original = [p.read_bytes() for p in paths]
        for path, invalid in ((paths[1], b'broken properties'),
                              (paths[2], b'CONFIG_MM_ISSUER_PUBLIC_KEY_HEX=""\n')):
            path.write_bytes(invalid)
            before = [p.read_bytes() for p in paths]
            result = self.run_setup('-AttestHealthyUtc')
            self.assertNotEqual(0, result.returncode)
            self.assertTrue(before == [p.read_bytes() for p in paths], 'Conflict modified files')
            self.assertTrue(pair['private'] not in result.stdout + result.stderr, 'Private key leaked')
            for p, content in zip(paths, original):
                p.write_bytes(content)
        result = self.run_setup(env=dict(self.env, ISSUER_PUBLIC_SPKI_B64='conflict'))
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(original == [p.read_bytes() for p in paths], 'Override modified files')
        paths[0].unlink()
        paths[1].unlink()
        result = self.run_setup()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(paths[0].exists(), 'Orphaned pin caused key rotation')

    def test_duplicate_or_malformed_pin_is_rejected(self):
        self.success(self.run_setup())
        valid = (self.directory / 'sdkconfig.issuer.defaults').read_text()
        config = Path(self.temp.name) / 'sdkconfig'
        for suffix in (valid, 'CONFIG_MM_ISSUER_PUBLIC_KEY_HEX=""\n',
                       ' CONFIG_MM_ISSUER_PUBLIC_KEY_HEX =garbage\n'):
            config.write_text(valid + suffix)
            self.assertNotEqual(0, self.run_setup('-CheckFirmwarePin', str(config)).returncode)

    def test_wrong_curve_and_invalid_base64_are_rejected(self):
        pair = self.success(self.run_setup())
        for key in ('private', 'public'):
            for value in ('not-base64!', base64.b64encode(b'not DER').decode(),
                          # Change named-curve OID prime256v1 to prime192v1.
                          base64.b64encode(base64.b64decode(pair[key]).replace(
                              bytes.fromhex('06082a8648ce3d030107'),
                              bytes.fromhex('06082a8648ce3d030101'))).decode()):
                altered = dict(pair, **{key: value})
                store = json.dumps(altered)
                (self.directory / 'issuer.json').write_text(store)
                result = self.run_setup()
                self.assertNotEqual(0, result.returncode)
                self.assertTrue(store == (self.directory / 'issuer.json').read_text(), 'Invalid store changed')
                self.assertTrue(pair['private'] not in result.stdout + result.stderr, 'Private key leaked')

    def test_repository_secret_storage_refused(self):
        self.assertNotEqual(0, self.run_setup(directory=SCRIPT.parent / 'issuer').returncode)

    def test_trailing_der_is_rejected_without_replacing_store(self):
        pair = self.success(self.run_setup())
        for key in ('private', 'public'):
            altered = dict(pair)
            altered[key] = base64.b64encode(base64.b64decode(pair[key]) + b'\x00').decode('ascii')
            store = json.dumps(altered)
            (self.directory / 'issuer.json').write_text(store)
            result = self.run_setup()
            self.assertNotEqual(0, result.returncode)
            self.assertTrue(altered['private'] not in result.stdout + result.stderr, 'Private key leaked')
            self.assertTrue(store == (self.directory / 'issuer.json').read_text(), 'Store changed')

    def test_shell_environment_and_private_storage_permissions(self):
        self.success(self.run_setup())
        def quote(value):
            return "'" + str(value).replace("'", "''") + "'"
        command = "$env:SPRING_CONFIG_ADDITIONAL_LOCATION='file:///existing-local.properties'; "
        command += f"& {quote(SCRIPT)} -KeyDirectory {quote(self.directory)} -AttestHealthyUtc; "
        command += "if ($env:TIME_BOOTSTRAP_CLOCK_HEALTHY -ne 'true') { throw 'attestation not exported' }; "
        command += "if (!$env:SPRING_CONFIG_ADDITIONAL_LOCATION.StartsWith('file:///existing-local.properties,file:')) { throw 'existing config lost' }; "
        command += "if (Get-Variable pair -ErrorAction SilentlyContinue) { throw 'secret escaped script scope' }; "
        command += f"& {quote(SCRIPT)} -KeyDirectory {quote(self.directory)}; "
        command += "if ($env:TIME_BOOTSTRAP_CLOCK_HEALTHY -ne 'false') { throw 'unchecked attestation stayed open' }; "
        command += "if ($env:SPRING_CONFIG_ADDITIONAL_LOCATION.Split(',').Length -ne 2) { throw 'duplicate import' }; "
        command += f"$dir = {quote(self.directory)}; "
        command += "if ($env:OS -eq 'Windows_NT') { $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; "
        command += "foreach ($item in @((Get-Item -LiteralPath $dir)) + @(Get-ChildItem -LiteralPath $dir -File)) { "
        command += "if ($PSVersionTable.PSEdition -eq 'Desktop') { $acl=$item.GetAccessControl() } else { $acl=Get-Acl -LiteralPath $item.FullName }; "
        command += "foreach ($rule in $acl.Access) { if ($rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid) { throw 'foreign ACL principal' } } } "
        command += "} else { if ([int][IO.File]::GetUnixFileMode($dir) -ne 448) { throw 'directory mode' }; "
        command += "foreach ($file in Get-ChildItem -LiteralPath $dir -File) { if ([int][IO.File]::GetUnixFileMode($file.FullName) -ne 384) { throw 'file mode' } } }"
        result = subprocess.run([POWERSHELL, '-NoProfile', '-Command', command], env=self.env, capture_output=True, text=True)
        self.success(result)

    @unittest.skipUnless(os.name == 'nt' and shutil.which('powershell') and shutil.which('pwsh'), 'Both Windows shells required')
    def test_keys_are_reused_across_windows_powershell_and_pwsh(self):
        self.success(self.run_setup())
        pair = (self.directory / 'issuer.json').read_bytes()
        pin = (self.directory / 'sdkconfig.issuer.defaults').read_bytes()
        for shell in ('powershell', 'pwsh'):
            result = subprocess.run([shell, '-NoProfile', '-File', str(SCRIPT), '-KeyDirectory', str(self.directory)],
                                    env=self.env, capture_output=True, text=True)
            self.success(result)
            self.assertTrue(pair == (self.directory / 'issuer.json').read_bytes(), 'Store changed')
            self.assertEqual(pin, (self.directory / 'sdkconfig.issuer.defaults').read_bytes())

    @unittest.skipUnless(os.name == 'nt' and shutil.which('powershell'), 'Windows PowerShell required')
    def test_legacy_pipe_is_independent_of_utf8_bom_output_encoding(self):
        self.success(self.run_setup())
        def quote(value):
            return "'" + str(value).replace("'", "''") + "'"
        command = "$OutputEncoding = [Text.UTF8Encoding]::new($true); "
        command += "[Console]::InputEncoding = [Text.UTF8Encoding]::new($true); "
        command += f"& {quote(SCRIPT)} -KeyDirectory {quote(self.directory)}; "
        command += "if (!$OutputEncoding.GetPreamble().Length -or ![Console]::InputEncoding.GetPreamble().Length) { throw 'caller encoding changed' }"
        result = subprocess.run(['powershell', '-NoProfile', '-Command', command], env=self.env,
                                capture_output=True, text=True)
        self.success(result)


if __name__ == '__main__':
    unittest.main()
