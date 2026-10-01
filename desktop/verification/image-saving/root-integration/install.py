from pathlib import Path
import hashlib, importlib.util, json, subprocess
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
LOCAL=REPO/'desktop/.local'
def ext(path):
    value=str(Path(path).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def read(path): return ext(path).read_text(encoding='utf-8').replace('\r\n','\n')
def write(path,text):
    ext(path).parent.mkdir(parents=True,exist_ok=True)
    ext(path).write_text(text,encoding='utf-8',newline='\n')
def replace(text,old,new):
    assert text.count(old)==1,old[:100]
    return text.replace(old,new,1)
def sha(raw): return hashlib.sha256(raw).hexdigest()
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO).decode().strip()=='e8a89e11be5ece570fff0d762609644c3cd2b471'
pins=[
 ('dynamic-detail-reply-parity/detail-container-next/static-save-location-next','evidence-manifest.json','34a233ab8d023a65e1e1f4851dc192f66f2f8728fb4fbc865fe332fd55780cbf'),
 ('native-known-folder-save-parity','evidence-manifest.json','32763d32b459cf94a2a2dbb2d31ea76bcde9f7d7b217e84381cad60e43569d3e'),
 ('dynamic-detail-reply-parity/detail-container-next/static-save-codec-next','evidence-manifest.json','8e8317154054295aa85b3042fff5a27289432d625f311e92bd7b28e6c47db694'),
 ('settings-image-save-path-parity','evidence-manifest.json','e91ad2ea410588f4152830ef8f0d2919eb79887f3d0692f357845dc28194577e'),
 ('dynamic-batch-save-parity','frozen-handoff.json','cab609ecb97799ab21231998b31cf514cff924e01e764f6709f77137cc997cc0'),
]
inventories={}
for lane,name,pin in pins:
    raw=ext(LOCAL/lane/name).read_bytes(); assert sha(raw)==pin
    manifest=json.loads(raw); rows=manifest.get('artifacts',manifest.get('files')); assert rows
    for row in rows:
        path=Path(row['path']); actual=path if path.is_absolute() else LOCAL/lane/path
        raw=ext(actual).read_bytes(); assert sha(raw)==row['sha256Bytes'],str(path)
        size=row.get('sizeBytes',row.get('bytes')); assert size is None or len(raw)==size
    inventories[lane]=rows
payloads=[
 (pins[0][0],'prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSaveLocationPreferences.kt'),
 (pins[1][0],'prepared/desktop/src/main/kotlin/com/bilipai/desktop/platform/DesktopWindowsImageSaveDirectory.kt'),
 (pins[2][0],'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicStaticImageCodec.kt'),
 (pins[2][0],'prepared/desktop/tools/extract-upstream-dynamic-static-image-codec.py'),
 (pins[3][0],'prepared/desktop/tools/extract-image-save-settings-ui.py'),
 (pins[3][0],'prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSavePathSettings.kt'),
]
receipt=[]
for lane,source in payloads:
    target=source.removeprefix('prepared/')
    assert not (REPO/target).exists()
    body=read(LOCAL/lane/source); write(REPO/target,body)
    receipt.append(dict(source=lane+'/'+source,target=target,sha256Lf=sha(body.encode())))
# Production command adapter only; the sole format producer remains generate().
path=REPO/'desktop/tools/extract-upstream-dynamic-static-image-codec.py'
body=read(path)
body=replace(body,"if __name__ == '__main__':\n    assert not safe(HERE / 'evidence-manifest.json').exists(), 'frozen'\n    print(json.dumps(dict(generated=generate(ROOT, HERE, standalone=True))))", "if __name__ == '__main__':\n    import argparse\n    cli = argparse.ArgumentParser(description=__doc__)\n    cli.add_argument('--repo', type=Path, required=True)\n    cli.add_argument('--output', type=Path, required=True)\n    args = cli.parse_args()\n    print(json.dumps(dict(generated=generate(args.repo.resolve(), args.output.resolve()))))")
write(path,body)
registry_path=REPO/'desktop/upstream-sources.json'
registry=json.loads(read(registry_path)); assert len(registry['sources'])==622
features={
 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt':'dynamic-static-image-save-parity',
 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt':'settings-image-save-path-parity',
 'app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt':'settings-image-save-path-ui',
 'app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt':'settings-image-save-path-ui',
}
for original,feature in features.items():
    matches=[row for row in registry['sources'] if row['path']==original]; assert len(matches)==1
    assert feature not in matches[0]['features']; matches[0]['features'].append(feature)
write(registry_path,json.dumps(registry,ensure_ascii=False,indent=2)+'\n')
path=REPO/'desktop/build.gradle.kts';body=read(path)
insert='''val extractUpstreamDynamicStaticImageCodec by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-static-image-codec.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-static-image-codec").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-dynamic-static-image-codec.py")
    inputs.files(sources.filter { "dynamic-static-image-save-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-static-image-codec"))
}

val extractImageSaveSettingsUi by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-image-save-settings-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/settings-image-save-path").get().asFile.absolutePath)
    inputs.files("tools/extract-image-save-settings-ui.py", "tools/extract-upstream-settings-storage-entries.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/extract-upstream-api.py")
    inputs.files(sources.filter { "settings-image-save-path-ui" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings-image-save-path"))
}

'''
body=replace(body,'val extractUpstreamDynamicGalleryMotionPhoto by tasks.registering(Exec::class) {',insert+'val extractUpstreamDynamicGalleryMotionPhoto by tasks.registering(Exec::class) {')
body=replace(body,'    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-gallery-motion-photo"))','    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-gallery-motion-photo"))\n    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-static-image-codec"))\n    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-image-save-path"))')
body=replace(body,'tasks.named("compileKotlin") { dependsOn(verifyUpstreamDynamicMedia, verifyDynamicMediaDependencies) }','tasks.named("compileKotlin") { dependsOn(verifyUpstreamDynamicMedia, verifyDynamicMediaDependencies, extractUpstreamDynamicStaticImageCodec, extractImageSaveSettingsUi) }')
write(path,body)
subprocess.run(['git','-c','core.longpaths=true','apply',str(LOCAL/'settings-image-save-path-parity/consumer.patch')],cwd=REPO,check=True)
path=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsTree.kt'
body=read(path).replace('全局下载目录、图片目录和原版缓存管理尚未完整移植。','全局下载目录和原版缓存管理尚未完整移植。');write(path,body)
write(HERE/'source-install-receipt.json',json.dumps(dict(base='e8a89e11be5ece570fff0d762609644c3cd2b471',pins=pins,payloads=receipt,registryCount=622,platformBoundaries=['Native Windows codec and KnownFolder mapping','Windows file URI and collision suffix','Production CLI adapter only for static producer']),indent=2)+'\n')
print(json.dumps(dict(installed=len(payloads),registryCount=622,sourceOnly=True)))
