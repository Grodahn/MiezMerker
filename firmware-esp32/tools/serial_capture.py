"""Bounded USB console capture. Explicit port selection; never erases or flashes."""
import argparse
import time
from pathlib import Path
import serial
from serial.tools import list_ports

def esp_ports():
    return [p for p in list_ports.comports() if p.vid == 0x303A and p.pid == 0x1001]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port')
    parser.add_argument('--seconds', type=float, default=12)
    parser.add_argument('--command', action='append', default=[])
    parser.add_argument('--reset', action='store_true')
    parser.add_argument('--interrupt-after-ms', type=int, help='Normal reset shortly after sending a dev command (1..1000ms)')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    ports = esp_ports()
    if not args.port:
        for port in ports:
            print(f'{port.device}: {port.description} USB serial={port.serial_number}')
        return
    if args.port.upper() not in [p.device.upper() for p in ports]:
        raise SystemExit('Refusing a port without Espressif native USB VID/PID; run esptool chip-id separately.')
    if not 1 <= args.seconds <= 60:
        raise SystemExit('Capture duration must be 1..60 seconds')
    if args.interrupt_after_ms is not None and (not args.command or not 1 <= args.interrupt_after_ms <= 1000):
        raise SystemExit('Interruption requires a dev command and 1..1000ms delay')
    # Configure line states before open to avoid an accidental BOOT assertion.
    console = serial.Serial(baudrate=115200, timeout=0.1)
    console.dtr = console.rts = False
    console.port = args.port
    output = bytearray()
    with console:
        if args.reset:
            from esptool.reset import HardReset
            HardReset(console, uses_usb=True)()
        start = time.monotonic()
        sent = False
        while time.monotonic() - start < args.seconds:
            if not sent and time.monotonic() - start >= 2:
                for command in args.command:
                    console.write((command + '\n').encode('ascii'))
                if args.interrupt_after_ms is not None:
                    time.sleep(args.interrupt_after_ms / 1000)
                    output.extend(console.read(console.in_waiting))
                    from esptool.reset import HardReset
                    HardReset(console, uses_usb=True)()
                sent = True
            output.extend(console.read(max(1, min(console.in_waiting, 4096))))
    text = output.decode('utf-8', errors='replace')
    print(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding='utf-8')
    if 'waiting for download' in text:
        raise SystemExit('ROM download mode: release BOOT, press RESET once; do not reflash.')

if __name__ == '__main__':
    main()
