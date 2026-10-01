"""Install the frozen stream and Space source payloads into the candidate."""
from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];CANDIDATE=BASE.parent/'BiliPai-v023'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def raw(path):return ext(path).read_bytes()
def lf(path):return raw(path).replace(b'\r\n',b'\n')
def verify(lane,name,pin):
    data=raw(lane/name);assert sha(data)==pin;meta=json.loads(data)
    for row in meta['artifacts']:
        data=raw(lane/row['path']);assert sha(data)==row['sha256Bytes']
        expected=next((row[k] for k in ['sizeBytes','bytes','size','byteCount'] if k in row),None)
        if expected is not None:assert len(data)==expected
    return meta
stream=CANDIDATE/'desktop/.local/stable-editor-stream-upload'
stream_meta=verify(stream,'evidence-manifest.json','e48ee2b9dbf7bd58f8460fee44f32fe700cad98e8ed8d2a834c9472042575f29')
stream_handoff=json.loads(raw(stream/'frozen-handoff.json'))
assert sha(raw(stream/'frozen-handoff.json'))=='bac9136a4a4011133e6dd76320434983aeb1f9ec0c1fd24506fa337a1ec4e2e1'
installed=[]
for row in stream_handoff['sourceCandidates']:
    if not row['changed']:continue
    assert sha(lf(CANDIDATE/row['path']))==row['baseLfSha256'],row['path']
    data=lf(stream/'prepared'/row['path']);assert sha(data)==row['candidateLfSha256']
    ext(CANDIDATE/row['path']).write_bytes(data);installed.append(dict(path=row['path'],sha256Lf=sha(data)))
patch=str(stream/'gradle-input-only.patch')
subprocess.run(['git','-c','core.longpaths=true','apply','--check',patch],cwd=CANDIDATE,check=True)
subprocess.run(['git','-c','core.longpaths=true','apply',patch],cwd=CANDIDATE,check=True)
space=BASE/'desktop/.local/space-image-preview-consumer-v023-parity'
space_meta=verify(space,'frozen-handoff.json','7586f91c748d3f574d313020d8e453fb5e9b431bb5950359b06664d0696d5ecf')
inventory=json.loads(raw(space/'source-inventory.json'))
assert sha(raw(space/'source-inventory.json'))=='96f9c40ee600d6c609b8bd5d828ead6592da16ba2b34ad9dd4c681f0949aa879'
for row in inventory['payloadTargets']:
    relative=row['target'];candidate=space/row['candidate']
    data=lf(candidate);assert sha(data)==row['sha256Bytes'],relative
    target=CANDIDATE/relative
    if target.exists():assert lf(target)==lf(BASE/relative),relative
    else:assert not (BASE/relative).exists(),relative
    ext(target.parent).mkdir(parents=True,exist_ok=True);ext(target).write_bytes(data)
    installed.append(dict(path=relative,sha256Lf=sha(data)))
shell=CANDIDATE/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
assert lf(shell)==lf(BASE/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt')
ext(shell).write_bytes(lf(shell))
patch=str(space/'root-feedback.patch')
subprocess.run(['git','-c','core.longpaths=true','apply','--check',patch],cwd=CANDIDATE,check=True)
subprocess.run(['git','-c','core.longpaths=true','apply',patch],cwd=CANDIDATE,check=True)
build=CANDIDATE/'desktop/build.gradle.kts';text=lf(build).decode()
anchor='val extractUpstreamSpaceContributions by tasks.registering(Exec::class) {'
assert text.count(anchor)==1
task='''val extractUpstreamSpaceImagePreviews by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-space-image-preview-callers.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/space-image-previews").get().asFile.absolutePath)
    inputs.files("tools/extract-space-image-preview-callers.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "space-image-preview" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/space-image-previews"))
}

'''
text=text.replace(anchor,task+anchor)
anchor='    kotlin.srcDir(layout.buildDirectory.dir("generated/space-overview"))'
assert text.count(anchor)==1
text=text.replace(anchor,anchor+'\n    kotlin.srcDir(layout.buildDirectory.dir("generated/space-image-previews"))')
anchor='tasks.named("compileKotlin") { dependsOn(extractUpstreamSpace, extractUpstreamSpaceContributions, extractUpstreamSpaceOverview) }'
assert text.count(anchor)==1
text=text.replace(anchor,'tasks.named("compileKotlin") { dependsOn(extractUpstreamSpace, extractUpstreamSpaceContributions, extractUpstreamSpaceOverview, extractUpstreamSpaceImagePreviews) }')
ext(build).write_text(text,encoding='utf-8',newline='\n')
registry=CANDIDATE/'desktop/upstream-sources.json';meta=json.loads(raw(registry))
matches=[row for row in meta['sources'] if row['path']=='app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt']
assert len(matches)==1 and matches[0]['sha256']=='2c7063c8c9b112b10f7b5394b34ddfc4362fcf2984d3eae3a3364469cc3557ca'
assert 'space-image-preview' not in matches[0]['features'];matches[0]['features'].append('space-image-preview')
assert len(meta['sources'])==622
ext(registry).write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
receipt=dict(schema='stable-consumer-candidate-install-v1',candidateInstalled=True,mainInstalled=False,
    verifiedStreamArtifacts=len(stream_meta['artifacts']),verifiedSpaceArtifacts=len(space_meta['artifacts']),
    registryCount=622,wholeStableCompiled=False,runtimeAccepted=False,installed=installed,
    rootFeedbackInstalled=True,newSpaceProducerRegistered=True,streamVerifierSharedSourceInputRegistered=True)
(HERE/'consumer-install.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(installedPayloads=len(installed),registryCount=622,mainChanged=False)))
