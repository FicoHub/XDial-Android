#!/usr/bin/env python3
"""Add DEX and original gomobile shared libraries to the aapt2 APK."""
from pathlib import Path
import sys, zipfile
root = Path(sys.argv[1])
with zipfile.ZipFile(root / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    for path in (root / 'dex').glob('*.dex'):
        apk.write(path, path.name)
    for path in (root / 'aar/jni').glob('*/*.so'):
        apk.write(path, 'lib/' + str(path.relative_to(root / 'aar/jni')))
