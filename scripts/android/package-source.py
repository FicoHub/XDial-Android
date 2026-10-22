#!/usr/bin/env python3
"""Package matching project and Go dependency source, excluding private keys."""
from pathlib import Path
import subprocess, zipfile
root = Path(__file__).resolve().parents[2]
files = subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=root).decode().split('\0')
import re
version = re.search(r'android:versionName="([^"]+)"', (root/'android/AndroidManifest.xml').read_text()).group(1)
archive = root/f'deliverables/XDial-Android-{version}-source.zip'
archive.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as bundle:
    for name in sorted(set(files)):
        if not name: continue
        path = Path(name)
        if path.parts[0] in ('build','deliverables','.git','.cindy-worktrees','PrivateConfig'): continue
        full = root/path
        if full.is_file(): bundle.write(full,'XDial-Android-source/'+name)
    cache = root/'build/android-tools/gopath/pkg/mod'
    for full in sorted(cache.rglob('*')):
        if full.is_file() and not full.name.endswith(('.lock','.tmp','.zip')):
            bundle.write(full,'XDial-Android-source/'+str(full.relative_to(root)))
print(archive)
