"""Complete current upstream tap stream, connected to the existing player-section owner."""
from pathlib import Path
import hashlib
import json

COMMIT = '415d01ec5bc3ac82521a9ac47bcc449b8e202460'
ARCHIVE = 'desktop/upstream-slices/v040-player-tap'
MANIFEST_SHA256 = '2e9b5c6f38d1a2a3dbc2cb3619163f98bb294aa29cace02dff70a957e387d792'
DECLARATION_SHA256 = 'ab9e53e2a92221c42b3d68dabbf980ee8e2ee69ed5ac5491e966c54474a92722'
CALLER_CHANGES = [{'before': 'import androidx.compose.foundation.gestures.detectTapGestures\n', 'after': 'import com.android.purebilibili.feature.video.ui.gesture.detectPlayerTapGestures\n', 'label': 'same current pure tap detector import'}, {'before': '    var longPressSpeedEndedAtMs by remember { mutableLongStateOf(0L) }\n', 'after': '', 'label': 'remove obsolete per-entry cooldown'}, {'before': '        longPressSpeedEndedAtMs = android.os.SystemClock.elapsedRealtime()\n', 'after': '', 'label': 'separate long-press actor no longer creates a cooldown'}, {'before': '        longPressSpeedEndedAtMs = android.os.SystemClock.elapsedRealtime()\n', 'after': '', 'label': 'separate long-press actor no longer creates a cooldown'}, {'before': '        // 上一条媒体的长按结束时间不能抑制新媒体的首次单击。\n        longPressSpeedEndedAtMs = 0L\n', 'after': '', 'label': 'remove obsolete source reset'}, {'before': '                        if (\n                            !shouldToggleControlsForVideoTap(\n                                longPressSpeedEndedAtMs = longPressSpeedEndedAtMs,\n                                nowMs = android.os.SystemClock.elapsedRealtime()\n                            )\n                        ) {\n                            return@detectTapGestures\n                        }\n', 'after': '', 'label': 'short tap stream owns long-press exclusion'}, {'before': '                detectTapGestures(\n', 'after': '                detectPlayerTapGestures(\n', 'label': 'actual retained main pointerInput connects current detector'}, {'before': 'return@detectTapGestures', 'after': 'return@detectPlayerTapGestures', 'label': 'current callback return label'}]
RELATIVE_OUTPUT = 'com/android/purebilibili/feature/video/ui/gesture/DesktopOriginalPlayerTapGestures.kt'


def sha(raw):
    return hashlib.sha256(raw.encode('utf-8') if isinstance(raw, str) else raw).hexdigest()


def fixed_original(repo):
    root = Path(repo) / ARCHIVE
    raw_manifest = (root / 'manifest.json').read_bytes()
    if sha(raw_manifest) != MANIFEST_SHA256:
        raise ValueError('Unreviewed original player-tap manifest')
    manifest = json.loads(raw_manifest)
    if manifest['fixedUpstreamCommit'] != COMMIT or len(manifest['files']) != 1:
        raise ValueError('Unreviewed original player-tap identity')
    row = manifest['files'][0]
    if row['filename'] != 'PlayerGestureHandler.kt':
        raise ValueError('Unexpected original player-tap file')
    raw = (root / row['filename']).read_bytes()
    blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if len(raw) != row['bytes'] or sha(raw) != row['sha256Bytes'] or blob != row['gitBlob']:
        raise ValueError('Modified fixed original player-tap source')
    text = raw.decode('utf-8').replace('\r\n', '\n')
    identity = dict(text=text, upstreamCommit=COMMIT, originalPath=row['path'],
        sha256LF=sha(text), gitBlob=blob, localPath=ARCHIVE + '/' + row['filename'],
        sourceManifestSha256=MANIFEST_SHA256)
    return text, identity


def whole_detector(text, protocol):
    anchor = 'suspend fun PointerInputScope.detectPlayerTapGestures('
    if text.count(anchor) != 1:
        raise ValueError('Expected one whole original player-tap detector')
    start = text.index(anchor)
    mask = protocol.masked(text)
    opening = mask.index('(', start)
    after_parameters = protocol.balanced(mask, opening, '(', ')')
    opening = mask.index('{', after_parameters)
    end = protocol.balanced(mask, opening, '{', '}')
    selected = text[start:end] + '\n'
    if sha(selected) != DECLARATION_SHA256:
        raise ValueError('Unreviewed original player-tap declaration')
    return selected


def inverse(text, edits):
    for edit in reversed(edits):
        at, after = edit['offset'], edit['after']
        if text[at:at + len(after)] != after:
            raise ValueError('Player-tap caller inverse boundary changed')
        text = text[:at] + edit['before'] + text[at + len(after):]
    return text


def project_caller(repo, text, sources, protocol):
    handler, identity = fixed_original(repo)
    whole_detector(handler, protocol)
    from v040_player_cover import fixed_sources
    current_section = fixed_sources(repo)['VideoPlayerSection.kt'].decode('utf-8').replace('\r\n', '\n')
    original, edits = text, []
    for index, change in enumerate(CALLER_CHANGES):
        before, after = change['before'], change['after']
        expected = sum(row['before'] == before for row in CALLER_CHANGES[index:])
        if text.count(before) != expected:
            raise ValueError('Unreviewed current player-tap caller: ' + change['label'])
        if after and after not in current_section:
            raise ValueError('Replacement is not the fixed current original caller')
        at = text.index(before)
        edits.append(dict(offset=at, before=before, after=after, label=change['label']))
        text = text[:at] + after + text[at + len(before):]
    if inverse(text, edits) != original:
        raise ValueError('Whole previous player section could not be recovered')
    if 'longPressSpeedEndedAtMs' in text or 'detectTapGestures' in text:
        raise ValueError('Obsolete live tap/cooldown path remains')
    sources[identity['localPath']] = identity
    proof = dict(fixedCommit=COMMIT, edits=edits, entirePreviousSectionInverse=True,
        wholeCurrentOriginalDetector=True, currentOriginalTapCaller=True,
        source={key: value for key, value in identity.items() if key != 'text'},
        windowsForegroundAndLongPressOwnerUnchanged=True,
        androidSystemAudioOrBrightnessNotImported=True, fullCurrentPlayerUiPorted=False)
    return text, proof


def emit(repo, output, outputs, sources, protocol, write):
    handler, identity = fixed_original(repo)
    method = whole_detector(handler, protocol)
    imports = ('package com.android.purebilibili.feature.video.ui.gesture\n\n'
        'import androidx.compose.foundation.gestures.awaitEachGesture\n'
        'import androidx.compose.foundation.gestures.awaitFirstDown\n'
        'import androidx.compose.foundation.gestures.waitForUpOrCancellation\n'
        'import androidx.compose.ui.geometry.Offset\n'
        'import androidx.compose.ui.input.pointer.PointerInputScope\n\n')
    selected = imports + method
    sources[identity['localPath']] = identity
    write(Path(output) / RELATIVE_OUTPUT, selected)
    outputs.append(dict(path=RELATIVE_OUTPUT, sha256LF=sha(selected), generated=True,
        mode='selected-complete-original-declaration',
        source={key: value for key, value in identity.items() if key != 'text'}))
    write(Path(output) / 'v040-player-tap-declaration-proof.json', json.dumps(dict(
        fixedCommit=COMMIT, sourceSHA256LF=identity['sha256LF'],
        wholeOriginalDeclarationSHA256LF=sha(method), wholeOriginalDeclarationUnmodified=True,
        platformImportWrapperOnly=True, kotlinCompiled=False, pointerEventsExecuted=False),
        ensure_ascii=False, indent=2) + '\n')
