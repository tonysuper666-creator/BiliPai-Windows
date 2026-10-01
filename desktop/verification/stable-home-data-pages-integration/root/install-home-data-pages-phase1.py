from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=HERE/'home-data-pages-phase1-install'
assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
lanes={
 'vm':(MAIN/'desktop/.local/stable-home-viewmodel-parity','ac33081aa67a40a3f807e20426de7f7fc12d1ea6d3128f50e3a0f951e9587a37'),
 'raw':(MAIN/'desktop/.local/stable-home-request-ports-parity','4824a6984eead54af5ac2c045cb9a21d600488d6be4ed3cbea2ad5e6eabdf5f3'),
 'owner':(MAIN/'desktop/.local/stable-home-root-retained-integration','be83c2c2215a88f879e63f98852746098f381ee0c35d909fecf345c612504e8a'),
 'partition':(MAIN/'desktop/.local/stable-home-partition-parity','d059904f107df7a650dd60816030828663cec950ae3250c23f89da81d5d28faf'),
 'bangumi':(MAIN/'desktop/.local/stable-home-bangumi-page-parity','d7e62ffd35be7b302f7952d3ca4668910f73cc88a5d734bb91a529ffaf7ddcac'),
 'live':(REPO/'desktop/.local/stable-home-live-list-parity','44b3f5508fc43b24bf9f3feb9a9258239321eecdce43e185b4ac4670c1a8aee5'),
 'global':(MAIN/'desktop/.local/stable-home-global-platform-parity','1c27e730135c171c5f73edac04fe432334a756747b300c15d6e524971d5b49d5'),
}
for name,(lane,pin)in lanes.items():
 raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin,name
 for r in json.loads(raw)['artifacts']:
  assert sha(wide(lane/r['path']).read_bytes())==r['sha256Bytes'],(name,r['path'])
registryPath=REPO/'desktop/upstream-sources.json';registry=json.loads(registryPath.read_text(encoding='utf-8'));initialCount=len(registry['sources']);index={r['path']:r for r in registry['sources']}
copied=[];patches=[];added=[];merged=[]
def payload(lane,source,target):
 p=REPO/target;assert not p.exists(),target;p.parent.mkdir(parents=True,exist_ok=True)
 b=wide(lane/source).read_bytes();p.write_bytes(b);copied.append(dict(target=target,sha256Bytes=sha(b)))
def patch(lane,name):
 p=lane/name
 subprocess.run(['git','apply','--check',str(p)],cwd=REPO,check=True)
 subprocess.run(['git','apply',str(p)],cwd=REPO,check=True)
 patches.append(dict(path=str(p),sha256Bytes=sha(p.read_bytes())))
def merge(row):
 p=row['path'];h=row['sha256'];assert sha((REPO/p).read_bytes().replace(b'\r\n',b'\n'))==h,p
 if p in index:
  current=index[p];assert current['sha256']==h
  for feature in row.get('features',[]):
   if feature not in current['features']:current['features'].append(feature)
  merged.append(p)
 else:
  current={k:row[k]for k in ('path','sha256','mode','features')};registry['sources'].append(current);index[p]=current;added.append(p)
vm=lanes['vm'][0];raw=lanes['raw'][0];owner=lanes['owner'][0]
phase=json.loads((owner/'phase-install-plan.json').read_text(encoding='utf-8'))['phase1']
for source in phase['VM287Include']:
 target='desktop/tools/'+Path(source).name if source.startswith('prepared/tools/')else'desktop/src/main/kotlin/'+source.removeprefix('prepared/manual/')
 payload(vm,source,target)
for r in json.loads((raw/'install-whitelist.json').read_text(encoding='utf-8'))['newSourcePayload']:payload(raw,r['source'],r['target'])
for name in ['shared-http-owner-admission.patch','home-preview-subject.patch']:patch(raw,name)
patch(vm,'backend-contract/follow-getter-and-planner-retirement-delta/sole-producer-two-getters.patch')
for r in phase['thisManualSources']:payload(owner,r['source'],r['target'])
for r in phase['thisPatches']:patch(owner,r['path'])
for lane in [vm,raw]:
 for row in json.loads((lane/'source-inventory.json').read_text(encoding='utf-8'))['records']:merge(row)
delta=json.loads((owner/'upstream-source-delta.json').read_text(encoding='utf-8'))
for row in delta['appendSources']:merge(row)
for row in delta['mergeExistingFeatures']:merge(dict(path=row['path'],sha256=row['sha256'],features=row['appendFeatures']))
for name in ['partition','bangumi']:
 lane=lanes[name][0];whitelist=json.loads((lane/'install-whitelist.json').read_text(encoding='utf-8'))
 for r in whitelist['newSources']:payload(lane,r['prepared'],r['destination'])
 for r in whitelist['localPatches']:patch(lane,r['patch'])
 recipe=json.loads((lane/'registry-recipe.json').read_text(encoding='utf-8'))
 for row in recipe['newRows']:merge(row)
 for row in recipe['mergeOnly']:merge(dict(path=row['path'],sha256=index[row['path']]['sha256'],features=row['addFeatures']))
lane=lanes['live'][0]
for r in json.loads((lane/'install-contract.json').read_text(encoding='utf-8'))['payloads']:
 source=Path(r['source']);assert sha(source.read_bytes())==r['sha256Bytes'];payload(lane,source,r['target'])
for row in json.loads((lane/'registry-merge-recipe.json').read_text(encoding='utf-8'))['records']:merge(row)
for name in ['DesktopHomeWindowGlobals.kt','DesktopHomeErrorAnimation.kt']:
 payload(lanes['global'][0],'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name,'desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name)
registryPath.write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
build=REPO/'desktop/build.gradle.kts';s=build.read_text(encoding='utf-8')
snippet=(raw/'gradle-tasks.snippet.kts').read_text(encoding='utf-8')
snippet+= '\n'+(lanes['live'][0]/'gradle-tasks.snippet.kts').read_text(encoding='utf-8')
for name in ['partition','bangumi']:
 w=json.loads((lanes[name][0]/'install-whitelist.json').read_text(encoding='utf-8'));task=w['taskName'];directory=w['sourceDir'];feature='home-partition'if name=='partition'else'home-bangumi-page';tool=w['newSources'][0]['destination'].removeprefix('desktop/')
 snippet+='''
val __TASK__ by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "__TOOL__",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("__DIR__").get().asFile.absolutePath)
    inputs.files("__TOOL__", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "__FEATURE__" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("__DIR__"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("__DIR__")) }
tasks.named("compileKotlin") { dependsOn(__TASK__) }
'''.replace('__TASK__',task).replace('__TOOL__',tool).replace('__DIR__',directory).replace('__FEATURE__',feature)
marker='val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {';assert s.count(marker)==1
assert 'val extractOriginalHomeViewModel by'in snippet and 'val extractOriginalHomeViewModel by'not in s
build.write_text(s.replace(marker,snippet+'\n'+marker),encoding='utf-8',newline='\n')
report=dict(stage='SOURCE_CLOSURE_ONLY_NO_FACTORY_MOUNT_OR_PLANNER_RETIREMENT',payloads=copied,patches=patches,
 originalSourceIdentitiesBefore=initialCount,originalSourceIdentitiesAfter=len(registry['sources']),newOriginalIdentities=added,featureMerges=merged,
 oldTodayWatchRepositoryPreserved=True,runtimeRetirementApplied=False,homeRetainerInstalled=False,
 originalSubscriptionPayloadInstalled=False,actualFullRootMounted=False,windowsExeChanged=False,
 preparedManifests={k:v[1]for k,v in lanes.items()})
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(payloads=len(copied),patches=len(patches),newIdentities=len(added),sourceCount=len(registry['sources']),rootMounted=False)))
