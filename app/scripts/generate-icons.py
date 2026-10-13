"""Generate installable PWA icons from src/assets/branding/logo.png.

Uses only the Python standard library, so local Windows/ESP-IDF development
and CI do not require an additional image-processing dependency.
The source logo is the single source of truth; generated files are ignored.
"""
from __future__ import annotations

from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src" / "assets" / "branding" / "logo.png"
PUBLIC = ROOT / "public"
SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    candidates = (abs(p - a), abs(p - b), abs(p - c))
    return (a, b, c)[candidates.index(min(candidates))]


def read_logo(path: Path) -> tuple[int, int, bytes]:
    data = path.read_bytes()
    if not data.startswith(SIGNATURE):
        raise ValueError(f"Not a PNG: {path}")
    pos, compressed = len(SIGNATURE), bytearray()
    width = height = None
    while pos < len(data):
        if pos + 12 > len(data):
            raise ValueError("Truncated PNG chunk")
        count = struct.unpack_from(">I", data, pos)[0]
        kind = data[pos + 4:pos + 8]
        end = pos + 12 + count
        if end > len(data):
            raise ValueError("Truncated PNG payload")
        payload = data[pos + 8:pos + 8 + count]
        if zlib.crc32(kind + payload) != struct.unpack_from(">I", data, end - 4)[0]:
            raise ValueError("Invalid PNG chunk CRC")
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if (depth, color, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                raise ValueError("Expected non-interlaced 8-bit RGBA source logo")
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            break
        pos = end
    if width is None or height is None or not width or not height:
        raise ValueError("Missing PNG dimensions")

    # Unfilter RGBA scanlines according to PNG specification.
    raw = zlib.decompress(compressed)
    stride, bpp = width * 4, 4
    rows, offset, previous = bytearray(), 0, bytearray(stride)
    for _ in range(height):
        if offset + 1 + stride > len(raw):
            raise ValueError("Truncated image data")
        filter_type = raw[offset]
        current = bytearray(raw[offset + 1:offset + 1 + stride])
        offset += 1 + stride
        if filter_type not in range(5):
            raise ValueError(f"Unsupported PNG filter {filter_type}")
        for i in range(stride):
            left = current[i - bpp] if i >= bpp else 0
            above = previous[i]
            upper_left = previous[i - bpp] if i >= bpp else 0
            predictor = (0, left, above, (left + above) // 2, _paeth(left, above, upper_left))[filter_type]
            current[i] = (current[i] + predictor) & 255
        rows.extend(current)
        previous = current
    if offset != len(raw):
        raise ValueError("Unexpected extra PNG image data")
    return width, height, bytes(rows)


def make_icon(source: tuple[int, int, bytes], size: int, maskable: bool = False) -> bytes:
    width, height, rgba = source
    # Composite alpha onto the light background of the existing brand logo.
    rgb = bytearray(width * height * 3)
    for i in range(width * height):
        alpha = rgba[i * 4 + 3]
        for channel in range(3):
            color = rgba[i * 4 + channel]
            rgb[i * 3 + channel] = (color * alpha + 255 * (255 - alpha) + 127) // 255

    # Maskable launchers crop outside their safe area. Add breathing room to
    # keep both the cat and the MiezMerker wordmark visible.
    side = round(size * 0.76) if maskable else size
    start = (size - side) // 2
    output = bytearray()
    for y in range(size):
        output.append(0)  # PNG filter: none
        for x in range(size):
            if not (start <= x < start + side and start <= y < start + side):
                output.extend((255, 255, 255))
                continue
            fx = max(0, min(width - 1, (x - start + 0.5) * width / side - 0.5))
            fy = max(0, min(height - 1, (y - start + 0.5) * height / side - 0.5))
            x0, y0 = int(fx), int(fy)
            x1, y1 = min(x0 + 1, width - 1), min(y0 + 1, height - 1)
            dx, dy = fx - x0, fy - y0
            for channel in range(3):
                a = rgb[(y0 * width + x0) * 3 + channel]
                b = rgb[(y0 * width + x1) * 3 + channel]
                c = rgb[(y1 * width + x0) * 3 + channel]
                d = rgb[(y1 * width + x1) * 3 + channel]
                output.append(round((a * (1 - dx) + b * dx) * (1 - dy)
                                    + (c * (1 - dx) + d * dx) * dy))

    result = SIGNATURE
    result += _chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
    result += _chunk(b"IDAT", zlib.compress(output, level=9))
    return result + _chunk(b"IEND", b"")


def main() -> None:
    logo = read_logo(SOURCE)
    PUBLIC.mkdir(parents=True, exist_ok=True)
    for filename, size, maskable in (
        ("icon-192.png", 192, False),
        ("icon-512.png", 512, False),
        ("icon-maskable-512.png", 512, True),
    ):
        path = PUBLIC / filename
        content = make_icon(logo, size, maskable)
        if not path.exists() or path.read_bytes() != content:
            path.write_bytes(content)
        print(f"{filename}: {size}x{size} ({'maskable' if maskable else 'any'})")


if __name__ == "__main__":
    main()
