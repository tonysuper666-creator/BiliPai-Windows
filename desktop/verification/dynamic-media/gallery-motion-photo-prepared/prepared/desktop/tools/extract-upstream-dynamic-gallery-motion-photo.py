"""Select original Motion Photo packing and gallery-result policy; no new account/UI."""
from pathlib import Path
import argparse, hashlib, json, subprocess, sys
sys.dont_write_bytecode = True

PREVIEW = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt'
GALLERY = 'app/src/main/java/com/android/purebilibili/core/util/GalleryVisualMediaContracts.kt'
TAG = 'v0.2.3-alpha.9'

def digest(value): return hashlib.sha256(value.encode()).hexdigest()

def generate(repo: Path, output: Path):
    repo, output = repo.resolve(), output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sources = {}
    for path in [PREVIEW, GALLERY]:
        raw = (repo / path).read_text(encoding='utf-8').replace('\r\n', '\n')
        tag_raw = subprocess.check_output(['git', 'show', TAG + ':' + path], cwd=repo).decode().replace('\r\n', '\n')
        assert raw == tag_raw, path + ' differs from pinned alpha.9'
        sources[path] = raw
    preview = sources[PREVIEW]
    start = preview.index('            // 4. 构建 Google / Android 官方 Motion Photo 1.0 标准 XMP 元数据')
    stop = preview.index('            // 8. 保存到相册', start)
    packing = preview[start:stop]
    assert packing.count('val xmpString =') == packing.count('val jpegSegmentBytes =') == 1
    packing_target = output / 'com/android/purebilibili/feature/dynamic/components/DesktopOriginalMotionPhotoPacking.kt'
    packing_target.parent.mkdir(parents=True, exist_ok=True)
    prefix = ('// GENERATED selected original JPEG APP1/XMP assembly; do not hand-maintain a second packing algorithm.\n'
        + '// Original: ' + PREVIEW + '\n// LF SHA-256: ' + digest(preview) + '\n'
        + 'package com.android.purebilibili.feature.dynamic.components\n'
        + 'internal fun desktopOriginalMotionPhotoJpeg(jpegWithExif: ByteArray, videoSize: Long): ByteArray {\n')
    packing_target.write_text(prefix + packing + '    return jpegSegmentBytes\n}\n', encoding='utf-8', newline='\n')

    gallery = sources[GALLERY]
    body_start = gallery.index('        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()')
    body_stop = gallery.index('\n    }', body_start)
    gallery_body = gallery[body_start:body_stop]
    transformations = [
        ('if (resultCode != Activity.RESULT_OK || intent == null)', 'if (!resultOk)'),
        ('linkedSetOf<Uri>()', 'linkedSetOf<String>()'),
        ('intent.data?.let(selectedUris::add)', 'data?.let(selectedUris::add)'),
        ('intent.clipData?.let { clipData ->', 'clips?.let { clipData ->'),
        ('clipData.itemCount', 'clipData.size'),
        ('clipData.getItemAt(index).uri', 'clipData[index]'),
    ]
    adapted = gallery_body
    for old, new in transformations:
        assert old in adapted, old
        adapted = adapted.replace(old, new)
    mime_start = gallery.index('): String? = when (mediaType) {') + len('): String? = ')
    mime = gallery[mime_start:].strip()
    assert mime.endswith('}')
    mime_adapted = mime.replace('ActivityResultContracts.PickVisualMedia.', 'DesktopGalleryVisualMediaType.')
    target = output / 'com/android/purebilibili/core/util/DesktopOriginalGalleryResultPolicy.kt'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('// GENERATED original gallery ordered distinct/take and MIME policy.\n// Original: ' + GALLERY
        + '\n// LF SHA-256: ' + digest(gallery) + '\npackage com.android.purebilibili.core.util\n'
        + 'internal sealed interface DesktopGalleryVisualMediaType {\n'
        + '    data object ImageOnly : DesktopGalleryVisualMediaType\n'
        + '    data object VideoOnly : DesktopGalleryVisualMediaType\n'
        + '    data object ImageAndVideo : DesktopGalleryVisualMediaType\n'
        + '    data class SingleMimeType(val mimeType: String) : DesktopGalleryVisualMediaType\n}\n'
        + 'internal fun desktopOriginalMultipleGalleryResult(resultOk: Boolean, data: String?, clips: List<String>?, maxItems: Int): List<String> {\n'
        + adapted + '\n}\n'
        + 'internal fun desktopOriginalGalleryMimeType(mediaType: DesktopGalleryVisualMediaType): String? = ' + mime_adapted + '\n',
        encoding='utf-8', newline='\n')
    receipt = {
        'formatVersion': 1, 'originalTag': TAG,
        'originalCommit': subprocess.check_output(['git', 'rev-parse', TAG + '^{commit}'], cwd=repo, text=True).strip(),
        'originalSourceLfPins': [{'path': p, 'sha256Lf': digest(sources[p]), 'tagBytesLfEqual': True} for p in sources],
        'selectedPacking': {'source': PREVIEW, 'startLine': preview[:start].count('\n') + 1,
            'endLine': preview[:stop].count('\n'), 'sha256Lf': digest(packing), 'unchangedBody': True,
            'sourceVariablesLiftedToParameters': ['jpegWithExif', 'videoSize'], 'newReturnOnly': 'return jpegSegmentBytes'},
        'galleryResult': {'source': GALLERY, 'originalBodySha256Lf': digest(gallery_body), 'adaptedBodySha256Lf': digest(adapted),
            'substitutionsOnly': transformations, 'originalLinkedSetOrderDedupeAndTake': True},
        'galleryMime': {'originalBodySha256Lf': digest(mime), 'adaptedBodySha256Lf': digest(mime_adapted),
            'typeQualifierOnly': True},
        'generated': [{'path': p.relative_to(output).as_posix(), 'sha256Bytes': hashlib.sha256(p.read_bytes()).hexdigest(),
                       'bytes': p.stat().st_size} for p in [packing_target, target]],
        'androidExifWriterIncluded': False, 'androidMediaStoreOrSafIncluded': False,
        'newBusinessPackingAlgorithm': False, 'newAccountApiStoreOrCache': False,
    }
    (output / 'producer-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return receipt

if __name__ == '__main__':
    cli = argparse.ArgumentParser()
    cli.add_argument('--repo', type=Path, required=True)
    cli.add_argument('--output', type=Path, required=True)
    a = cli.parse_args()
    receipt = generate(a.repo, a.output)
    print('PASS exact original packing body and gallery policy; generated ' + str(len(receipt['generated'])) + ' Kotlin sources')
