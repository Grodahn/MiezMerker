"""Rebuild the checked-in PWA icons; uses only Python's standard library."""
from pathlib import Path
import struct
import zlib


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))


for size in (192, 512):
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)  # PNG row filter
        for x in range(size):
            nx, ny = x / size, y / size
            head = ((nx - .5) / .23) ** 2 + ((ny - .56) / .2) ** 2 < 1
            ear = (.28 < nx < .43 and .27 < ny < .46 and ny > .27 + (nx - .28) * .9)
            ear |= (.57 < nx < .72 and .27 < ny < .46 and ny > .27 + (.72 - nx) * .9)
            eye = (nx - .41) ** 2 + (ny - .53) ** 2 < .025 ** 2
            eye |= (nx - .59) ** 2 + (ny - .53) ** 2 < .025 ** 2
            nose = abs(nx - .5) < .025 and .6 < ny < .635
            pixels.extend((244, 247, 245) if (head or ear) and not (eye or nose) else (36, 88, 75))
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(pixels, 9)) + chunk(b'IEND', b'')
    (Path(__file__).resolve().parents[1] / 'public' / f'icon-{size}.png').write_bytes(png)
