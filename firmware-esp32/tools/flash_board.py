"""Explicitly selected ESP32-C3 flash; preserve MiezMerker data partitions."""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from serial_capture import esp_ports

def partitions(data):
    import struct
    result = {}
    for offset in range(0, min(0xC00, len(data)), 32):
        entry = data[offset:offset+32]
        if len(entry) != 32 or entry[:2] != b'\xaa\x50':
            break
        _, kind, subtype, address, size, name, flags = struct.unpack('<HBBII16sI', entry)
        result[name.rstrip(b'\0').decode('ascii')] = (kind, subtype, address, size, flags)
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', required=True)
    parser.add_argument('--expected-mac', required=True, help='Chip MAC from a separate esptool chip-id inspection (not Node ID)')
    parser.add_argument('--build-dir', type=Path, required=True)
    args = parser.parse_args()
    if args.port.upper() not in [p.device.upper() for p in esp_ports()]:
        raise SystemExit('Refusing a port without Espressif native USB VID/PID')
    build = args.build_dir.resolve()
    flash = json.loads((build / 'flasher_args.json').read_text())
    if flash['flash_settings']['flash_size'] != '4MB':
        raise SystemExit('Expected a 4MB image')
    command = [sys.executable, '-m', 'esptool', '--chip', 'esp32c3', '--port', args.port]
    check = subprocess.run(command + ['chip-id'], text=True, capture_output=True)
    print(check.stdout)
    if check.returncode:
        print(check.stderr, file=sys.stderr)
        raise SystemExit(check.returncode)
    if args.expected_mac.lower() not in check.stdout.lower() or 'revision' not in check.stdout:
        raise SystemExit('Selected chip does not match expected hardware MAC')
    with tempfile.TemporaryDirectory(prefix='mm-partition-') as folder:
        old_table = Path(folder) / 'partitions.bin'
        subprocess.run(command + ['read-flash', '0x8000', '0x1000', str(old_table)], check=True)
        old = partitions(old_table.read_bytes())
    new = partitions((build / 'partition_table/partition-table.bin').read_bytes())
    if new.get('node_state') != (1, 2, 0x210000, 0x40000, 0) or new.get('observations') != (1, 2, 0x250000, 0x1B0000, 0):
        raise SystemExit('Unexpected MiezMerker data partition geometry')
    for name in ('node_state', 'observations'):
        if name in old and old[name] != new[name]:
            raise SystemExit(f'Refusing layout change of established {name}; explicit migration required')
    # No erase-flash, data-partition image, or --force. Only generated application images.
    files = flash['flash_files']
    allowed = {0, 0x8000, 0x10000}
    if {int(offset, 0) for offset in files} != allowed:
        raise SystemExit('Unexpected flash offsets; refusing to overwrite node state')
    subprocess.run(command + ['--before', 'default-reset', '--after', 'hard-reset',
        'write-flash', '@flash_args'], cwd=build, check=True)

if __name__ == '__main__':
    main()
