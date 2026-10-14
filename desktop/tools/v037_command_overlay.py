"""Whole fixed v037 command-card UI on the existing owned Windows vote ports."""
from pathlib import Path
import hashlib
import json

COMMIT = '8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
ARCHIVE = Path('desktop/upstream-slices/v037-command-overlay')
MANIFEST_SHA = 'cb4a43e2659e8b7235991bfe27286a09c58f0a490434aede99eb340a8ab93232'
PIN = {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlay.kt', 'filename': 'CommandDanmakuOverlay.kt', 'bytes': 43029, 'sha256Bytes': 'af492775f32aa5449f8fab3d1e15363ca26848da527660f6e88f21710f8d8fb3', 'gitBlob': '73c3faa498764b2162a6c5b4d5b96e4c04532d88'}


def fixed_original(repo):
    root = Path(repo) / ARCHIVE
    data = (root / 'manifest.json').read_bytes()
    assert hashlib.sha256(data).hexdigest() == MANIFEST_SHA
    manifest = json.loads(data)
    assert manifest['fixedUpstreamCommit'] == COMMIT and manifest['files'] == [PIN]
    raw = (root / PIN['filename']).read_bytes()
    assert len(raw) == PIN['bytes'] and hashlib.sha256(raw).hexdigest() == PIN['sha256Bytes'], 'Fixed command overlay bytes changed'
    assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == PIN['gitBlob']
    return raw.decode('utf-8').replace('\r\n', '\n'), dict(PIN, fixedCommit=COMMIT)


def emit(output, relative, original_path, body):
    target = Path(output) / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    header = '// GENERATED from ' + original_path + '; do not edit.\n// Fixed upstream ' + COMMIT + '; raw SHA-256: ' + PIN['sha256Bytes'] + '; Git blob: ' + PIN['gitBlob'] + '\n'
    target.write_bytes((header + body).encode())
