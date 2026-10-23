"""Profile/cache/release contract; runs the actual CMake guard without ESP-IDF."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

GUARD = Path(__file__).resolve().parents[1] / 'idf/clock_policy.cmake'


class ClockPolicyTest(unittest.TestCase):
    def run_guard(self, defaults, alpha, dev=False, allow=False, explicit=None):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'sdkconfig.local-dev'
            pin = 'CONFIG_MM_ISSUER_PUBLIC_KEY_HEX="04' + '12' * 64 + '"\n'
            config.write_text(pin + ('CONFIG_MM_FIELD_ALPHA_PHONE_CLOCK=y\n' if alpha else '# CONFIG_MM_FIELD_ALPHA_PHONE_CLOCK is not set\n'))
            before = config.read_bytes()
            command = [os.environ.get('CMAKE_COMMAND', 'cmake'),
                       f'-DSDKCONFIG_DEFAULTS={defaults}', f'-DSDKCONFIG={config}',
                       f'-DCONFIG_MM_FIELD_ALPHA_PHONE_CLOCK={"ON" if alpha else "OFF"}',
                       f'-DMM_DEVELOPMENT_BUILD={"ON" if dev else "OFF"}',
                       f'-DMM_ALLOW_FIELD_ALPHA={"ON" if allow else "OFF"}']
            if explicit is not None:
                command += [f'-DMM_SECURITY_CLOCK_POLICY={explicit}']
            result = subprocess.run(command + ['-P', str(GUARD)], capture_output=True, text=True)
            self.assertEqual(before, config.read_bytes(), 'Never migrate/erase issuer pins implicitly')
            return result.returncode, result.stdout + result.stderr

    def test_default_dev_alpha_and_clean_signed_production(self):
        for defaults, alpha, dev in [('sdkconfig.defaults;sdkconfig.dev', True, True),
                                     ('sdkconfig.defaults', False, False),
                                     ('sdkconfig.defaults;sdkconfig.dev;sdkconfig.signed-time', False, True)]:
            code, output = self.run_guard(defaults, alpha, dev)
            self.assertEqual(0, code, output)
            self.assertIn('security_clock_policy=', output)

    def test_old_cache_and_reverse_cache_fail_with_pin_preserving_remedy(self):
        for defaults, alpha, dev in [('sdkconfig.defaults;sdkconfig.dev', False, True),
                                     ('sdkconfig.defaults', True, False),
                                     ('sdkconfig.defaults;sdkconfig.dev;sdkconfig.signed-time', True, True)]:
            code, output = self.run_guard(defaults, alpha, dev)
            self.assertNotEqual(0, code)
            self.assertIn('Security clock policy mismatch', output)
            self.assertIn('Do not delete SDKCONFIG', output)

    def test_field_alpha_release_requires_explicit_acceptance(self):
        defaults = 'sdkconfig.defaults;sdkconfig.field-alpha'
        code, output = self.run_guard(defaults, True)
        self.assertNotEqual(0, code)
        self.assertIn('forbidden in release', output)
        code, output = self.run_guard(defaults, True, allow=True)
        self.assertEqual(0, code, output)

    def test_explicit_signed_never_silently_uses_alpha(self):
        code, output = self.run_guard('sdkconfig.defaults;sdkconfig.dev', True, True, explicit='SIGNED_BACKEND_TIME')
        self.assertNotEqual(0, code)
        self.assertIn('requested=SIGNED_BACKEND_TIME', output)
        code, output = self.run_guard('sdkconfig.defaults', False, explicit='typo')
        self.assertNotEqual(0, code)
        self.assertIn('Invalid MM_SECURITY_CLOCK_POLICY', output)

    def test_cached_selector_cannot_override_current_profile(self):
        # Both stale selectors and stale config agree, but today's raw dev
        # command requests alpha. Looking only at selector vs config misses it.
        code, output = self.run_guard('sdkconfig.defaults;sdkconfig.dev', False, True, explicit='SIGNED_BACKEND_TIME')
        self.assertNotEqual(0, code)
        self.assertIn('profile=FIELD_ALPHA_PHONE_CLOCK', output)
        self.assertIn('-U MM_SECURITY_CLOCK_POLICY', output)


if __name__ == '__main__':
    unittest.main()
