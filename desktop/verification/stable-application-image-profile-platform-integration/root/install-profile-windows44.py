from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-profile-windows-platform-parity';OUT=HERE/'profile-windows-install44'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
raw=wide(LANE/'frozen-handoff.json').read_bytes();assert sha(raw)=='15aa4e1197a7b804f8bdb9f6942972761c7864a8d552dbbdef72a33930f03db6'
frozen=json.loads(raw)
for r in frozen['artifacts']:assert sha(wide(LANE/r['path']).read_bytes())==r['sha256Bytes'],r['path']
contract=json.loads((LANE/'install-contract.json').read_bytes());assert sha((LANE/'install-contract.json').read_bytes())=='89e26cf5687a2bf20c4fa7def9c066914477847bbf40083f23c3209c3f6b7be9'
pending={};hunks=json.loads((LANE/contract['sharedHunks']).read_bytes())
assert len(hunks['rows'])==5
for r in contract['payloads']:
 data=wide(r['source']).read_bytes();assert sha(data)==r['sha256Bytes'];target=REPO/r['target'];assert not target.exists(),r['target'];pending[target]=data
for r in hunks['rows']:
 target=REPO/r['target'];data=pending.get(target,target.read_bytes()).decode().replace('\r\n','\n')
 assert sha(r['before'].encode())==r['beforeSha256LF'];assert sha(r['after'].encode())==r['afterSha256LF']
 assert data.count(r['before'])==1,(r['target'],r['reason']);pending[target]=data.replace(r['before'],r['after'],1).encode()
for r in hunks['targets']:
 assert sha((REPO/r['path']).read_bytes().replace(b'\r\n',b'\n'))==r['baseSha256LF'],r['path']
 assert sha(pending[REPO/r['path']])==r['candidateSha256LF'],r['path']
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(reg.read_bytes());assert len(registry['sources'])==930
recipe=json.loads((LANE/'registry-merge-recipe.json').read_bytes())
for r in recipe['newRows']:
 assert not any(old['path']==r['path']for old in registry['sources']);assert sha((REPO/r['path']).read_bytes().replace(b'\r\n',b'\n'))==r['sha256'];registry['sources'].append(r)
for r in recipe['existingRows']:
 old=next(v for v in registry['sources']if v['path']==r['path']);assert old['sha256']==r['sha256'] and old['mode']==r['mode'];assert r['appendFeature']not in old['features'];old['features'].append(r['appendFeature'])
assert len(registry['sources'])==931;pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
gradle=REPO/'desktop/build.gradle.kts';text=gradle.read_text(encoding='utf-8');marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert text.count(marker)==1
snippet='''// Full original Profile image/video import on the retained Windows entry.
val extractUpstreamProfileWallpaperImport by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-profile-wallpaper-import.py",
        "--source-repo", repositoryRoot.absolutePath,
        "--output-dir", layout.buildDirectory.dir("generated/profile-wallpaper-import").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-profile-wallpaper-import.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-profile-windows-platform" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/profile-wallpaper-import"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/profile-wallpaper-import")) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamProfileWallpaperImport) }

'''
assert 'val extractUpstreamProfileWallpaperImport 'not in text;pending[gradle]=text.replace(marker,snippet+marker,1).encode()
OUT.mkdir()
for target,data in pending.items():
 if target.exists():
  baseline=wide(OUT/'baseline'/target.relative_to(REPO));baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(target.read_bytes())
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
(OUT/'installed.json').write_text(json.dumps(dict(payloads=contract['payloads'],hunks=hunks['rows'],sourceCountBefore=930,sourceCountAfter=931,sourcePacketSha256Bytes=sha(raw),newRuntimeArtifacts=0,profileRootMounted=False,actualWindowAccepted=False,newExeDeployed=False),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(applied=True,payloads=6,sharedHunks=5,sourceCount=931,profileRootMounted=False)))
