"""Real IDF configure regressions for stale SDKCONFIG and explicit release gates.

Run after the dev image build, against disposable CI configs only; no hardware.
"""
import os
from pathlib import Path
import subprocess
import sys

project = Path(__file__).resolve().parents[1] / 'idf'
idf = Path(os.environ['IDF_PATH']) / 'tools/idf.py'
source = (project / sys.argv[1]).read_text()


def reject(name, config, defaults, expected, *extra):
    sdkconfig = project / f'sdkconfig.local-ci-{name}'
    sdkconfig.write_text(config)
    directory = project / f'build-ci-{name}'
    result = subprocess.run([sys.executable, str(idf), '-C', str(project),
                             '-B', str(directory), '-D', f'SDKCONFIG={sdkconfig}',
                             '-D', f'SDKCONFIG_DEFAULTS={defaults}', *extra,
                             'reconfigure'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    directory.mkdir(exist_ok=True)
    (directory / 'clock-policy-guard.log').write_text(result.stdout)
    if result.returncode == 0 or expected not in result.stdout:
        raise RuntimeError(f'{name}: expected rejection {expected}; see {directory}/clock-policy-guard.log')
    # Generated config can be normalized by IDF; independent pin must survive.
    pins = [line for line in config.splitlines() if line.startswith('CONFIG_MM_ISSUER_PUBLIC_KEY_HEX=')]
    for pin in pins:
        if pin not in sdkconfig.read_text().splitlines():
            raise RuntimeError(f'{name}: issuer pin changed')
    print(f'PASS {name}: {expected}; issuer pin preserved')


reject('old-signed-dev', source.replace('CONFIG_MM_FIELD_ALPHA_PHONE_CLOCK=y',
       '# CONFIG_MM_FIELD_ALPHA_PHONE_CLOCK is not set'),
       'sdkconfig.defaults;sdkconfig.dev', 'Security clock policy mismatch',
       '-D', 'MM_DEVELOPMENT_BUILD=ON')
reject('explicit-signed-conflict', source, 'sdkconfig.defaults;sdkconfig.dev',
       'Security clock policy mismatch', '-D', 'MM_DEVELOPMENT_BUILD=ON',
       '-D', 'MM_SECURITY_CLOCK_POLICY=SIGNED_BACKEND_TIME')
reject('alpha-release-reject', 'CONFIG_MM_FIELD_ALPHA_PHONE_CLOCK=y\n',
       'sdkconfig.defaults;sdkconfig.field-alpha', 'forbidden in release',
       '-D', 'MM_DEVELOPMENT_BUILD=OFF')
