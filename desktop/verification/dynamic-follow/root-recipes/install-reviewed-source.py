from pathlib import Path
import hashlib,importlib.util,json,subprocess
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
LANE=REPO/'desktop/.local/dynamic-follow-observer-parity'
def ext(p):
 value=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(value if value.startswith(prefix)else prefix+value)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(ext(p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()
def read(p):return json.loads(ext(p).read_bytes())
def save(p,v):ext(p).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8',newline='\n')
def write(p,s):ext(p).write_text(s,encoding='utf-8',newline='\n')
manifest=LANE/'frozen-handoff-v2.json'
assert sha(manifest)=='fe16610024638f562846e409c549f72542cfb79abed50bf678a2998dbf3d0635'
handoff=read(manifest);assert len(handoff['files'])==237
for row in handoff['files']:
 p=(LANE/row['path']).resolve();assert p.is_relative_to(LANE.resolve())
 assert sha(p)==row['sha256Bytes']and ext(p).stat().st_size==row['bytes'],row['path']
checklist=LANE/'root-integration-checklist.json';assert sha(checklist)=='8bfc1bad9a4f5e522472c5242be2197e54cf68f02b6fd62a9a17e37e52c5d3cc'
assert sha(LANE/'review-final-v2.json')=='4ef0715c9441fb577db43013e44783c722a2660ec0ec316e21bc61f830f8541f'
baseline=read(LANE/'baseline-pins.json')
for row in baseline:assert lfsha(REPO/row['path'])==row['sha256Lf'],row['path']
prepared=read(checklist)['producerInstall']
for row in prepared:
 assert sha(LANE/row['source'])==row['sha256Bytes']
 old=LANE/'baseline'/row['target']
 if ext(old).exists():assert lfsha(old)==lfsha(REPO/row['target']),row['target']
sourceRows=[row for row in handoff['files']if row['path'].startswith('candidate/desktop/src/main/kotlin/')]
assert len(sourceRows)==7
# Prepare the full registry merge without replacing unrelated modes, features or rows.
spec=importlib.util.spec_from_file_location('reviewed_follow_inventory',LANE/'prepared-tools/desktop/tools/extract-upstream-dynamic-follow.py')
producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
newRows=producer.inventory(REPO);assert len(newRows)==3
registry=REPO/'desktop/upstream-sources.json';before=read(registry);after=json.loads(json.dumps(before));identities={r['path']:r for r in after['sources']}
assert len(identities)==len(after['sources'])
newIdentities=[]
for row in newRows:
 if row['path']in identities:
  current=identities[row['path']];assert current['sha256']==row['sha256'],row['path']
  current['features']=sorted(set(current.get('features',[]))|set(row['features']))
 else:
  after['sources'].append(row);identities[row['path']]=row;newIdentities.append(row['path'])
assert len(after['sources'])==len(before['sources'])+len(newIdentities)
for old in before['sources']:
 current=identities[old['path']]
 assert {k:v for k,v in current.items()if k!='features'}=={k:v for k,v in old.items()if k!='features'}
 assert set(old.get('features',[])).issubset(current.get('features',[]))
# Existing Root Compose owner effect. No additional list owner, scope or IO dispatcher.
shell=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
shellBefore=ext(shell).read_text(encoding='utf-8').replace('\r\n','\n')
anchor='    DisposableEffect(dynamicCardSession, dynamicCardRegistry) {\n        onDispose { dynamicCardSession.close(); dynamicCardRegistry.close() }\n    }\n'
assert shellBefore.count(anchor)==1
effect='    LaunchedEffect(dynamicCardSession, dynamicCardRegistry) {\n        dynamicCardSession.observeFollowStateChanges(dynamicCardRegistry)\n    }\n'
assert effect not in shellBefore
shellAfter=shellBefore.replace(anchor,anchor+effect)
gradle=REPO/'desktop/build.gradle.kts';gradleBefore=ext(gradle).read_text(encoding='utf-8').replace('\r\n','\n')
task='''val extractUpstreamDynamicFollow by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicSettings, extractUpstreamDynamicTabs)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-follow.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-follow").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-follow.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-api.py")
    inputs.files(sources.filter { "dynamic-follow-observer-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-follow"))
}

'''
taskAnchor='val extractUpstreamCrashPrompt by tasks.registering(Exec::class) {'
assert gradleBefore.count(taskAnchor)==1 and 'val extractUpstreamDynamicFollow'not in gradleBefore
gradleAfter=gradleBefore.replace(taskAnchor,task+taskAnchor)
sourceAnchor='    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-editor"))\n'
assert gradleAfter.count(sourceAnchor)==1
gradleAfter=gradleAfter.replace(sourceAnchor,sourceAnchor+'    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-follow"))\n')
dependencyAnchor='tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicEditor, verifyUpstreamDynamicEditorProtocol) }\n'
assert gradleAfter.count(dependencyAnchor)==1
gradleAfter=gradleAfter.replace(dependencyAnchor,dependencyAnchor+'\ntasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicFollow) }\n')
beforePins=[dict(path=p.relative_to(REPO).as_posix(),sha256Bytes=sha(p),sha256Lf=lfsha(p))for p in [shell,gradle,registry]]
for row in sourceRows:
 relative=row['path'].removeprefix('candidate/');target=REPO/relative
 if ext(target).exists():assert any(r['path']==relative for r in baseline),relative
 else:assert target.name=='DesktopFollowStateEvents.kt'
 ext(target).write_bytes(ext(LANE/row['path']).read_bytes())
for row in prepared:ext(REPO/row['target']).write_bytes(ext(LANE/row['source']).read_bytes())
write(shell,shellAfter);write(gradle,gradleAfter);save(registry,after)
community=ext(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt').read_text(encoding='utf-8')
assert 'onAllTimelineChanged={rows->if(cardRegistry.isCurrentAll(model))cache.saveTimeline(rows)}'in community
assert 'cardRegistry.register(users)'in community and '.also(cardRegistry::register)'in community
record=dict(MainSourceInstalled=True,baseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
 verifiedRawFiles=237,frozenHandoffSha256Bytes=sha(manifest),reviewFinalSha256Bytes=sha(LANE/'review-final-v2.json'),
 beforeRootPins=beforePins,preparedKotlinSources=7,preparedProducers=3,originalInventoryEntries=3,newSourceIdentities=newIdentities,
 sourceCountBefore=len(before['sources']),sourceCountAfter=len(after['sources']),originalModesFeaturesPreserved=True,
 rootEpochEffectInstalled=True,communityCurrentAllSoleCacheSeamsPreserved=True,MainCompilationPending=True,
 realAccountOrPackageAccepted=False,newDesktopDeployment=False)
save(HERE/'source-installation.json',record);print(json.dumps(record,ensure_ascii=False))
