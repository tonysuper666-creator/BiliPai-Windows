"""Root installation of individually reviewed source payloads; no fixture binaries."""
from pathlib import Path
import hashlib, json, os, subprocess

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
LOCAL = REPO / 'desktop/.local'

def ext(path):
    value = str(Path(path).absolute()); prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)

def sha(data): return hashlib.sha256(data).hexdigest()
def read(path): return ext(path).read_text(encoding='utf-8').replace('\r\n', '\n')
def write(path, value):
    ext(path).parent.mkdir(parents=True, exist_ok=True)
    ext(path).write_text(value, encoding='utf-8', newline='\n')
def replace(value, old, new):
    assert value.count(old) == 1, old[:120]
    return value.replace(old, new, 1)

assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO).decode().strip() == 'e10471a960e8d7dde10d0395c4f5f6c07dbcb4f7'
initial_status = subprocess.check_output(['git', '-c', 'core.longpaths=true', 'status', '--porcelain'], cwd=REPO).decode()
pins = [
    ('dynamic-gallery-motion-photo-parity', 'frozen-handoff.json', '08da9ef3efd871724d1cc58482dc5945e18c9312229e4749f75e0dc405826c18'),
    ('dynamic-motion-photo-download-owner-parity', 'frozen-handoff.json', 'bd97df3e2aa1ea4529fa49f9196b8f95db7e0100dfcc20aff79d0b091a14a503'),
    ('comment-qr-windows-clip-parity', 'frozen-handoff.json', '382b64f3b20ab5b0d330a78bcedc6a74a7a2d38a8be32f946dfa4905f25b5f57'),
    ('dynamic-detail-reply-parity/detail-container-next/motion-photo-commit-cancel-delta', 'evidence-manifest.json', 'af0187e4604340a71bc5bd3c7ece5065836e83ea1f2e2014eb28e8fad22d41a3'),
    ('dynamic-detail-root-mounted-proof/comment-confirmation-receipt-delta', 'evidence-manifest.json', 'af93f3e64052f4650b9460b7ca6f8efc6523141969c90607a2c182f72d12fb84'),
]
inventories = {}
for lane, name, expected in pins:
    raw = ext(LOCAL/lane/name).read_bytes(); assert sha(raw) == expected
    inventory = json.loads(raw); rows = inventory.get('artifacts', inventory.get('files'))
    assert rows
    for row in rows:
        data = ext(LOCAL/lane/row['path']).read_bytes()
        assert sha(data) == row['sha256Bytes'], row['path']
        size = row.get('bytes', row.get('sizeBytes'))
        if size is not None: assert len(data) == size, row['path']
    inventories[lane] = {}
    for row in rows:
        path = Path(row['path'])
        key = path.relative_to(LOCAL/lane).as_posix() if path.is_absolute() else row['path'].replace('\\', '/')
        inventories[lane][key] = row

payloads = [
    ('dynamic-gallery-motion-photo-parity', 'prepared/desktop/tools/extract-upstream-dynamic-gallery-motion-photo-valid-xmp.py', 'desktop/tools/extract-upstream-dynamic-gallery-motion-photo.py'),
    ('dynamic-gallery-motion-photo-parity', 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoExif.kt', 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoExif.kt'),
    ('dynamic-gallery-motion-photo-parity', 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicGallerySelection.kt', 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicGallerySelection.kt'),
    ('dynamic-detail-reply-parity/detail-container-next/motion-photo-commit-cancel-delta', 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.kt', 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicMotionPhotoFiles.kt'),
    ('dynamic-motion-photo-download-owner-parity', 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt', 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'),
    ('comment-qr-windows-clip-parity', 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCommentImageCanvas.kt', 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCommentImageCanvas.kt'),
    ('comment-qr-windows-clip-parity', 'prepared/desktop/tools/extract-upstream-dynamic-reply.py', 'desktop/tools/extract-upstream-dynamic-reply.py'),
    ('dynamic-detail-root-mounted-proof/comment-confirmation-receipt-delta', 'desired-extract-upstream-dynamic-detail.py', 'desktop/tools/extract-upstream-dynamic-detail.py'),
]
receipt = []
for entry in initial_status.splitlines():
    target=entry[3:]
    matches=[(lane, source) for lane, source, destination in payloads if destination==target]
    assert len(matches)==1, entry
    lane, source=matches[0]
    assert read(REPO/target)==read(LOCAL/lane/source), target
for lane, original, target in payloads:
    assert original in inventories[lane]
    data = read(LOCAL/lane/original)
    prior = subprocess.run(['git', 'show', 'HEAD:'+target], cwd=REPO, capture_output=True)
    receipt.append(dict(source=lane+'/'+original, target=target, candidateSha256Lf=sha(data.encode()), priorSha256Lf=sha(prior.stdout.replace(b'\r\n', b'\n')) if prior.returncode==0 else None))
    write(REPO/target, data)

# Retain the single existing chooser, but use the actual Root Window as parent.
path = REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
body = read(path)
body = replace(body, 'import java.io.IOException', 'import java.awt.Component\nimport java.io.IOException')
body = replace(body, '= ::selectDynamicSaveTarget,', '= { name, mime -> selectDynamicSaveTarget(name, mime) },')
body = replace(body, '= ::selectDynamicSaveDirectory,', '= { selectDynamicSaveDirectory() },')
body = replace(body, 'selectDynamicSaveTarget(name: String, mime: String)', 'selectDynamicSaveTarget(name: String, mime: String, parent: Component? = null)')
body = replace(body, 'private suspend fun selectDynamicSaveDirectory()', 'internal suspend fun selectDynamicSaveDirectory(parent: Component? = null)')
assert body.count('chooser.showSaveDialog(null)') == 2
body = body.replace('chooser.showSaveDialog(null)', 'chooser.showSaveDialog(parent)')
body = replace(body, 'JOptionPane.showConfirmDialog(null,', 'JOptionPane.showConfirmDialog(parent,')
body = replace(body, '// This actual Main type is unchanged. Task-only compile omits only this line\n// so the real frozen Main class is used, without a SaveTarget override.\n', '')
write(path, body)
write(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicSaveParent.kt',
      'package com.bilipai.desktop.ui\n\nimport androidx.compose.runtime.staticCompositionLocalOf\nimport java.awt.Component\n\n/** Existing Root Window for the single image/comment save chooser. */\ninternal val LocalDesktopDynamicSaveParent = staticCompositionLocalOf<Component?> { null }\n')

path = REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDynamicCardHost.kt'
body = read(path)
body = replace(body, '    val alive=remember(repository,capturedEpoch,item.id_str)', '    val saveParent=LocalDesktopDynamicSaveParent.current\n    val alive=remember(repository,capturedEpoch,item.id_str,saveParent)')
body = replace(body, '    val assets=remember(operations){DesktopDynamicImageAssets(repository.httpClient,operations::isOwned)}',
    '    val assets=remember(operations){\n        val guard=repository.dynamicCacheSessionGuard\n        val owner=checkNotNull(guard.dynamicCacheOwner())\n        DesktopDynamicImageAssets(repository.httpClient,\n            stillOwned={operations.isOwned()&&owner.epoch==capturedEpoch},\n            sessionGuard=guard,expectedOwner=owner,\n            selectTarget={name,mime->selectDynamicSaveTarget(name,mime,saveParent)},\n            selectDirectory={selectDynamicSaveDirectory(saveParent)})\n    }')
body = replace(body, 'alive.set(false);scope.cancel();assets.close()', 'alive.set(false);assets.close();scope.cancel()')
body = replace(body, 'override suspend fun saveMotionPhoto(imageUrl:String,videoUrl:String):Boolean=error("Windows 实况照片合成尚未接入；可分别保存图片和实况视频")',
    'override suspend fun saveMotionPhoto(imageUrl:String,videoUrl:String)=assets.saveMotionPhoto(imageUrl,videoUrl)')
write(path, body)

path = REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicEditorWindowsPickers.kt'
body = read(path)
body = replace(body, 'isMultiSelectionEnabled = true', 'isMultiSelectionEnabled = maxItems > 1')
body = replace(body, '                val paths = chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) }\n                    .take(maxItems.coerceIn(1, 9)).map { it.toPath() }\n                val sources = selectedImages.accept(paths)',
    '                val sources = DesktopDynamicGallerySelection(selectedImages, stillOwned).acceptResult(\n                    approved = true, single = chooser.selectedFile?.toPath(),\n                    multiple = chooser.selectedFiles.map { it.toPath() }.takeIf { it.isNotEmpty() },\n                    maxItems = maxItems.coerceIn(1, 9))')
write(path, body)
path = REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt'
body = read(path)
body = replace(body, '    val clipboard=LocalDesktopTextClipboard.current', '    val clipboard=LocalDesktopTextClipboard.current\n    val saveParent=LocalDesktopDynamicSaveParent.current')
body = replace(body, 'val platform=remember(alive,clipboard)', 'val platform=remember(alive,clipboard,saveParent)')
body = replace(body, '".png","image/png")', '".png","image/png",saveParent)') if '".png","image/png")' in body else replace(body, '.png","image/png")', '.png","image/png",saveParent)')
body = replace(body, '        val beforeComment=commentVersion', '        val beforeComment=commentVersion\n        val beforeCommentReceipt=replySession.commentConfirmationRevision.value')
body = replace(body, 'commentChanged=commentVersion!=beforeComment)', 'commentChanged=commentVersion!=beforeComment||\n                replySession.commentConfirmationRevision.value!=beforeCommentReceipt)')
write(path, body)
path = REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
body = replace(read(path), '        LocalDesktopDetailForeground provides (hostDisplayable && hostVisible),',
    '        LocalDesktopDetailForeground provides (hostDisplayable && hostVisible),\n        LocalDesktopDynamicSaveParent provides hostWindow,')
write(path, body)

path = REPO/'desktop/upstream-sources.json'
manifest = json.loads(read(path)); assert len(manifest['sources']) == 621
by_path = {row['path']: row for row in manifest['sources']}
for source in ['app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt', 'app/src/main/java/com/android/purebilibili/core/util/GalleryVisualMediaContracts.kt']:
    identity = sha(read(REPO/source).encode())
    if source not in by_path:
        row = dict(path=source, mode='policy-extract', features=[], sha256=identity)
        manifest['sources'].append(row)
    else:
        row=by_path[source]; assert row['sha256'] == identity
    if 'dynamic-media-export-parity' not in row['features']: row['features'].append('dynamic-media-export-parity')
assert len(manifest['sources']) == 622
write(path, json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')

# Official Apache notices are resources of the actual product, not task-only.
deps = LOCAL/'dynamic-gallery-motion-photo-parity/dependencies'
notice_rows=[]; dependency_rows=[]
for artifact, version, prefix, group in [('commons-imaging', '1.0.0-alpha6', 'official-bin', 'org.apache.commons'), ('commons-io', '2.19.0', 'official-commons-io', 'commons-io'), ('commons-lang3', '3.17.0', 'official-commons-lang3', 'org.apache.commons')]:
    dependency_rows.append(dict(coordinate=group+':'+artifact+':'+version, sha256=sha(ext(deps/(artifact+'-'+version+'.jar')).read_bytes())))
    for name in ['LICENSE', 'NOTICE']:
        source=deps/(prefix+'-'+name+'.txt')
        target='licenses/dynamic-media/'+artifact+'/'+name+'.txt'
        raw=ext(source).read_bytes(); ext(REPO/'desktop/src/main/resources'/target).parent.mkdir(parents=True, exist_ok=True)
        ext(REPO/'desktop/src/main/resources'/target).write_bytes(raw)
        notice_rows.append(dict(resource=target, sha256=sha(raw)))
write(REPO/'desktop/third-party/dynamic-media-dependency-pins.json', json.dumps(dict(dependencies=dependency_rows, notices=notice_rows), indent=2)+'\n')
write(HERE/'source-install-receipt.json', json.dumps(dict(baseCommit='e10471a960e8d7dde10d0395c4f5f6c07dbcb4f7', registryBefore=621, registryAfter=622, payloads=receipt, runtimeAccepted=False, packaged=False), indent=2)+'\n')
print(json.dumps(dict(installedPayloads=len(receipt), registryCount=622, dependencies=len(dependency_rows), notices=len(notice_rows))))
