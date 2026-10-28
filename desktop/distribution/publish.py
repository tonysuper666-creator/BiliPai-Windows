#!/usr/bin/env python3
"""Server-side immutable upload admission. No private signing material is accepted.

Called by release.sh after an scp to an isolated incoming directory. Mutable
channel pointers are atomic files; interrupted upload never exposes a partial ZIP.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile


def require(condition, message="Invalid publication"):
    if not condition:
        raise ValueError(message)


def digest(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def atomic(file, data):
    file.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=file.parent, delete=False) as out:
        temp = Path(out.name)
        out.write(data)
        out.flush()
        os.fsync(out.fileno())
    try:
        os.replace(temp, file)
    finally:
        temp.unlink(missing_ok=True)


def publish(root, incoming):
    root, incoming = Path(root).resolve(), Path(incoming).resolve()
    metadata = json.loads((incoming / 'publish.json').read_text())
    channel, version = metadata['channel'], metadata['version']
    require(channel in ('dev', 'stable') and re.fullmatch(r'\d+(?:\.\d+){1,3}', version))
    require(type(metadata['sequence']) is int and metadata['sequence'] > 0)
    # Stable admission belongs to release.sh and independently requires human-use
    # and real-device acceptance receipts. The server cannot grant that approval.
    if channel == 'stable':
        require(metadata.get('stableApproved') is True, 'Stable requires approval')
    private = root / 'private'
    private.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (private / 'publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        sequence_file = root / 'channels' / channel / 'current.json'
        previous = json.loads(sequence_file.read_text()) if sequence_file.exists() else {'sequence': 0}
        require(metadata['sequence'] > previous['sequence'], 'Channel sequence must increase')
        history = root / 'channels' / channel / str(metadata['sequence'])
        require(not history.exists(), 'Sequence already used')
        artifacts = metadata['files']
        require(len(artifacts) <= 8)
        for file in artifacts:
            name = file['name']
            require(re.fullmatch(r'[A-Za-z0-9_.+ -]+\.(?:zip|msi|exe)(?:\.sha256)?', name))
            source = incoming / name
            require(source.is_file() and not source.is_symlink())
            require(digest(source) == file['sha256'], 'Upload checksum mismatch')
            kind = 'installers' if name.endswith(('.msi', '.exe')) else 'public/artifacts'
            target = root / kind / version / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                require(digest(target) == file['sha256'], 'Immutable artifact conflict')
            else:
                # Temp + rename stays on the target filesystem.
                with source.open('rb') as stream, tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as out:
                    temp = Path(out.name)
                    shutil.copyfileobj(stream, out)
                    out.flush()
                    os.fsync(out.fileno())
                os.replace(temp, target)
        history.mkdir(parents=True)
        for kind, directory in (('manifest', 'updates'), ('config', 'config')):
            raw = (incoming / f'{kind}.json').read_bytes()
            require(len(raw) <= 32 * 1024)
            atomic(history / f'{kind}.json', raw)
        atomic(history / 'publish.json', json.dumps(metadata, ensure_ascii=False).encode())
        # One rename switches manifest, config and download catalog together.
        atomic(sequence_file, json.dumps({'sequence': metadata['sequence'], 'version': version}).encode())
        print(json.dumps({'published': version, 'channel': channel, 'sequence': metadata['sequence']}))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: publish.py <data-root> <incoming-directory>')
    publish(*sys.argv[1:])
