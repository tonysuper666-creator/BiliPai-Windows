from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=MAIN/'desktop/.local/stable-video-player-full-controls-parity';OUT=HERE/'player-controls-install50'
assert not OUT.exists();sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def lf(p):return read(p).replace(b'\r\n',b'\n')
raw=read(LANE/'frozen-stage2.json');assert sha(raw)=='d40fd1b37f2c4c58af97ce0314e70f68f412b4d90d2ec17b7830d65d1dffcd84'
frozen=json.loads(raw);assert len(frozen['rawArtifacts'])==790
for row in frozen['rawArtifacts']:assert sha(read(LANE/row['path']))==row['sha256Bytes'],row['path']
raw=read(LANE/'INSTALL-RECIPE.json');assert sha(raw)=='9281f11975eba576e2aec5181ec950b9638f56a3c00630e1f206d6bcde45074a';recipe=json.loads(raw);pending={}
for row in recipe['soleTools']:
 data=lf(LANE/row['path']);assert sha(data)==row['sha256LF'];target=REPO/'desktop/tools'/Path(row['path']).name;assert not wide(target).exists();pending[target]=data
for row in recipe['manualOutputs']:
 data=lf(LANE/'prepared/manual'/row['path']);assert sha(data)==row['sha256LF'];target=REPO/'desktop/src/main/kotlin'/row['path'];assert not wide(target).exists();pending[target]=data
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(read(reg));assert len(registry['sources'])==959
delta=json.loads(read(LANE/recipe['registryDelta']));added=0
for row in delta['sources']:
 assert sha(lf(REPO/row['path']))==row['sha256'],row['path']
 existing=next((r for r in registry['sources'] if r['path']==row['path']),None)
 if existing:
  assert existing['sha256']==row['sha256']
  if row['proposedMode']=='direct':assert existing['mode']=='direct'
  if row['feature'] not in existing['features']:existing['features'].append(row['feature'])
 else:
  mode='reference-only' if row['proposedMode']=='reference' else row['proposedMode']
  registry['sources'].append(dict(path=row['path'],sha256=row['sha256'],features=[row['feature']],mode=mode));added+=1
assert added==43 and len(registry['sources'])==1002
pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
gradle=REPO/'desktop/build.gradle.kts';data=lf(gradle).decode();assert 'extractOriginalPlayerFullControls' not in data
snippet='''val extractOriginalPlayerFullControls by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoDetailUnits, extractOriginalOfflinePlayer)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-player-full-controls.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-player-full-controls").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-player-full-controls.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-player-full-controls" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-player-full-controls"))
}
val verifyOriginalPlayerFullControls by tasks.registering(Exec::class) {
    dependsOn(extractOriginalPlayerFullControls)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-video-player-full-controls.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-player-full-controls").get().asFile.absolutePath,
        layout.buildDirectory.file("generated/original-video-player-controls-verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-video-player-full-controls.py", "tools/extract-upstream-video-player-full-controls.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.dir(layout.buildDirectory.dir("generated/original-video-player-full-controls"))
    outputs.file(layout.buildDirectory.file("generated/original-video-player-controls-verification.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-player-full-controls")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalPlayerFullControls, verifyOriginalPlayerFullControls) }
'''
pending[gradle]=(data.rstrip('\n')+'\n\n'+snippet).encode()
OUT.mkdir()
for target,data in pending.items():
 if wide(target).exists():
  p=wide(OUT/'baseline'/target.relative_to(REPO));p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(read(target))
 wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(data)
report=dict(applied=True,newOriginalIdentities=43,sourceRegistryCount=1002,newManuals=8,newTools=2,productionSelectedOutputs=20,directOutputsSoleSync=26,ordinaryPlayerAssemblyPending=True,wholeCompilationPending=True,newRuntimeDependencies=0,newExeDeployed=False)
wide(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
