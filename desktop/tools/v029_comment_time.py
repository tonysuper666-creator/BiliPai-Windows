"""Pinned v029 time-only selection; never advances or rewrites the v025 catalog."""
from pathlib import Path
import hashlib
import json
import os

COMMIT = 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
ARCHIVE = Path('desktop/upstream-slices/v029-comment-time')
BASE = 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/'
PINS = {
    'ReplyComponents.kt': (132262, 'edba82de5f1e9726c104f3ec38d003cc566e72d99b10afcbd57d9a7c3f7848e9', 'ebce1b2b777967c0d53d761be2adf9c0a7ce6ff1'),
    'SubReplyDetailComponents.kt': (71788, '7b937a3be2194b2e51563a884a18d1d93c2f1eb67335b0b98b81743ff745311b', '3419e36b69246b29ec4357aacf25995eeb762842'),
}
CANONICAL_V025_LF_PINS = {
    BASE + 'ReplyComponents.kt': 'fcd9065876e3815af5337c9f43579c5bbd53e6d66d3dbfd8efb51bd43c9b66f1',
    BASE + 'SubReplyDetailComponents.kt': 'ac4646df2a2de90aad24e65f3bf4e11ac772463278e7d579b17eb2f290554b19',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def raw(path):
    path = Path(path).resolve()
    return (Path('\\\\?\\' + str(path)) if os.name == 'nt' else path).read_bytes()


def fixed_sources(repo):
    manifest = json.loads(raw(repo / ARCHIVE / 'manifest.json'))
    if manifest.get('schemaVersion') != 1 or manifest.get('fixedUpstreamCommit') != COMMIT:
        raise ValueError('Unknown v029 comment-time manifest')
    rows = manifest.get('files', [])
    if len(rows) != len(PINS) or {r.get('archiveFile') for r in rows} != set(PINS):
        raise ValueError('Unknown v029 comment-time source set')
    sources = {}
    for row in rows:
        name = row['archiveFile']
        size, sha, blob = PINS[name]
        expected = dict(originalPath=BASE + name, archiveFile=name, bytes=size,
                        sha256Bytes=sha, sha256LF=sha, gitBlob=blob)
        if row != expected:
            raise ValueError('Changed fixed v029 comment-time identity: ' + name)
        data = raw(repo / ARCHIVE / name)
        actual_blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if len(data) != size or digest(data) != sha or actual_blob != blob:
            raise ValueError('Changed fixed v029 comment-time raw bytes: ' + name)
        sources[BASE + name] = data.decode('utf-8')
    return sources


def _between(text, start, end):
    if text.count(start) != 1:
        raise ValueError('Ambiguous original time declaration: ' + start)
    a = text.index(start)
    return text[a:text.index(end, a)]


def apply(repo, path, original):
    fixed = fixed_sources(repo)
    if path not in fixed:
        return original, None
    if digest(original.encode()) != CANONICAL_V025_LF_PINS[path]:
        raise ValueError('Changed canonical v025 comment-time baseline: ' + path)
    newest = fixed[path]
    patches = []
    result = original

    def replace(before, after, origin):
        nonlocal result
        if result.count(before) != 1 or not after or after not in newest:
            raise ValueError('Original comment-time selection mismatch: ' + origin)
        patches.append(dict(before=before, after=after, originalV029Line=newest[:newest.index(after)].count('\n') + 1,
                            selection=origin))
        result = result.replace(before, after, 1)

    if path == BASE + 'ReplyComponents.kt':
        replace('import com.android.purebilibili.core.store.SettingsManager\n',
                'import com.android.purebilibili.core.store.SettingsManager\nimport com.android.purebilibili.core.ui.LocalDetailedCommentTimeEnabled\n',
                'detailed-time-local-import')
        replace(_between(original, 'internal fun resolveReplyPreviewTextContent(', '//  优化后的颜色常量'),
                _between(newest, 'internal fun resolveReplyPreviewTextContent(', '//  优化后的颜色常量'),
                'complete-original-preview-time-policy')
        replace('    val appearance = rememberVideoCommentAppearance()\n    val context = LocalContext.current\n    val scope = rememberCoroutineScope()',
                '    val appearance = rememberVideoCommentAppearance()\n    val context = LocalContext.current\n    val detailedCommentTimeEnabled = LocalDetailedCommentTimeEnabled.current\n    val scope = rememberCoroutineScope()',
                'original-reply-local-consumer')
        replace(_between(original, '    //  [PiliPlus 对齐] 一级评论固定显示绝对时间', '    val showTopBadge'),
                _between(newest, '    val metadataText = remember(item.ctime, displayLocation, detailedCommentTimeEnabled)', '    val showTopBadge'),
                'complete-original-first-level-time-with-location')
        for old, new in [
            ('"已切换为绝对时间：楼中楼与动态评论将显示 yyyy-MM-dd HH:mm:ss"', '"已切换为绝对时间：评论显示 yyyy-MM-dd HH:mm:ss"'),
            ('"已切换为相对时间（默认）：一级评论保持精确时间，楼中楼/动态按相对显示"', '"已切换为相对时间：评论按相对时间显示"'),
        ]:
            replace(old, new, 'original-time-setting-feedback')
    before = '                                        onReplyClick = onReplyClick,\n                                    )'
    after = '                                        onReplyClick = onReplyClick,\n                                        detailedTimeEnabled = detailedCommentTimeEnabled\n                                    )'
    replace(before, after, 'original-preview-caller-time-argument')
    restored = result
    for patch in reversed(patches):
        if restored.count(patch['after']) != 1:
            raise ValueError('Ambiguous inverse comment-time patch')
        restored = restored.replace(patch['after'], patch['before'], 1)
    if restored != original:
        raise ValueError('Comment-time complete inverse differs from canonical original')
    return result, dict(fixedUpstreamCommit=COMMIT, originalPath=path,
                        originalCanonicalSha256LF=digest(original.encode()),
                        adaptedSha256LF=digest(result.encode()), exactCompleteCanonicalInverse=True,
                        fixedRawSha256=PINS[Path(path).name][1], adaptations=patches)
