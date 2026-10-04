"""Prepare original image-format fragments plus a stateless Windows stage encoder.
No Main/registry/Gradle writes, transport, owner, store, cache or final commit.
"""
from pathlib import Path
import hashlib, json, re, subprocess, textwrap

HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
ORIGINAL = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt'
ORIGINAL_COMMIT = 'fcf84853b287662e8a9129ea0d38576c36522a34'
ORIGINAL_SHA = '8ab6d642e5085483ffa5fbe684cb962468c6b93daec46c1eb768e98f8b3fe0b0'

def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)

def write(p, content):
    safe(p.parent).mkdir(parents=True, exist_ok=True)
    safe(p).write_text(content, encoding='utf-8', newline='\n')

def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()

def generate(repo, output, standalone=False):
    raw = subprocess.check_output(['git', 'show', ORIGINAL_COMMIT + ':' + ORIGINAL], cwd=repo)
    text = raw.decode('utf-8').replace('\r\n', '\n')
    assert hashlib.sha256(text.encode()).hexdigest() == ORIGINAL_SHA
    lines = text.splitlines(keepends=True)
    # Exact narrow original assignment/branch fragments, preserved after indentation changes.
    classifiers = ''.join(lines[2037:2040])
    raw_extension = ''.join(lines[2066:2071])
    raw_mime = ''.join(lines[2071:2076])
    static_extension = lines[2141]
    static_mime = lines[2142]
    filename = lines[2076]
    for body in [classifiers, raw_extension, raw_mime, static_extension, static_mime, filename]:
        assert body.strip()
    assert text.count(classifiers) == 1
    assert 'val isGif = imageUrl.contains(".gif", ignoreCase = true)' in classifiers
    assert 'val isWebp = imageUrl.contains(".webp", ignoreCase = true)' in classifiers
    assert 'val isPng = imageUrl.contains(".png", ignoreCase = true)' in classifiers
    assert static_extension.strip() == 'val extension = if (isPng) "png" else "jpg"'
    assert static_mime.strip() == 'val mimeType = if (isPng) "image/png" else "image/jpeg"'
    assert filename.strip() == 'val fileName = "BiliPai_${System.currentTimeMillis()}.$extension"'
    def at(body, spaces): return textwrap.indent(textwrap.dedent(body).rstrip() + '\n', ' ' * spaces)
    pure = f'''// Selected original alpha.9 ImagePreviewDialog.kt LF_SHA256 {ORIGINAL_SHA}
// Original predicates/format/naming assignments; function seams and return statements are declared extraction adapters.
package com.android.purebilibili.feature.dynamic.components

internal fun desktopOriginalStaticGalleryKeepBytes(imageUrl: String): Boolean {{
{at(''.join(lines[2037:2039]), 4)}    return isGif || isWebp
}}

internal fun desktopOriginalStaticGalleryExtension(imageUrl: String): String {{
{at(classifiers, 4)}    if (isGif || isWebp) {{
{at(raw_extension, 8)}        return extension
    }}
{at(static_extension, 4)}    return extension
}}

internal fun desktopOriginalStaticGalleryMimeType(imageUrl: String): String {{
{at(classifiers, 4)}    if (isGif || isWebp) {{
{at(raw_mime, 8)}        return mimeType
    }}
{at(static_mime, 4)}    return mimeType
}}

internal fun desktopOriginalStaticGalleryFileName(imageUrl: String): String {{
    val extension = desktopOriginalStaticGalleryExtension(imageUrl)
{at(filename, 4)}    return fileName
}}
'''
    selected = output / ('generated' if standalone else '') / 'com/android/purebilibili/feature/dynamic/components/DesktopOriginalStaticGalleryFormat.kt'
    write(selected, pure)
    if standalone:
        write(output / 'original/saveImageToGallery.kt', ''.join(lines[2033:2194]))
    declarations = ['desktopOriginalStaticGalleryKeepBytes', 'desktopOriginalStaticGalleryExtension', 'desktopOriginalStaticGalleryMimeType', 'desktopOriginalStaticGalleryFileName']
    normalized = lambda x: re.sub(r'\s+', '', x)
    audit = []
    for label, body in [('classifiers', classifiers), ('rawExtension', raw_extension), ('rawMime', raw_mime), ('staticExtension', static_extension), ('staticMime', static_mime), ('fileName', filename)]:
        assert normalized(body) in normalized(pure)
        audit.append(dict(fragment=label, originalSha256Lf=hashlib.sha256(body.encode()).hexdigest(), tokenBodyPreserved=True))
    audit_record = dict(
        originalTag='v0.2.3-alpha.9', originalCommit=ORIGINAL_COMMIT,
        originalPath=ORIGINAL, originalSha256Lf=ORIGINAL_SHA, fragments=audit,
        declaredAdapters=['Split original local assignments into four uniquely named internal stateless functions', 'Indentation only for selected bodies', 'Added function-level return statements', 'Original raw-branch predicate reused directly'],
        originalAndroidCoilBitmapEncodingBodyRetainedAsReferenceOnly=True,
        WindowsSkiaEncodingIsExplicitPlatformAdapter=True, MainChanged=False, sourceRegistryChanged=False,
    )
    if standalone:
        write(output / 'source-extraction-audit.json', json.dumps(audit_record, ensure_ascii=False, indent=2) + '\n')
    return declarations

if __name__ == '__main__':
    import argparse
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--repo', type=Path, required=True)
    cli.add_argument('--output', type=Path, required=True)
    args = cli.parse_args()
    print(json.dumps(dict(generated=generate(args.repo.resolve(), args.output.resolve()))))
