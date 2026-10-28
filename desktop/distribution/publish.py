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
import subprocess
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


def read_document(file, limit):
    require(file.is_file() and not file.is_symlink() and file.stat().st_size <= limit)
    return file.read_text(encoding='utf-8')


def verify_documents(raw_manifest, raw_config, metadata):
    result = subprocess.run(['node', str(Path(__file__).with_name('verify-publication.mjs'))],
                            input=json.dumps({'manifest': raw_manifest, 'config': raw_config, 'metadata': metadata}),
                            text=True, capture_output=True)
    require(result.returncode == 0, 'Signed publication rejected: ' + result.stderr[-1500:])
    return json.loads(result.stdout)


def publish(root, incoming, *, verify=verify_documents):
    root, incoming = Path(root).resolve(), Path(incoming).resolve()
    # Deployment runs this writer and invitation.mjs as the systemd service UID.
    # Refuse a mixed-owner installation rather than producing unreadable 0600 files.
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    require(root.stat().st_uid == os.geteuid(), 'Run publication as the distribution data owner/service user')
    metadata = json.loads(read_document(incoming / 'publish.json', 64 * 1024))
    channel, version = metadata['channel'], metadata['version']
    require(channel in ('dev', 'stable') and re.fullmatch(r'\d+(?:\.\d+){1,3}', version))
    require(type(metadata['sequence']) is int and metadata['sequence'] > 0)
    raw_manifest = read_document(incoming / 'manifest.json', 32 * 1024)
    raw_config = read_document(incoming / 'config.json', 16 * 1024)
    # No pointer, artifact or channel directory is changed before verification.
    verified = verify(raw_manifest, raw_config, metadata)
    manifest, catalog = verified['manifest'], verified['catalog']
    require(manifest['channel'] == channel and manifest['sequence'] == metadata['sequence'] and
            manifest['release']['version'] == version, 'Verified publication identity changed')
    archive, installer = manifest['release'], manifest['installer']
    checksum_name = archive['assetName'] + '.sha256'
    checksum_bytes = f"{archive['sha256']}  {archive['assetName']}\n".encode()
    expected = {
        archive['assetName']: ('public/artifacts', archive['sha256'], archive['size']),
        installer['name']: ('installers', installer['sha256'], installer['size']),
        checksum_name: ('public/artifacts', hashlib.sha256(checksum_bytes).hexdigest(), len(checksum_bytes)),
    }
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
        require(len({entry['name'] for entry in artifacts}) == len(artifacts), 'Duplicate upload')
        for file in artifacts:
            name = file['name']
            require(re.fullmatch(r'[A-Za-z0-9_.+ -]+\.(?:zip|msi|exe)(?:\.sha256)?', name))
            require(name in expected, 'Upload is not bound by signed manifest')
            kind, signed_hash, signed_size = expected[name]
            require(file['sha256'] == signed_hash, 'Unsigned upload hash differs from signed manifest')
            source = incoming / name
            require(source.is_file() and not source.is_symlink())
            require(source.stat().st_size == signed_size and digest(source) == signed_hash, 'Upload checksum/size mismatch')
            target = root / kind / version / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                require(digest(target) == signed_hash, 'Immutable artifact conflict')
            else:
                # Temp + rename stays on the target filesystem.
                with source.open('rb') as stream, tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as out:
                    temp = Path(out.name)
                    shutil.copyfileobj(stream, out)
                    out.flush()
                    os.fsync(out.fileno())
                try:
                    require(temp.stat().st_size == signed_size and digest(temp) == signed_hash, 'Upload changed while copying')
                    os.replace(temp, target)
                finally:
                    temp.unlink(missing_ok=True)
        # A withdrawal may upload no new files, but its signed executable bytes
        # must already exist. Never trust unsigned publish.json as an inventory.
        for name in (archive['assetName'], installer['name']):
            kind, signed_hash, signed_size = expected[name]
            target = root / kind / version / name
            require(target.is_file() and not target.is_symlink() and target.stat().st_size == signed_size and
                    digest(target) == signed_hash, 'Missing or changed signed artifact')
        history.mkdir(parents=True)
        atomic(history / 'manifest.json', raw_manifest.encode())
        atomic(history / 'config.json', raw_config.encode())
        admitted = {**metadata, 'catalog': catalog, 'stableApproved': manifest.get('stableApproved', False)}
        atomic(history / 'publish.json', json.dumps(admitted, ensure_ascii=False).encode())
        # One rename switches manifest, config and download catalog together.
        atomic(sequence_file, json.dumps({'sequence': metadata['sequence'], 'version': version}).encode())
        print(json.dumps({'published': version, 'channel': channel, 'sequence': metadata['sequence']}))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: publish.py <data-root> <incoming-directory>')
    os.umask(0o077)
    publish(*sys.argv[1:])
