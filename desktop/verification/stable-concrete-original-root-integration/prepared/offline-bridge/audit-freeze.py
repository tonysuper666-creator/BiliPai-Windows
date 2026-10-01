from pathlib import Path
import hashlib,json,subprocess,zipfile,re,sys
sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,value):safe(p).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
sources=[]
paths=['feature/download/OfflineVideoPlayerScreen.kt','feature/download/OfflineVideoPlaybackPolicy.kt','feature/download/OfflinePlaybackSessionPolicy.kt','feature/download/OfflineEpisodeQueuePolicy.kt','feature/download/OfflineVideoRoutingPolicy.kt','feature/download/DownloadListNavigationPolicy.kt','feature/download/DownloadListScreen.kt','navigation/AppNavigation.kt']
for suffix in paths:
 path='app/src/main/java/com/android/purebilibili/'+suffix
 blob=subprocess.check_output(['git','-C',str(CANDIDATE),'show',COMMIT+':'+path])
 source=blob.decode('utf-8').replace('\r\n','\n');assert read(CANDIDATE/path).replace('\r\n','\n')==source,path
 target=LANE/'retained-original'/suffix;safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_bytes(blob)
 anchors=[]
 for line,text in enumerate(source.splitlines(),1):
  if re.match(r'(private (data class|enum class|fun)|fun OfflineVideoPlayerScreen)',text) or 'resolveDownloadTaskClickTarget(' in text or 'BiliPaiNavEntryContentRole.OFFLINE_VIDEO_PLAYER' in text or 'ScreenRoutes.OfflineVideoPlayer.createRoute' in text:
   anchors.append({'line':line,'text':text.strip()})
 sources.append({'source':path,'commit':COMMIT,'gitBlob':subprocess.check_output(['git','-C',str(CANDIDATE),'rev-parse',COMMIT+':'+path],text=True).strip(),'sha256Bytes':hashlib.sha256(blob).hexdigest(),'sha256LF':hashlib.sha256(source.encode()).hexdigest(),'lineCount':len(source.splitlines()),'retained':str(target),'anchors':anchors,'installedByThisPacket':False})
save(LANE/'source-inventory.json',{'fixedCommit':COMMIT,'sources':sources,'originalUiDeclaredParity':False,'registryDelta':[],'note':'These whole Git blobs are retained review-only. This packet installs only two Windows navigation consumers of existing original policies and actors.'})
env={'__file__':str(LANE/'audit-freeze.py')}
parser=read(CANDIDATE/'desktop/.local/stable-home-live-list-parity/audit_abi.py').split('aud=json.loads',1)[0];exec(parser,env);parse=env['parse']
audit=json.loads(read(LANE/'input-audit.json'));cp=audit['verifiedDependencyPins'];compiled=json.loads(read(LANE/'compile-03/compile-result.json'));proof=json.loads(read(LANE/'proof-04/proof-result.json'))
assert len(cp)==97 and proof['assertions']==25 and proof['status']=='PASS'
actual=set();actualmethods={}
for row in cp:
 assert sha(row['path'])==row['sha256Bytes']
 with zipfile.ZipFile(safe(row['path'])) as z:
  for name in z.namelist():
   if not name.endswith('.class'):continue
   actual.add(name)
   if name.endswith('Kt.class'):
    for access,method,desc in parse(z.read(name)):
     if access&9==9:actualmethods.setdefault((name.rsplit('/',1)[0],method,desc),[]).append(name)
classes=0;methods=0;invalid=[];overlap=[]
with zipfile.ZipFile(safe(compiled['jar'])) as z:
 for name in z.namelist():
  if not name.endswith('.class'):continue
  classes+=1;assert name not in actual,name
  entries=parse(z.read(name));methods+=len(entries)
  for access,method,desc in entries:
   if any(c in method for c in '.;/[') or ('<' in method and method not in ['<init>','<clinit>']):invalid.append([name,method])
   if name.endswith('Kt.class') and access&9==9 and (name.rsplit('/',1)[0],method,desc) in actualmethods:overlap.append([name,method,desc])
assert not invalid and not overlap
save(LANE/'abi-audit.json',{'actualSnapshot':44,'actualRuntimeEntries':97,'candidateClasses':classes,'candidateMethods':methods,'newClassOverlap':[],'samePackagePublicStaticMethodOverlap':overlap,'invalidJvmMethodNames':invalid,'prospectiveExistingClassOverrides':[]})
payloads=[]
for p in sorted(safe(LANE/'prepared').rglob('*.kt')):
 target=p.relative_to(safe(LANE/'prepared')).as_posix();payloads.append({'prepared':str(p),'target':target,'sha256Bytes':sha(p),'kind':'new-Windows-navigation-consumer'})
assert len(payloads)==2
contract={'fixedCommit':COMMIT,'actualCompileSnapshot':44,'actualRuntimeEntries':97,'payloads':payloads,'sharedHunks':[],'originalRegistryDelta':[],'existingProducerChanges':[],'requiresRootLeaf':'BiliPaiNavKey.OfflineVideoPlayer(taskId)','requiredRecipe':str(LANE/'ROOT-INTEGRATION.md'),'actualRootRuntimeAccepted':False,'fullOriginalOfflineUi':False,'installProofJar':False,'store':'same existing DesktopDownloadManager task queue and SessionStore admission','player':'same existing DesktopRetainedMedia.offline and Root MpvPlayer; no production player construction'}
save(LANE/'install-contract.json',contract)
items=[]
for p in sorted(safe(LANE).rglob('*')):
 if p.is_file() and p.suffix not in ['.class','.pyc','.mp4'] and 'local-fixture' not in p.parts and p.name!='frozen-handoff.json':
  items.append({'path':p.relative_to(safe(LANE)).as_posix(),'sha256Bytes':sha(p),'size':p.stat().st_size})
frozen={'status':'FROZEN_SOURCE_ONLY','fixedCommit':COMMIT,'snapshot':44,'runtimeEntries':97,'payloadCount':2,'sharedHunks':0,'originalIdentityDelta':0,'compile':{'sources':2,'classes':classes,'methods':methods,'jarSha256Bytes':compiled['jarSha256Bytes'],'newClassOverlap':0,'topMethodOverlap':0,'invalidJvmMethods':0},'proof':{'groups':4,'assertions':25,'pointerPairs':1,'actualNativeLocalFirstFrame':True,'sameRetainedOfflineMemory':True,'HTTPCalls':0,'chooserOpened':False},'boundaries':{'fullOriginalOfflineUi':False,'actualRootMounted':False,'actualRootRuntimeAccepted':False,'nativeRootWindowOrOverlayPaintAccepted':False,'rawOriginalUiReviewOnly':True,'productionExistingClassOverrides':0,'sharedGradleRun':False},'installContract':{'path':str(LANE/'install-contract.json'),'sha256Bytes':sha(LANE/'install-contract.json')},'files':items}
save(LANE/'frozen-handoff.json',frozen)
print(json.dumps({'manifest':str(LANE/'frozen-handoff.json'),'manifestSha256Bytes':sha(LANE/'frozen-handoff.json'),'contractSha256Bytes':sha(LANE/'install-contract.json'),'recipeSha256Bytes':sha(LANE/'ROOT-INTEGRATION.md'),'originalOfflineScreenLines':sources[0]['lineCount'],'compile':frozen['compile'],'proof':frozen['proof']},indent=2))
