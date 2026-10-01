from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=MAIN/'desktop/.local/stable-home-subscription-page-parity';OUT=HERE/'home-subscription-install'
assert not OUT.exists() or not any(OUT.iterdir());OUT.mkdir(exist_ok=True);sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
raw=(LANE/'frozen-handoff.json').read_bytes();assert sha(raw)=='c019ddc3538a5f96f41f35f0ffa617cfc3069983101a59e14efa000611e6137a'
for r in json.loads(raw)['artifacts']:assert sha(wide(LANE/r['path']).read_bytes())==r['sha256Bytes']
payloads=[];hunks=[]
for source in ['prepared/desktop/tools/extract-upstream-subscription-page.py',
 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopSubscriptionWriteAdmission.kt',
 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSubscriptionPageBindings.kt',
 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSubscriptionPredictiveBack.kt']:
 target=source.removeprefix('prepared/');p=REPO/target;b=wide(LANE/source).read_bytes();assert not p.exists() or p.read_bytes()==b;p.write_bytes(b);payloads.append(dict(path=target,sha256Bytes=sha(b)))
for r in json.loads((LANE/'existing-source-hunks.json').read_text(encoding='utf-8')):
 p=REPO/r['path'];b=p.read_bytes();assert sha(b.replace(b'\r\n',b'\n'))==r['baseSha256LF'];s=b.decode('utf-8').replace('\r\n','\n')
 for change in r['changes']:
  # Source scanner records repeated publication statements in original left-to-right order.
  # Explicit occurrences are simultaneous replacements. Baseline AND full desired digest remain
  # mandatory, so repeated short fragments cannot silently patch another final source.
  count=change.get('occurrences',1);assert s.count(change['before'])>=count,(r['path'],change['before'])
  s=s.replace(change['before'],change['after'],count)
 assert sha(s.encode())==r['candidateSha256LF'];p.write_text(s,encoding='utf-8',newline='\n');hunks.append(dict(path=r['path'],changes=len(r['changes']),sha256LF=r['candidateSha256LF']))
p=REPO/'desktop/upstream-sources.json';registry=json.loads(p.read_text(encoding='utf-8'));row=json.loads((LANE/'registry-recipe.json').read_text(encoding='utf-8'))['source'];assert not any(r['path']==row['path']for r in registry['sources'])
assert sha((REPO/row['path']).read_bytes().replace(b'\r\n',b'\n'))==row['sha256'];registry['sources'].append(row)
p.write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
p=REPO/'desktop/build.gradle.kts';s=p.read_text(encoding='utf-8');marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert s.count(marker)==1
snippet='''val extractOriginalSubscriptionPage by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-subscription-page.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-subscription-page").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-subscription-page.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-subscription-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-subscription-page"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/home-subscription-page")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSubscriptionPage) }

'''
assert 'val extractOriginalSubscriptionPage by'not in s;p.write_text(s.replace(marker,snippet+marker),encoding='utf-8',newline='\n')
report=dict(payloads=payloads,existingSourcesNarrowHunks=hunks,newOriginalIdentities=1,sourceCount=len(registry['sources']),sameRuntimeSubscriptionParserIoOwner=True,originalRootMounted=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(payloads=len(payloads),existingSourceFamilies=len(hunks),sourceCount=len(registry['sources']))))
