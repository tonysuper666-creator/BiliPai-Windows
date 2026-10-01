from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=MAIN/'desktop/.local/stable-video-detail-full-ui-parity';OUT=HERE/'video-full-units-install49'
assert not OUT.exists();sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def lf(p):return read(p).replace(b'\r\n',b'\n')
raw=read(LANE/'frozen-stage1.json');assert sha(raw)=='6019bfa5ffdd3ca51b413add293c6dd724867e5f9c9731d3dbba2f515181e564'
frozen=json.loads(raw);assert len(frozen['artifacts'])==620
for row in frozen['artifacts']:assert sha(read(LANE/row['path']))==row['sha256Bytes'],row['path']
raw=read(LANE/'INSTALL-RECIPE.json');assert sha(raw)=='5e546554006ffd61b0ae25bfb231a5aa5445df578e10c98e2c24a1149d1f6b80';recipe=json.loads(raw)
pending={}
for row in recipe['soleTools']:
 data=read(LANE/row['path']);assert sha(data)==row['sha256Bytes'];target=REPO/'desktop/tools'/Path(row['path']).name;assert not wide(target).exists();pending[target]=data
for row in recipe['manualOutputs']:
 data=lf(LANE/'prepared/manual'/row['path']);assert sha(data)==row['sha256LF'];target=REPO/'desktop/src/main/kotlin'/row['path'];assert not wide(target).exists();pending[target]=data
visibility=json.loads(read(LANE/recipe['metadataVisibilityLocalHunk']['path']));target=REPO/visibility['path'];data=lf(target).decode();assert sha(data.encode())==visibility['baseSha256LF']
for h in visibility['hunks']:
 assert data.count(h['before'])==h['expectedCount'];data=data.replace(h['before'],h['after'],h['expectedCount'])
pending[target]=data.encode()
ops=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt';data=lf(ops).decode();fragment=lf(LANE/recipe['operationsMemberFragment']['path']).decode();marker=recipe['operationsMemberFragment']['insertBefore'];assert data.count(marker)==1 and '// Desktop original complete video engagement/info binding' not in data
pending[ops]=data.replace(marker,fragment.rstrip('\n')+'\n\n'+marker,1).encode()
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(read(reg));assert len(registry['sources'])==939
inventory=json.loads(read(LANE/'prepared/registry-delta.json'));newCount=0;feature='stable-video-detail-full-units'
for row in inventory['identities']:
 assert sha(lf(REPO/row['path']))==row['sha256LF'],row['path']
 existing=next((r for r in registry['sources'] if r['path']==row['path']),None)
 if existing:
  assert existing['sha256']==row['sha256LF']
  if row['requestedMode']=='direct':assert existing['mode']=='direct'
  if feature not in existing['features']:existing['features'].append(feature)
 else:
  assert row['requestedMode']!='reference'
  registry['sources'].append(dict(path=row['path'],sha256=row['sha256LF'],features=[feature],mode='direct' if row['requestedMode']=='direct' else 'policy-extract'));newCount+=1
assert newCount==14 and len(registry['sources'])==953
pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
gradle=REPO/'desktop/build.gradle.kts';data=lf(gradle).decode();marker='kotlin.sourceSets.named("main") {';assert data.count(marker)==1 and 'extractOriginalVideoDetailUnits' not in data
snippet='''val extractOriginalVideoDetailUnits by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-detail-full-units.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-detail-units").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-detail-full-units.py", "tools/sync-upstream.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-detail-full-units" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-detail-units"))
}
val verifyOriginalVideoDetailMembers by tasks.registering(Exec::class) {
    dependsOn(extractOriginalVideoDetailUnits)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-video-detail-full-units.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-detail-units").get().asFile.absolutePath,
        layout.buildDirectory.file("generated/original-video-detail-members-verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-video-detail-full-units.py", "src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt")
    inputs.file(layout.buildDirectory.file("generated/original-video-detail-units/video-operations-members.fragment"))
    outputs.file(layout.buildDirectory.file("generated/original-video-detail-members-verification.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-detail-units")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoDetailUnits, verifyOriginalVideoDetailMembers) }

'''
pending[gradle]=data.replace(marker,snippet+marker,1).encode()
OUT.mkdir()
for target,data in pending.items():
 if wide(target).exists():
  baseline=wide(OUT/'baseline'/target.relative_to(REPO));baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(read(target))
 wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(data)
report=dict(applied=True,newManuals=5,newTools=2,newOriginalIdentities=14,registryCount=953,selectedOutputs=18,directOutputsSoleSync=8,originalOtherProtocolGatesPreserved=True,ordinaryCompletePlayerNotYetMounted=True,wholeCompilationPending=True,newRuntimeDependencies=0,newExeDeployed=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
