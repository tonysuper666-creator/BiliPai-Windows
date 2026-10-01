from pathlib import Path
import hashlib,json,subprocess,os,zipfile
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
PY=Path('C:/Users/TONYS/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe')
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def js(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2))

receipt=json.loads(read(LANE/'generated/source-receipt.json'));compiled=json.loads(read(LANE/'compile-03/compile-result.json'));proof=json.loads(read(LANE/'proof-05/proof-result.json'))
assert receipt['selectedSettingsExactInversePass'] and proof['assertions']==21 and proof['pointerPairs']==2
for row in compiled['sourcePins']:assert sha(row['path'])==row['sha256Bytes']
manifest=json.loads(read(REPO/'desktop/upstream-sources.json'));settings='app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'
newRows=[]
for record in receipt['sources']:
 if record['source']==settings:continue
 assert not any(row['path']==record['source'] for row in manifest['sources'])
 newRows.append({'path':record['source'],'sha256':record['originalSha256LF'],'features':['stable-download-list-original'],'mode':'direct' if record['source'].endswith('DownloadTaskPresentationPolicy.kt') else 'platform-adapter-reference'})
merge={'fixedCommit':receipt['fixedCommit'],'newRows':newRows,'existingFeatureMerges':[{'path':settings,'sha256':'680005e1f25e8a365d30f0c78c988765e7d2140008c57d9bf31d859c5b835b1c','appendFeature':'stable-download-list-original','newSettingsManagerObject':False,'newPersistentStore':False}]}
js(LANE/'registry-merge-recipe.json',merge)
replay=LANE/'replay-source-repo';registry=json.loads(json.dumps(manifest));registry['sources']+=newRows
for row in registry['sources']:
 if row['path']==settings:row['features']=sorted(set(row['features']+['stable-download-list-original']))
write(replay/'desktop/upstream-sources.json',json.dumps(registry,indent=2))
for p in [row['source'] for row in receipt['sources']]+['desktop/tools/sync-upstream.py','desktop/tools/extract-upstream-media.py','desktop/tools/extract-appearance-platform.py']:
 write(replay/p,read(REPO/p))
gitdir=subprocess.check_output(['git','rev-parse','--absolute-git-dir'],cwd=REPO,text=True).strip()
env=dict(os.environ,GIT_DIR=gitdir,GIT_WORK_TREE=str(replay))
r=subprocess.run([str(PY),str(LANE/'prepared/desktop/tools/extract-upstream-download-list.py'),'--repo',str(replay),'--output',str(LANE/'replay-production')],env=env,capture_output=True,text=True,encoding='utf-8',timeout=20)
write(LANE/'replay-production.log',r.stdout+r.stderr);assert r.returncode==0,r.stderr
assert not (LANE/'replay-production/com/android/purebilibili/feature/download/DownloadTaskPresentationPolicy.kt').exists()
for row in receipt['sources']:
 if row['source'].endswith('DownloadTaskPresentationPolicy.kt'):continue
 assert read(LANE/'generated'/row['output'])==read(LANE/'replay-production'/row['output'])
js(LANE/'production-byte-equality.json',{'productionGeneratedSources':3,'directPolicyEmittedTwice':False,'allThreeProductionFilesEqualStandaloneCompiledInputs':True,'registryFixedIdentityAndGitBlobGatesPassed':True,'sameSourceBodiesUnchangedAfterFinalCompile':True})

rows=[];targets=[]
def hunk(target,before,after):
 source=read(REPO/target);assert source.count(before)==1,(target,before)
 if not any(t['path']==target for t in targets):
  write(LANE/'shared-bases'/target,source);targets.append({'path':target,'baseSha256LF':digest(source)})
 rows.append({'target':target,'before':before,'after':after,'count':1})
target='desktop/src/main/kotlin/com/bilipai/desktop/ui/DownloadScreens.kt'
hunk(target,'private fun desktopDownloadNetworkAvailable(): Boolean = runCatching {','internal fun desktopDownloadNetworkAvailable(): Boolean = runCatching {')
target='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
before='                                        if (repository.sessionEpoch != expectedEpoch) throw CancellationException("下载账号已切换")'
after=before+'''\n                                        val downloadDestination = desktopOriginalDownloadDestination(globalPluginContext) {
                                            !isClosing() && !activatingUpdate && repository.sessionEpoch == expectedEpoch &&
                                                downloadJob?.isActive == true && playback.state.value.details?.bvid == info.bvid &&
                                                playback.state.value.details?.pages?.getOrNull(playback.state.value.currentPart)?.cid == part.cid
                                        }'''
hunk(target,before,after)
before='authorizationReceipt = source.authorizationReceipt), metadata = DownloadMetadata(\n                                            aid = info.aid, bvid = info.bvid, cid = part.cid'
after='authorizationReceipt = source.authorizationReceipt), destination = downloadDestination, metadata = DownloadMetadata(\n                                            aid = info.aid, bvid = info.bvid, cid = part.cid'
hunk(target,before,after)
for t in targets:
 base=read(LANE/'shared-bases'/t['path']);candidate=base
 local=[row for row in rows if row['target']==t['path']]
 for row in local:assert candidate.count(row['before'])==1;candidate=candidate.replace(row['before'],row['after'])
 write(LANE/'review-only-shared-candidates'/t['path'],candidate);recovered=candidate
 for row in reversed(local):assert recovered.count(row['after'])==1;recovered=recovered.replace(row['after'],row['before'])
 assert recovered==base;t.update({'candidateSha256LF':digest(candidate),'exactReversePass':True})
js(LANE/'shared-local-hunks.json',{'targets':targets,'rows':rows,'wholeSharedCandidateInstall':False,'keepRootPlayback45Admission':True})

# Static ABI audit uses the existing small class reader; actual97 paths remain immutable.
helper=REPO/'desktop/.local/stable-home-live-list-parity/audit_abi.py';namespace={'__file__':str(LANE/'audit.py')};exec(read(helper).split('aud=json.loads')[0],namespace);parse=namespace['parse']
cp=json.loads(read(LANE/'input-audit.json'))['verifiedDependencyPins'];actualMethods={};actualClasses=set()
for row in cp:
 assert sha(row['path'])==row['sha256Bytes']
 with zipfile.ZipFile(safe(row['path'])) as z:
  for name in z.namelist():
   if not name.endswith('.class'):continue
   actualClasses.add(name)
   if name.endswith('Kt.class') and name.startswith(('com/android/purebilibili/','com/bilipai/desktop/')):
    for access,method,desc in parse(z.read(name)):
     if access&9==9:actualMethods.setdefault((name.rsplit('/',1)[0],method,desc),[]).append(name)
invalid=[];overlap=[];methods=0;classes=0
with zipfile.ZipFile(safe(compiled['jar'])) as z:
 for name in z.namelist():
  if not name.endswith('.class'):continue
  classes+=1;assert name not in actualClasses
  for access,method,desc in parse(z.read(name)):
   methods+=1
   if any(c in method for c in '.;/[') or ('<' in method and method not in ['<init>','<clinit>']):invalid.append([name,method])
   if name.endswith('Kt.class') and access&9==9 and (name.rsplit('/',1)[0],method,desc) in actualMethods:overlap.append([name,method,desc])
assert not invalid and not overlap
js(LANE/'abi-audit.json',{'candidateClasses':classes,'candidateMethods':methods,'newFqnOverlap':0,'newPublicTopMethodOverlap':0,'invalidJvmMethodNames':invalid,'actualSnapshot':44,'existingReaderPath':str(helper),'existingReaderSha256Bytes':sha(helper),'classloadWithoutInitialization':proof['loadedCandidateClasses']})
payloads=[{'source':str(p),'target':p.relative_to(LANE/'prepared').as_posix(),'sha256Bytes':sha(p),'sha256LF':digest(read(p))} for p in sorted((LANE/'prepared').rglob('*')) if p.is_file() and p.suffix in ['.py','.kt']]
js(LANE/'install-contract.json',{'payloads':payloads,'sharedHunkFile':'shared-local-hunks.json','sharedHunks':3,'sharedTargets':2,'generatedSources':3,'soleDirectPolicySources':1,'manualSources':2,'newRegistryRows':3,'sameExistingSettingsIdentityFeatureMerge':1,'rootFactoryRequired':True,'offlinePlayerOriginalUiStillPending':True,'noNativeRebuildNeededForThisSourceOnlyUi':True,'noJarInstallation':True})
print(json.dumps({'payloads':len(payloads),'generated':3,'direct':1,'sharedHunks':3,'classes':classes,'methods':methods,'abiOverlap':0,'productionReplay':'PASS'},indent=2))
