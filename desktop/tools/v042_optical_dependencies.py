"""Authenticated current optical kernels; retain the existing sole Windows owners."""
from pathlib import Path
import hashlib
import json

from v029_comment_search import whole_proof

COMMIT = '5c34a82418b33bf88acea01784bb3c6b179d6491'
ARCHIVE = 'desktop/upstream-slices/v042-optical-dependencies'
MANIFEST_SHA256 = '8a5366fac0da15189d06ef8c5bb7824c84cb3987cfb0b5833809d3c69277215f'
APP = 'app/src/main/java/com/android/purebilibili/'
DS = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
SURFACE = DS + 'AppSurfaceTokens.kt'
CHROME = DS + 'blur/ChromeBackdropSource.kt'
MOTION = DS + 'animation/LiquidIndicatorMotion.kt'
TONAL = DS + 'renderer/material3/AppMaterial3TonalPillTabRow.kt'
SHARED_PATHS = {APP + 'core/ui/components/LiquidDockViewport.kt', *(
    APP + 'feature/home/components/miuix/' + leaf + '.kt' for leaf in (
        'DampedDragAnimation', 'DragGestureInspector', 'InteractiveHighlight',
        'InteractiveHighlightPalette', 'InteractiveHighlightMotionSpec'))}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Unreviewed optical dependency manifest')
    manifest = json.loads(raw)
    expected = SHARED_PATHS | {SURFACE, CHROME, MOTION, TONAL}
    if (manifest['upstreamCommit'] != COMMIT or manifest['hashNormalization'] != 'raw'
            or {r['path'] for r in manifest['files']} != expected or len(manifest['files']) != len(expected)):
        raise ValueError('Unknown optical dependency source set')
    if {p.name for p in root.iterdir()} != {'manifest.json', *(r['filename'] for r in manifest['files'])}:
        raise ValueError('Unexpected optical dependency archive leaf')
    sources = {}
    for row in manifest['files']:
        raw = (root / row['filename']).read_bytes()
        if (row['filename'] != Path(row['path']).name or len(raw) != row['bytes']
                or sha(raw) != row['sha256Bytes']
                or hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() != row['gitBlob']):
            raise ValueError('Changed optical dependency: ' + row['path'])
        sources[row['path']] = raw
    return sources, manifest


def source_record(repo, path):
    _, manifest = fixed_sources(repo)
    return dict(next(r for r in manifest['files'] if r['path'] == path), upstreamCommit=COMMIT)


def identity(repo, path):
    if path not in SHARED_PATHS:
        return None
    row = source_record(repo, path)
    return dict(pinnedCommit=COMMIT, originalPath=path, path=ARCHIVE + '/' + row['filename'],
        sha256Bytes=row['sha256Bytes'], gitBlobOid=row['gitBlob'], bytes=row['bytes'])


def selected_source(repo, path, previous):
    if path not in SHARED_PATHS | {SURFACE, MOTION, TONAL}:
        return previous
    return fixed_sources(repo)[0][path].decode('utf8')


def backdrop_source(repo):
    original = fixed_sources(repo)[0][CHROME].decode('utf8')
    text = original
    edits = [
        ('import androidx.compose.runtime.remember\n', 'import androidx.compose.runtime.remember\nimport androidx.compose.runtime.getValue\nimport androidx.compose.ui.graphics.drawscope.DrawScope\n'),
        ('    recordingBackground: Color = Color.Unspecified,\n', '    recordingBackground: Color = Color.Unspecified,\n    recordContent: (DrawScope.() -> Unit)? = null,\n'),
        ('    val contentLayer = rememberGraphicsLayer()', '    val latestRecordContent by rememberUpdatedState(recordContent)\n    val contentLayer = rememberGraphicsLayer()'),
        ('                        this@drawWithContent.drawContent()', '                        val recording = latestRecordContent\n                        if (recording == null) this@drawWithContent.drawContent()\n                        else recording.invoke(this)'),
        ('                    if (drawContentToScreen) drawLayer(contentLayer)', '                    if (drawContentToScreen) {\n                        if (latestRecordContent == null) drawLayer(contentLayer)\n                        else this@drawWithContent.drawContent()\n                    }'),
    ]
    for before, after in edits:
        if text.count(before) != 1:
            raise ValueError('Unknown actual native backdrop recording boundary')
        text = text.replace(before, after, 1)
    # Current hidden capture/background updates and the native mirror use this
    # single actual source. Native thumbnail content is never painted on screen.
    proof = whole_proof(original, text)
    if proof['beforeSha256LF'] != sha(original.encode()):
        raise ValueError('Current backdrop inverse source identity changed')
    return original, text
