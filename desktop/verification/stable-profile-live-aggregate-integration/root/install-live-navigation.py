from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=MAIN/'desktop/.local/stable-home-live-navigation-parity';OUT=HERE/'live-navigation-install';assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
manifest=wide(LANE/'frozen-handoff.json').read_bytes();assert sha(manifest)=='f029db02a6efff2c48f142d675e1cfbfaf6aa5b644823adcb3facb59d9620818'
d=json.loads(manifest)
for r in d.get('artifacts',d.get('rawArtifacts',[])):assert sha(wide(LANE/r['path']).read_bytes())==r['sha256Bytes'],r['path']
contract=json.loads((LANE/'install-contract.json').read_text(encoding='utf-8'))
for r in contract['payloads']:
 b=wide(r['source']).read_bytes();assert sha(b)==r['sha256Bytes'];p=REPO/r['target'];assert not p.exists();p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
hunk=json.loads((LANE/'sole-shared-helper-hunk.json').read_text(encoding='utf-8'));p=REPO/hunk['target'];raw=p.read_bytes();assert sha(raw.replace(b'\r\n',b'\n'))==hunk['baseSha256LF'];text=raw.decode('utf-8').replace('\r\n','\n');assert text.count(hunk['before'])==hunk['count'];text=text.replace(hunk['before'],hunk['after']);(OUT/'extract-upstream-home-partition.py.before').write_bytes(raw);p.write_text(text,encoding='utf-8',newline='\n');desired=sha(text.encode())
p=REPO/'desktop/upstream-sources.json';registry=json.loads(p.read_text(encoding='utf-8'));before=len(registry['sources']);index={r['path']:r for r in registry['sources']};new=[];merged=[]
for r in json.loads((LANE/'registry-merge-recipe.json').read_text(encoding='utf-8'))['rows']:
 path=r['path'];assert sha((REPO/path).read_bytes().replace(b'\r\n',b'\n'))==r['sha256LF']
 if path in index:
  old=index[path];assert old['sha256']==r['sha256LF']
  if r['feature']not in old['features']:old['features'].append(r['feature'])
  merged.append(path)
 else:
  assert r['proposedMode']!='reference';row=dict(path=path,sha256=r['sha256LF'],mode=r['proposedMode'],features=[r['feature']]);registry['sources'].append(row);index[path]=row;new.append(path)
p.write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
p=REPO/'desktop/build.gradle.kts';text=p.read_text(encoding='utf-8');assert 'val extractOriginalLiveNavigation by'not in text
snippet='''
val extractOriginalLiveNavigation by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-live-navigation.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/live-navigation").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-live-navigation.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "live-sub-navigation" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/live-navigation"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/live-navigation")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalLiveNavigation) }
'''
marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert text.count(marker)==1;p.write_text(text.replace(marker,snippet+'\n'+marker),encoding='utf-8',newline='\n')
report=dict(preparedManifestSHA256=sha(manifest),payloads=contract['payloads'],sharedHelperHunk=hunk,desiredSharedProducerSHA256LF=desired,newIdentities=new,featureMerges=merged,sourceCountBefore=before,sourceCountAfter=len(registry['sources']),rootMounted=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(payloads=len(contract['payloads']),sourceCount=len(registry['sources']),liveRootMounted=False)))
