#!/usr/bin/env python3
"""Reject bundled 32-bit/unknown ELF libraries; no native libraries means ABI-neutral DEX."""
from pathlib import Path
import struct
import sys
import zipfile


def check(apk):
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith('.so'):
                continue
            with archive.open(name) as library:
                header = library.read(20)
            if len(header) < 20 or header[:4] != b'\x7fELF':
                raise ValueError(f'{apk}: {name} is not an ELF library')
            if header[4] != 2:
                raise ValueError(f'{apk}: {name} is not 64-bit ELF')
            endian = '<' if header[5] == 1 else '>' if header[5] == 2 else None
            if endian is None or struct.unpack(endian + 'H', header[18:20])[0] not in (62, 183):
                raise ValueError(f'{apk}: {name} has an unsupported native architecture')
            if name.startswith('lib/'):
                machine = struct.unpack(endian + 'H', header[18:20])[0]
                expected = 'arm64-v8a' if machine == 183 else 'x86_64'
                if len(name.split('/')) != 3 or name.split('/')[1] != expected:
                    raise ValueError(f'{apk}: {name} is stored under the wrong ABI directory')
            libraries.append(name)
    detail = ', '.join(libraries) if libraries else 'ABI-neutral DEX; no bundled native libraries'
    print(f'APK_SUPPORTS_64BIT {Path(apk).name}: {detail}')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit('Usage: check_apk_abi.py APK [APK ...]')
    for filename in sys.argv[1:]:
        check(filename)
