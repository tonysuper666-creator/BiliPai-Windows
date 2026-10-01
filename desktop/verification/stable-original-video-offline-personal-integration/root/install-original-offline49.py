from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=MAIN/'desktop/.local/stable-original-offline-player-parity';OUT=HERE/'original-offline-install49'
assert not OUT.exists();sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def lf(p):return read(p).replace(b'\r\n',b'\n')
raw=read(LANE/'frozen-handoff.json');assert sha(raw)=='b7d3bbe5c4e95162e643056a8f0a316167b911f6c4313ee7683446cc68e53553';frozen=json.loads(raw);assert len(frozen['artifacts'])==84
for row in frozen['artifacts']:assert sha(read(LANE/row['path']))==row['sha256Bytes'],row['path']
raw=read(LANE/'install-contract.json');assert sha(raw)=='9efc1f249d25ca957d09394305ea1e70f87b4e7cc3cf816fa8f3c766cadff078';contract=json.loads(raw);pending={}
for row in contract['payloads']:
 data=read(LANE/row['path']);assert sha(data)==row['sha256Bytes'];target=REPO/row['path'].removeprefix('prepared/');assert not wide(target).exists();pending[target]=data
for row in contract['localDeltas']:
 delta=json.loads(read(LANE/row['file']));target=REPO/row['path'];data=lf(target).decode();assert sha(data.encode())==row['baseLfSha256'];assert len(delta['hunks'])==row['hunks']
 for h in delta['hunks']:
  assert data.count(h['before'])==1,h['label'];data=data.replace(h['before'],h['after'],1)
 assert sha(data.encode())==row['candidateLfSha256'];pending[target]=data.encode()
assert sha(lf(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPlayerSurface.kt'))=='332bc71f6a56647e7c913e92c52478dd34f127bda8ccc593598ae3795eedc79b'
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(read(reg));assert len(registry['sources'])==953;newCount=0
for row in json.loads(read(LANE/contract['registryDelta']['path'])):
 assert sha(lf(REPO/row['path']))==row['sha256'];existing=next((r for r in registry['sources'] if r['path']==row['path']),None)
 if row['operation']=='addOneSourceIdentity':
  assert existing is None;registry['sources'].append({k:row[k] for k in ['path','sha256','mode','features']});newCount+=1
 else:
  assert existing and existing['sha256']==row['sha256']
  for feature in row['features']:
   if feature not in existing['features']:existing['features'].append(feature)
assert newCount==2 and len(registry['sources'])==955;pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
gradle=REPO/'desktop/build.gradle.kts';data=lf(gradle).decode();assert 'extractOriginalOfflinePlayer' not in data
snippet='''val extractOriginalOfflinePlayer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-offline-player.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/original-offline-player").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-offline-player.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(sources.filter { "stable-offline-player-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-offline-player"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-offline-player")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalOfflinePlayer) }
'''
pending[gradle]=(data.rstrip('\n')+'\n\n'+snippet).encode();OUT.mkdir()
for target,data in pending.items():
 if wide(target).exists():
  baseline=wide(OUT/'baseline'/target.relative_to(REPO));baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(read(target))
 wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(data)
report=dict(applied=True,payloads=4,existingHunks=21,newOriginalIdentities=2,sourceRegistryCount=955,sharedSurfaceReused=True,newRuntimeDependencies=0,rootFullOfflineMounted=False,wholeCompilationPending=True,newExeDeployed=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
