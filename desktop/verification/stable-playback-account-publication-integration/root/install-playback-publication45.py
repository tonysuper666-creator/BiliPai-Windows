from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
BACKEND=MAIN/'desktop/.local/stable-playback-account-protocol-parity';FINAL=MAIN/'desktop/.local/stable-playback-final-publication-parity'
OUT=HERE/'playback-publication-install45';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def frozen(lane,pin,count):
 raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin;manifest=json.loads(raw);assert len(manifest['artifacts'])==count
 for r in manifest['artifacts']:assert sha(wide(lane/r['path']).read_bytes())==r['sha256Bytes'],r['path']
 return manifest
backend=frozen(BACKEND,'b990563ad4ec9cf4eca8ea495377fe0496073209684df8d8e922cbe8a08322a3',84)
final=frozen(FINAL,'3c1f394d055a060a0f8e35a5e51f0f46da3d71f6f2ab6320080aeb306ab3bc5d',428)
assert (MAIN/'desktop/.local/stable-product-snapshot-44/manifest.json').exists(),'Freeze actual44 first'
pending={};receipts=[]
def new(relative,data):
 target=REPO/relative;assert not target.exists()and target not in pending,relative;pending[target]=data
def apply(rows,cohort,semantic=()):
 for r in rows:
  if r.get('producerRequired'):
   assert r['path']=='app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt'
   assert sha((REPO/r['path']).read_bytes().replace(b'\r\n',b'\n'))==r['baseLF'];continue
  target=REPO/r['path'];raw=pending.get(target,target.read_bytes());text=raw.decode().replace('\r\n','\n');beforeSha=sha(text.encode())
  if r['path']not in semantic:assert beforeSha==r['baseLF'],(cohort,r['path'],'base')
  for h in r['hunks']:
   expected=h.get('occurrences',1);assert text.count(h['before'])==expected,(cohort,r['path'],expected,text.count(h['before']))
   text=text.replace(h['before'],h['after'])
  afterSha=sha(text.encode())
  if r['path']not in semantic:assert afterSha==r['candidateLF'],(cohort,r['path'],'candidate')
  pending[target]=text.encode();receipts.append(dict(cohort=cohort,path=r['path'],hunks=r['hunks'],actualBeforeSha256LF=beforeSha,actualAfterSha256LF=afterSha,semanticExactHunkMerge=r['path']in semantic))
auth='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopPlaybackAuthorization.kt'
new(auth,wide(BACKEND/'prepared'/auth).read_bytes())
apply(json.loads((BACKEND/'local-hunks.json').read_bytes()),'backend84')
recipe=json.loads((FINAL/'install-recipe.json').read_bytes())
for r in recipe['newFiles']:
 data=wide(FINAL/r['source']).read_bytes();assert sha(data)==r['sha256Bytes'];new(r['path'],data)
apply(json.loads((FINAL/recipe['exactSequentialHunks']).read_bytes())['changes'],'final428',('desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',))
apply(json.loads((FINAL/recipe['producerHunks']).read_bytes()),'castProducer')
apply(json.loads((FINAL/recipe['buildRegistrationProposal']).read_bytes()),'downloadBuild',('desktop/build.gradle.kts',))
apply(json.loads((FINAL/recipe['castForkRecordOnlyUpdate']).read_bytes()),'castProvenance')
apply(json.loads((FINAL/recipe['syntheticCallerHunks']).read_bytes()),'syntheticCaller')
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(reg.read_bytes());assert len(registry['sources'])==931
delta=json.loads((FINAL/recipe['sameIdentityRegistryMerge']).read_bytes());assert delta['newOriginalIdentities']==0
for row in delta['sources']:
 existing=next(r for r in registry['sources']if r['path']==row['path']);assert existing['mode']==delta['replaceExistingMode'];assert existing['sha256']==row['sha256'];existing['mode']=row['mode']
 for feature in row['features']:
  if feature not in existing['features']:existing['features'].append(feature)
pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
# Exact snippets preserve all unrelated Root image/Profile/window lifetime changes.
shell=pending[REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'].decode()
for marker in ['val applicationImages = LocalDesktopApplicationImageLoader.current','homeRootRef.getAndSet(null)?.closeAndJoin()','withContext(Dispatchers.IO) { applicationImages.close() }']:
 assert shell.count(marker)==(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf-8').count(marker),marker
OUT.mkdir()
for target,data in pending.items():
 if target.exists():
  baseline=wide(OUT/'baseline'/target.relative_to(REPO));baseline.parent.mkdir(parents=True,exist_ok=True);baseline.write_bytes(target.read_bytes())
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
(OUT/'installed.json').write_text(json.dumps(dict(applied=True,newFiles=6,exactFamilyRows=receipts,sourceCount=931,newOriginalIdentities=0,newRuntimeArtifacts=0,syntheticCallerFiles=13,syntheticCallerSites=24,wholeSharedFileOverwrite=False,sourceRootLifetimeMarkersPreserved=True,productAcceptancePending=True,newExeDeployed=False),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(applied=True,newFiles=6,exactFamilyRows=len(receipts),sourceCount=931,syntheticCallerFiles=13,productAcceptancePending=True)))
