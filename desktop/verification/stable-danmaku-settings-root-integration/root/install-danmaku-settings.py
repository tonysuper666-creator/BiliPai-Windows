from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-danmaku-settings-panel-parity'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.decode('utf-8').replace('\r\n','\n').encode()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
def put(p,b):
    p=wide(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
manifest=pin(LANE/'frozen-handoff.json','51acaae885d7cf690f2b17aaf4ea856b02aae81e8b25c0086a5ffb01dddeb6a8')
m=json.loads(manifest); by_artifact={r['path']:r for r in m['artifacts']}
for r in m['artifacts']:
    b=pin(LANE/r['path'],r['sha256Bytes']);assert len(b)==r['size']
contract=json.loads(pin(LANE/'install-contract.json','51e77f752ba52fbc6aa38be71f35986135b64e6fee0c258806c0ce085667d071'))
pending={}; baselines={}
for r in [contract['producer']]+contract['manualSourcePayloads']:
    target=wide(r['path']).relative_to(wide(LANE/'prepared')).as_posix()
    b=read(r['path']);assert sha(lf(b))==r['sha256LF'];assert not wide(REPO/target).exists()
    pending[target]=b
target='desktop/upstream-sources.json';b=read(REPO/target);baselines[target]=b;registry=json.loads(b)
assert len(registry['sources'])==765 and len(registry['resources'])==213
by={r['path']:r for r in registry['sources']};changes=[]
delta=json.loads(read(LANE/'registry-delta.json'))
for incoming in delta['sources']:
    path=incoming['path'];assert sha(lf(read(REPO/path)))==incoming['sha256']
    if path in by:
        row=by[path];assert row['sha256']==incoming['sha256'] and row['mode']==incoming['proposedMode'];operation='feature merge'
    else:
        row=dict(path=path,sha256=incoming['sha256'],mode=incoming['proposedMode'],features=[])
        registry['sources'].append(row);by[path]=row;operation='append identity'
    for feature in incoming['features']:
        if feature not in row['features']:row['features'].append(feature)
    changes.append(dict(path=path,operation=operation,mode=row['mode']))
assert len(registry['sources'])==770
pending[target]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
target='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
b=read(REPO/target);baselines[target]=b
fragment=read(contract['opsFragment']['path']);assert sha(lf(fragment))==contract['opsFragment']['sha256LF']
marker=contract['opsFragment']['anchor'].encode();text=lf(b)
assert text.count(marker)==1 and b'private val originalDanmakuCloudRules' not in text
pending[target]=text.replace(marker,lf(fragment)+b'\n'+marker,1)
target='desktop/build.gradle.kts';b=read(REPO/target);baselines[target]=b;text=lf(b).decode()
assert 'val extractOriginalDanmakuSettings by' not in text
fragment='''val extractOriginalDanmakuSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-danmaku-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-danmaku-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-danmaku-settings.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-danmaku-settings-panel" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-danmaku-settings"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalDanmakuSettings) }

'''
marker='val extractOriginalDanmakuListMenu by tasks.registering(Exec::class) {'
assert text.count(marker)==1;text=text.replace(marker,fragment+marker,1)
marker='    kotlin.srcDir(layout.buildDirectory.dir("generated/original-danmaku-list-menu/com"))'
assert text.count(marker)==1;text=text.replace(marker,marker+'\n    kotlin.srcDir(layout.buildDirectory.dir("generated/original-danmaku-settings/com"))',1)
pending[target]=text.encode()
patch=contract['sourceDelta']['exactEightLinePatch'];relative=wide(patch).relative_to(wide(LANE)).as_posix()
pin(patch,by_artifact[relative]['sha256Bytes'])
target='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt'
baselines[target]=read(REPO/target)
subprocess.run(['git','apply','-p0','--check','--ignore-space-change','--ignore-whitespace',patch],cwd=REPO,check=True)
assert not wide(HERE/'danmaku-settings-install.json').exists()
for p,b in baselines.items():put(HERE/'danmaku-settings-install-baseline'/p,b)
subprocess.run(['git','apply','-p0','--ignore-space-change','--ignore-whitespace',patch],cwd=REPO,check=True)
assert sha(lf(read(REPO/target)))==contract['sourceDelta']['sha256LF']
for p,b in pending.items():put(REPO/p,b)
report=dict(installed=True,frozenManifestSHA256=sha(manifest),frozenArtifactsVerified=len(m['artifacts']),
    sourceCount=len(registry['sources']),resourceCount=len(registry['resources']),changes=changes,
    installedFiles=[dict(path=p,sha256Bytes=sha(b)) for p,b in pending.items()],eightLineNormalizationPatchOnly=True,
    opsWholeFileOverwritten=False,strictEditorAnchorPreserved=True,rootConsumerInstalled=False,wholeClassesAccepted=False,
    nativeRendererFullParityAccepted=False,desktopExeReplaced=False)
put(HERE/'danmaku-settings-install.json',(json.dumps(report,indent=2)+'\n').encode());print(json.dumps(dict(installed=True,sources=770,artifacts=len(m['artifacts']))))
