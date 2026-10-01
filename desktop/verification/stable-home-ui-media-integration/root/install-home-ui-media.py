from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
UI=MAIN/'desktop/.local/stable-home-page-parity';MEDIA=MAIN/'desktop/.local/stable-home-platform-media-parity'
OUT=HERE/'home-ui-media-install';assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,h):
    b=read(p);assert sha(b)==h,p;return b
def lf(b):return b.replace(b'\r\n',b'\n')
for lane,expected,count in ((UI,'0b38723109616c73096d6e81fcf932a16f89164aa14bcbdb3db2fe5461626df8',524),
    (MEDIA,'9b9ced6df9540109469a25da9917407126e97bdce5656688b04627f2f05fe966',74)):
    m=json.loads(pin(lane/'frozen-handoff.json',expected));assert len(m['artifacts'])==count
    for r in m['artifacts']:pin(lane/r['path'],r['sha256Bytes'])
copies=[];unchanged=[]
whitelist=json.loads(read(UI/'install-whitelist.json'))['files'];assert len(whitelist)==8
for r in whitelist:
    b=pin(UI/r['prepared'],r['sha256Bytes']);target=REPO/r['install']
    if wide(target).exists():assert read(target)==b,r['install'];unchanged.append(r['install'])
    else:copies.append((target,b))
for relative in ('player/MpvSoftwareFrames.kt','ui/DesktopHomeOwnedMedia.kt','ui/DesktopHomeWallpaperImages.kt','ui/DesktopHomeWindowPlatform.kt'):
    target=REPO/'desktop/src/main/kotlin/com/bilipai/desktop'/relative;assert not wide(target).exists()
    copies.append((target,read(MEDIA/'prepared/desktop/src/main/kotlin/com/bilipai/desktop'/relative)))
patches=[]
for r in json.loads(read(UI/'patch-baselines.json')):
    assert sha(lf(read(REPO/r['path'])))==r['baseSha256LF'];patches.append((UI/'home-full-card-source-owner.patch',r))
for r in json.loads(read(MEDIA/'patch-baselines.json')):
    assert sha(lf(read(REPO/r['path'])))==r['baseSha256LF'];patches.append((Path(r['localPatch']),r))
for p,r in patches:subprocess.run(['git','apply','--check',str(p)],cwd=REPO,check=True)
registry=json.loads(read(REPO/'desktop/upstream-sources.json'));assert len(registry['sources'])==773
inventory=json.loads(read(UI/'source-inventory.json'));assert len(inventory['records'])==69
index={r['path']:r for r in registry['sources']};added=[];merged=[]
for r in inventory['records']:
    assert sha(lf(read(REPO/r['path'])))==r['sha256'],r['path']
    if r['path'] in index:
        old=index[r['path']];assert old['sha256']==r['sha256'];old['features']=list(dict.fromkeys(old['features']+r['features']));merged.append(r['path'])
    else:
        entry={k:r[k] for k in ('path','sha256','mode','features')};registry['sources'].append(entry);index[r['path']]=entry;added.append(r['path'])
assert len(added)==58 and len(merged)==11 and len(registry['sources'])==831
build=read(REPO/'desktop/build.gradle.kts');text=lf(build).decode('utf-8');original=text
anchor='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert text.count(anchor)==1
task='''val extractOriginalHomePage by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-page.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-page").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-page.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-page"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalHomePage) }

'''
text=text.replace(anchor,task+anchor,1)
old='dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories, extractUpstreamHomeCards)';assert text.count(old)==1
text=text.replace(old,old[:-1]+', extractOriginalHomePage)',1)
old='inputs.files("tools/extract-upstream-home-full-card.py", "tools/extract-upstream-media.py",';assert text.count(old)==1
text=text.replace(old,'inputs.files("tools/extract-upstream-home-full-card.py", "tools/extract-upstream-home-page.py", "tools/extract-upstream-media.py",',1)
old='    kotlin.srcDir(layout.buildDirectory.dir("generated/home-full-card/generated"))';assert text.count(old)==1
text=text.replace(old,old+'\n    kotlin.srcDir(layout.buildDirectory.dir("generated/home-page"))',1)
# The full-card producer imports the whole Home assembler; invalidate it for every assembler source change.
old='    outputs.dir(layout.buildDirectory.dir("generated/home-full-card"))';assert text.count(old)==1
text=text.replace(old,'    inputs.files(sources.filter { "home-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }\n        .map { File(repositoryRoot, it["path"].toString()) })\n'+old,1)
OUT.mkdir();(OUT/'baseline-build.gradle.kts').write_bytes(build);(OUT/'baseline-registry.json').write_bytes(read(REPO/'desktop/upstream-sources.json'))
for p,r in patches:
    target=OUT/'baselines'/r['path'];wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(read(REPO/r['path']))
for target,b in copies:wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(b)
for p,r in patches:
    subprocess.run(['git','apply',str(p)],cwd=REPO,check=True)
    desired=r.get('desiredSha256LF',r.get('candidateSha256LF'));assert sha(lf(read(REPO/r['path'])))==desired,r['path']
(REPO/'desktop/build.gradle.kts').write_text(text,encoding='utf-8',newline='\n')
(REPO/'desktop/upstream-sources.json').write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
report=dict(applied=True,uiFrozenSHA256=sha(read(UI/'frozen-handoff.json')),mediaFrozenSHA256=sha(read(MEDIA/'frozen-handoff.json')),
    copiedFiles=[str(p.relative_to(REPO)).replace('\\','/') for p,b in copies],unchangedPayloadSkipped=unchanged,
    soleExistingProducerAndMpvHunksOnly=True,registryAdded=added,registryMerged=merged,sourceRegistryCount=831,
    originalHomeTaskRegistered=True,noNativeOrRuntimeExecution=True,homeRootMounted=False,todayWatchRuntimeOrPlannerChanged=False,
    buildBeforeLF=sha(original.encode()),buildAfterLF=sha(text.encode()))
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(applied=True,copiedFiles=len(copies),unchangedPayloadSkipped=unchanged,sourceRegistryCount=831,rootMounted=False)))
