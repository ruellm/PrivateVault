"""Writes the EXIF orientation fixtures for the Stage 3B viewer tests.

Reads base.jpg from the directory given as the argument (a 64x32 JPEG, grey
with a 16x16 red square at the top left, made once with .NET System.Drawing;
the command is in tests/fixtures/exif/README.md) and writes orient_1.jpg,
orient_3.jpg, orient_6.jpg, and orient_8.jpg beside it. Each copy carries a
hand-built APP1 Exif segment right after SOI: a little-endian TIFF header and
one IFD0 entry, tag 0x0112 (Orientation), type SHORT, count 1. Synthetic data;
nothing here is a secret.
"""
import struct
import sys
from pathlib import Path


def exif_segment(orientation: int) -> bytes:
    tiff = b"II" + struct.pack("<HI", 42, 8)
    ifd = struct.pack("<H", 1) + struct.pack("<HHIHH", 0x0112, 3, 1, orientation, 0) + struct.pack("<I", 0)
    payload = b"Exif\x00\x00" + tiff + ifd
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: make_exif_fixtures.py <dir with base.jpg>")
        return 1
    d = Path(sys.argv[1])
    base = (d / "base.jpg").read_bytes()
    if base[:2] != b"\xff\xd8":
        print("base.jpg does not start with SOI")
        return 1
    for o in (1, 3, 6, 8):
        out = base[:2] + exif_segment(o) + base[2:]
        (d / f"orient_{o}.jpg").write_bytes(out)
        print(f"orient_{o}.jpg {len(out)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
