"""Fixed v032 comment display-media slice in the existing sole reply producers.

Does not change composer draft tokens, selection, requests, or display density.
The two complete upstream Kotlin sources are emitted verbatim; v025 remains the
canonical source inventory. Output adaptations are counted and reversible.
"""
import hashlib
import json
import os
from pathlib import Path

from v025_source_paths import canonical_source

COMMIT = '1db8665cb9706dca44fcae0f540f2a3440721089'
MANIFEST_SHA256 = '10e2a916dc753fbd730dbcfa16ab45d63216e2fd2d4e854ecbd54dac21320b4d'
ARCHIVE = Path('desktop/upstream-slices/v032-comment-media')
FORMAT = 'core-data/src/main/java/com/android/purebilibili/core/util/FormatUtils.kt'
POLICY = 'core-data/src/main/java/com/android/purebilibili/data/repository/CommentMediaPolicy.kt'

def wide(path):
    value = os.path.abspath(path)
    return Path('\\\\?\\' + value if os.name == 'nt' and not value.startswith('\\\\?\\') else value)

def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()

def sources(repo):
    folder = Path(repo) / ARCHIVE
    raw = wide(folder / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Fixed v032 comment media manifest changed')
    manifest = json.loads(raw)
    if manifest['upstreamCommit'] != COMMIT or len(manifest['sources']) != 2:
        raise ValueError('Unknown fixed v032 comment media identity')
    result = {}
    for row in manifest['sources']:
        name = row['path']
        if Path(name).name != name or name in result:
            raise ValueError('Invalid fixed comment media archive path')
        body = wide(folder / name).read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest()
        if type(row['bytes']) is not int or len(body) != row['bytes'] or sha(body) != row['sha256'] or blob != row['gitBlob']:
            raise ValueError('Fixed comment media source bytes changed: ' + name)
        result[name] = body
    if set(result) != {'CommentMediaPolicy.kt', 'FormatUtils.kt'}:
        raise ValueError('Unknown fixed comment media source closure')
    return manifest, result

def emit_shared(repo, output):
    manifest, raw = sources(repo)
    canonical = canonical_source(repo, FORMAT).read_text(encoding='utf8').replace('\r\n', '\n')
    before = '    private fun normalizeImageUrl(url: String?): String {'
    after = '    fun normalizeImageUrl(url: String?): String {'
    old_comment = '评论时间：详细模式固定显示本地年月日时分，否则沿用 PiliPlus 相对时间规则。'
    new_comment = '评论时间：详细模式固定显示本地年月日时分秒，否则沿用 PiliPlus 相对时间规则。'
    if canonical.count(before) != 1 or canonical.count(old_comment) != 1:
        raise ValueError('Canonical FormatUtils visibility boundary changed')
    projected = canonical.replace(before, after, 1).replace(old_comment, new_comment, 1)
    if projected.encode('utf8') != raw['FormatUtils.kt']:
        raise ValueError('Fixed FormatUtils changed beyond audited visibility and comment')
    emitted = []
    for row in manifest['sources']:
        relative = row['origin'].split('/src/main/java/', 1)[1]
        target = wide(Path(output) / relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw[row['path']])
        emitted.append(target)
    proof = dict(upstreamCommit=COMMIT, manifestSha256=MANIFEST_SHA256,
                 sourceBodiesVerbatim=True, fullSourceRows=manifest['sources'],
                 canonicalFormatSha256LF=sha(canonical), formatVisibilityAdapter=dict(before=before, after=after),
                 formatCommentAdapter=dict(before=old_comment, after=new_comment),
                 canonicalSourcesRewritten=False, fullV032ParityClaimed=False,
                 owner='extract-upstream-dynamic-editor.py', newNetworkOrDraftOwner=False)
    wide(Path(output) / 'v032-comment-media-source-ownership.json').write_text(
        json.dumps(proof, ensure_ascii=True, indent=2) + '\n', encoding='utf8', newline='\n')
    return emitted

def inverse(body, edits):
    for row in reversed(edits):
        if body.count(row['after']) != row['count']:
            raise ValueError('Comment media inverse count changed: ' + row['label'])
        body = body.replace(row['after'], row['before'], row['count'])
    return body

def adapt(body, kind, output):
    before_all, edits = body, []
    def replace(before, after, label, count=1):
        nonlocal body
        if body.count(before) != count:
            raise ValueError('Comment media producer boundary changed: ' + label)
        body = body.replace(before, after, count)
        edits.append(dict(label=label, before=before, after=after, count=count))
    if kind == 'rich-policy':
        replace('''internal fun collectRenderableEmoteKeys(
    text: String,
    emoteMap: Map<String, String>
): Set<String> {
    if (text.isEmpty() || emoteMap.isEmpty()) return emptySet()
    return EMOTE_TOKEN_PATTERN.findAll(text)
        .map { it.value }
        .filter { emoteMap.containsKey(it) }
        .toSet()
}''', '''internal fun collectRenderableEmoteKeys(
    text: String,
    emoteMap: Map<String, String>
): Set<String> = com.android.purebilibili.data.repository.resolveCommentRenderableEmoteKeys(text, emoteMap)''',
                'existing displayed emote keys delegate to shared original policy')
        replace('EMOTE_TOKEN_PATTERN.findAll(remainingText)',
                'com.android.purebilibili.data.repository.COMMENT_EMOTE_TOKEN_PATTERN.findAll(remainingText)',
                'displayed rich-text scanner consumes original shared pattern; editor pattern remains')
    elif kind == 'reply-renderer':
        replace('''    val imageUrls = remember(pictures) {
        pictures.map { pic ->
            var url = pic.imgSrc
            // 修复协议
            if (url.startsWith("//")) {
                url = "https:$url"
            } else if (url.startsWith("http://")) {
                url = url.replace("http://", "https://")
            }
            //  移除尺寸参数以获取原图（避免模糊）
            if (url.contains("@")) {
                url = url.substringBefore("@")
            }
            url
        }
    }''', '''    val normalizedMedia = remember(pictures) {
        pictures.mapNotNull { picture ->
            com.android.purebilibili.data.repository.resolveCommentPictureUrls(listOf(picture))
                .firstOrNull()?.let { url -> picture to url }
        }
    }
    // Keep server metadata paired with the shared policy's non-empty URLs.
    val normalizedPictures = remember(normalizedMedia) { normalizedMedia.map { it.first } }
    val imageUrls = remember(normalizedMedia) { normalizedMedia.map { it.second } }''',
                'shared original normalization and empty URL filtering preserve metadata indices')
        replace('val totalCount = pictures.size  //  [优化] 保存总图片数用于角标显示',
                'val totalCount = normalizedPictures.size  // 保持原预览数量与有效图片一致', 'valid gallery count')
        replace('    when (pictures.size) {\n        1 -> {',
                '    if (normalizedPictures.isEmpty()) return\n    when (normalizedPictures.size) {\n        1 -> {',
                'empty normalized media has no indexed thumbnail')
        replace('            val pic = pictures[0]', '            val pic = normalizedPictures[0]', 'single image metadata stays aligned')
        replace('''            val aspectRatio = if (pic.imgHeight > 0 && pic.imgWidth > 0) {
                (pic.imgWidth.toFloat() / pic.imgHeight.toFloat()).coerceIn(0.5f, 2f)
            } else {
                1.33f  // 默认 4:3 比例
            }''', '            val aspectRatio = com.android.purebilibili.data.repository.resolveCommentSinglePictureAspectRatio(pic)',
                'shared original aspect ratio and exact four-thirds fallback')
        replace('val displayItems = pictures.take(9)  //  [优化] 最多显示9张',
                'val displayItems = normalizedPictures.take(com.android.purebilibili.data.repository.COMMENT_PICTURE_MAX_COUNT)',
                'same nine thumbnail cap from shared original constant')
        replace('''            val columns = when {
                displayItems.size <= 4 -> 2
                else -> 3
            }''', '            val columns = com.android.purebilibili.data.repository.resolveCommentPictureGridColumns(displayItems.size)',
                'shared original two or three columns')
        replace('totalCount > 9', 'totalCount > com.android.purebilibili.data.repository.COMMENT_PICTURE_MAX_COUNT', 'overflow badge shared cap')
        replace('totalCount - 9', 'totalCount - com.android.purebilibili.data.repository.COMMENT_PICTURE_MAX_COUNT', 'overflow badge shared amount')
        replace('Placeholder(width = 1.4.em, height = 1.4.em,',
                'Placeholder(width = com.android.purebilibili.data.repository.COMMENT_EMOTE_INLINE_EM.em, height = com.android.purebilibili.data.repository.COMMENT_EMOTE_INLINE_EM.em,',
                'unchanged relative emote size from shared original constant')
    else:
        raise ValueError('Unknown comment media producer scope')
    if inverse(body, edits) != before_all:
        raise ValueError('Comment media previous owned source did not restore')
    proof = dict(upstreamCommit=COMMIT, kind=kind, manifestSha256=MANIFEST_SHA256,
                 beforeSha256LF=sha(before_all), afterSha256LF=sha(body), edits=edits,
                 fullPreviousOwnedInverse=True, editorTokenAndDraftSerializationChanged=False,
                 selectionChanged=False, displayDensityChanged=False, newRequestOwner=False,
                 fullV032ParityClaimed=False)
    wide(Path(output) / ('v032-comment-media-' + kind + '-adaptations.json')).write_text(
        json.dumps(proof, ensure_ascii=True, indent=2) + '\n', encoding='utf8', newline='\n')
    return body
