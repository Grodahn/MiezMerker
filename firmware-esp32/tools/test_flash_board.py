"""Hardware-free regression of the flash helper's preservation boundaries."""
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import flash_board


class FlashSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.build = Path(self.temp.name)
        self.files = {
            '0x0': 'bootloader/bootloader.bin',
            '0x8000': 'partition_table/partition-table.bin',
            '0x10000': 'miezmerker-node.bin',
        }
        self.table = b''.join(struct.pack('<HBBII16sI', 0x50aa, 1, 2, address, size, name.encode(), 0)
            for name, address, size in [('node_state', 0x210000, 0x40000), ('observations', 0x250000, 0x1b0000)])
        for file in self.files.values():
            target = self.build / file
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(self.table if file.startswith('partition_table') else b'firmware')
        self.flash = {'flash_settings': {'flash_size': '4MB', 'flash_mode': 'dio', 'flash_freq': '80m'},
            'flash_files': self.files}
        self.writes = []

    def run_helper(self):
        (self.build / 'flasher_args.json').write_text(json.dumps(self.flash))
        def run(command, **kwargs):
            if 'chip-id' in command:
                return SimpleNamespace(returncode=0, stdout='ESP32-C3 revision v1.1 MAC: 44:b1:76:18:ca:78', stderr='')
            if 'read-flash' in command:
                Path(command[-1]).write_bytes(self.table)
            if 'write-flash' in command:
                self.writes.append(command)
            return SimpleNamespace(returncode=0)
        with patch.object(sys, 'argv', ['flash_board.py', '--port', 'COM6', '--expected-mac',
                '44:b1:76:18:ca:78', '--build-dir', str(self.build)]), \
             patch.object(flash_board, 'esp_ports', return_value=[SimpleNamespace(device='COM6')]), \
             patch.object(flash_board.subprocess, 'run', side_effect=run):
            flash_board.main()

    def test_validated_json_is_the_only_flash_source(self):
        # An inconsistent/stale argument file must not override validated offsets.
        (self.build / 'flash_args').write_text('--erase-all 0x210000 identity.bin')
        self.run_helper()
        self.assertEqual(len(self.writes), 1)
        command = self.writes[0]
        self.assertNotIn('@flash_args', command)
        self.assertNotIn('--erase-all', command)
        for offset, file in self.files.items():
            index = command.index(offset)
            self.assertEqual(Path(command[index + 1]), (self.build / file).resolve())

    def test_oversized_application_is_refused_before_flash(self):
        (self.build / 'miezmerker-node.bin').write_bytes(b'x' * (0x200000 + 1))
        with self.assertRaises(SystemExit):
            self.run_helper()
        self.assertEqual(self.writes, [])

    def test_data_partition_offset_is_refused(self):
        self.files['0x210000'] = 'identity.bin'
        with self.assertRaises(SystemExit):
            self.run_helper()
        self.assertEqual(self.writes, [])

    def test_partition_image_cannot_disagree_with_checked_table(self):
        self.files['0x8000'] = 'another-table.bin'
        (self.build / 'another-table.bin').write_bytes(b'unchecked')
        with self.assertRaises(SystemExit):
            self.run_helper()
        self.assertEqual(self.writes, [])


if __name__ == '__main__':
    unittest.main()
