"""Fixed complete player policies reused by the sole Windows section producer."""
from pathlib import Path
import hashlib
import json

COMMIT = '8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
ARCHIVE = Path('desktop/upstream-slices/v037-player-section-policy')
MANIFEST_SHA = 'd67a590b429a07db8c86efa6309bd05c967370d96c4f9d9e88c2ec819c220869'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionPolicy.kt', 'filename': 'VideoPlayerSectionPolicy.kt', 'bytes': 64713, 'sha256Bytes': 'afc0f14c47eea87fadf77d49fbca6f76b5c87252624e71fae5b70d7fad33de83', 'gitBlob': '500dc23af6aa9b6a79c0f344ea0855e92e3347a5'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerDanmakuLoadPolicy.kt', 'filename': 'VideoPlayerDanmakuLoadPolicy.kt', 'bytes': 1873, 'sha256Bytes': '056e56c4f25f75725c5a62a67f0fb8d3a7d19bf21282addf9e3733ae96b73d09', 'gitBlob': '3a6be6e513337d8e650a2c7bc6c72d990ad45a05'}]
RELATIVE_PATHS = (
    'feature/video/ui/section/VideoPlayerSectionPolicy.kt',
    'feature/video/ui/section/VideoPlayerDanmakuLoadPolicy.kt',
)
GESTURE_MOTION_NAMES = (
    'VideoGestureMode', 'VideoGestureMotionSpec', 'resolveVideoGestureMotionSpec',
)


def fixed_original(repo, relative):
    assert relative in RELATIVE_PATHS, relative
    root = Path(repo) / ARCHIVE
    manifest_raw = (root / 'manifest.json').read_bytes()
    assert hashlib.sha256(manifest_raw).hexdigest() == MANIFEST_SHA
    manifest = json.loads(manifest_raw)
    assert manifest['fixedUpstreamCommit'] == COMMIT and manifest['files'] == PINS
    original_path = 'app/src/main/java/com/android/purebilibili/' + relative
    row = next(item for item in PINS if item['path'] == original_path)
    raw = (root / row['filename']).read_bytes()
    assert len(raw) == row['bytes'] and hashlib.sha256(raw).hexdigest() == row['sha256Bytes'], 'Fixed player policy bytes changed'
    assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == row['gitBlob']
    text = raw.decode('utf-8').replace('\r\n', '\n')
    return text, dict(text=text, upstreamCommit=COMMIT, originalPath=original_path,
                     sha256LF=hashlib.sha256(text.encode()).hexdigest(), gitBlob=row['gitBlob'],
                     localPath=(ARCHIVE / row['filename']).as_posix(), sourceManifestSha256=MANIFEST_SHA)
