"""Select original Motion Photo packing and gallery-result policy; no new account/UI."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, difflib, hashlib, json, subprocess, sys
sys.dont_write_bytecode = True

PREVIEW = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt'
GALLERY = 'app/src/main/java/com/android/purebilibili/core/util/GalleryVisualMediaContracts.kt'
TAG = '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'

def digest(value): return hashlib.sha256(value.encode()).hexdigest()

def generate(repo: Path, output: Path):
    repo, output = repo.resolve(), output.resolve()
    value = str(output)
    output = Path(value if value.startswith("\\\\?\\") else "\\\\?\\" + value)
    output.mkdir(parents=True, exist_ok=True)
    sources = {}
    for path in [PREVIEW, GALLERY]:
        raw = (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')
        tag_raw = subprocess.check_output(['git', 'show', TAG + ':' + path], cwd=repo).decode().replace('\r\n', '\n')
        assert raw == tag_raw, path + ' differs from pinned ' + TAG
        sources[path] = raw
    preview = sources[PREVIEW]
    start = preview.index('            // 4. 构建 Google / Android 官方 Motion Photo 1.0 标准 XMP 元数据')
    stop = preview.index('            // 8. 保存到相册', start)
    packing = preview[start:stop]
    assert packing.count('val xmpString =') == packing.count('val jpegSegmentBytes =') == 1
    original_packing = packing
    omitted = ['        GCamera:MotionPhoto="1"', '        GCamera:MotionPhotoVersion="1"', '        GCamera:MotionPhotoPresentationTimestampUs="0"']
    for line in omitted:
        assert packing.splitlines().count(line) == 1, line
        packing = packing.replace(line + '\n', '')
    assert original_packing.count('\n') - packing.count('\n') == 3
    (output / 'original-packing-before-xmp-fix.txt').write_text(original_packing, encoding='utf-8', newline='\n')
    (output / 'packing-after-xmp-fix.txt').write_text(packing, encoding='utf-8', newline='\n')
    (output / 'xmp-three-line-adaptation.diff').write_text(''.join(difflib.unified_diff(original_packing.splitlines(True), packing.splitlines(True), fromfile=TAG + '-original-packing', tofile='windows-xmp-unique-attributes-packing')), encoding='utf-8', newline='\n')
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
    original_multiple_admission = 'require(maxItems > 1) { \"Max items must be higher than 1\" }'
    assert gallery.count(original_multiple_admission) == 1
    original_single = '?.let { result -> result.data ?: result.clipData?.getItemAt(0)?.uri }'
    assert gallery.count(original_single) == 1
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
        + 'internal fun desktopOriginalSingleGalleryResult(resultOk: Boolean, data: String?, clips: List<String>?): String? =\n'
        + '    if (!resultOk) null else data ?: clips?.get(0)\n'
        + 'internal fun desktopOriginalMultipleGalleryResult(resultOk: Boolean, data: String?, clips: List<String>?, maxItems: Int): List<String> {\n'
        + '    ' + original_multiple_admission + '\n' + adapted + '\n}\n'
        + 'internal fun desktopOriginalGalleryMimeType(mediaType: DesktopGalleryVisualMediaType): String? = ' + mime_adapted + '\n',
        encoding='utf-8', newline='\n')
    receipt = {
        'formatVersion': 1, 'originalTag': TAG,
        'originalCommit': subprocess.check_output(['git', 'rev-parse', TAG + '^{commit}'], cwd=repo, text=True).strip(),
        'originalSourceLfPins': [{'path': p, 'sha256Lf': digest(sources[p]), 'tagBytesLfEqual': True} for p in sources],
        'selectedPacking': {'source': PREVIEW, 'startLine': preview[:start].count('\n') + 1,
            'endLine': preview[:stop].count('\n'), 'originalSha256Lf': digest(original_packing), 'sha256Lf': digest(packing), 'unchangedBodyExceptThreeXmpAttributes': True,
            'unchangedBody': False, 'deletedExactLines': omitted,
            'why': 'XML expanded-attribute uniqueness; Camera and GCamera map to identical namespace URI',
            'preserved': ['Camera modern three attributes', 'GCamera MicroVideo legacy four attributes', 'MiCamera', 'Container', 'JPEG marker assembly', 'unchanged MP4 append'],
            'sourceVariablesLiftedToParameters': ['jpegWithExif', 'videoSize'], 'newReturnOnly': 'return jpegSegmentBytes'},
        'gallerySingleResult': {'source': GALLERY, 'originalExpression': original_single, 'adaptedResult': 'if (!resultOk) null else data ?: clips?.get(0)', 'platformEmptyClipsMustBeNull': True},
        'galleryMultipleAdmission': {'originalExactStatement': original_multiple_admission, 'liftedBeforeParseResult': True},
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
    print('PASS original packing with approved three-line XMP attribute fix and unchanged gallery policy; generated ' + str(len(receipt['generated'])) + ' Kotlin sources')
