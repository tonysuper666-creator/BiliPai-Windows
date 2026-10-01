from pathlib import Path
import hashlib, json, os, subprocess, zipfile

P=Path(__file__).resolve().parent; MAIN=P.parents[2]; CANDIDATE=MAIN.parent/'BiliPai-v023'
SNAP=MAIN/'desktop/.local/stable-product-snapshot-69'
PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def bytesha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(wide(p).read_text(encoding='utf8').replace('\r\n','\n').encode()).hexdigest()
def dump(p,v):wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
assert not wide(P/'frozen-handoff.json').exists()
assert bytesha(SNAP/'manifest.json')=='4524b0c8b5e4e97bb88223a6a6f71c126bc17586111d50a10fb49f186a3d695d'
assert bytesha(SNAP/'ordered-runtime-cp.json')=='125c041f103947b5aa4be065a182cc561528bde575afe682db9e9af43a42dc9f'
cp=json.loads(wide(SNAP/'ordered-runtime-cp.json').read_text()); assert len(cp)==101
for row in cp:assert bytesha(row['path'])==row['sha256Bytes']
assert json.loads(wide(P/'runs/04/result.json').read_text())['passed']

# Pin loaded original classes and declared prospective family boundaries. The
# Java runtime's CodeSource lines and immutable ordered graph are retained raw.
jar=P/'runs/04/candidate-proof.jar'
with zipfile.ZipFile(wide(jar))as z: new={n:hashlib.sha256(z.read(n)).hexdigest()for n in z.namelist()if n.endswith('.class')}
old={}
for r in cp[:3]:
 with zipfile.ZipFile(wide(r['path']))as z:
  for n in z.namelist():
   if n.endswith('.class'):old[n]=(r['path'],hashlib.sha256(z.read(n)).hexdigest())
overlap=sorted(set(new)&set(old))
allowed=['com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding','com/bilipai/desktop/player/DesktopSubtitleAssets']
assert all(any(n==a+'.class'or n.startswith(a+'$')for a in allowed)for n in overlap)
origin=[]
for name in ['com.bilipai.desktop.ui.DesktopOriginalVideoOwnerRequestRepository','com.bilipai.desktop.ui.DesktopOriginalVideoRepositoryBinding','com.bilipai.desktop.player.DesktopSubtitleAssets','com.bilipai.desktop.ui.DesktopOriginalVideoPlaybackInvocationPorts','com.android.purebilibili.data.repository.DesktopOriginalVideoNoteProtocol']:
 entry=name.replace('.','/')+'.class'; candidate=entry in new
 origin.append(dict(className=name,loadedFrom=str(jar)if candidate else old[entry][0],classBytesSHA256=new[entry]if candidate else old[entry][1],source='explicit prepared adapter'if candidate else 'immutable actual69'))
dump(P/'class-ownership-audit.json',dict(declaredFamilyOverlaps=overlap,undeclaredOverlaps=[],origins=origin,
 runtimeCodeSourceLog='runs/04/run.log',claim='actual original protocols with explicit candidate adapters; not zero product override'))

vm=CANDIDATE/'desktop/build/generated/original-video-full-owner/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
body=wide(vm).read_text(encoding='utf8').replace('\r\n','\n')
assert lfsha(vm)=='15d36e1863173138ebac392705afbaebebc8299d5602c7be9ece66bb02e85123'
def excerpt(token):
 assert body.count(token)>=1
 pos=body.index(token); line=body[:pos].count('\n')+1
 start=body.rfind('\n',0,max(0,pos-250))+1; end=body.find('\n',pos+400)
 return dict(line=line,token=token,actualSourceExcerpt=body[start:end])
dump(P/'vip-caller-inventory.json',dict(actual69VMSourceSHA256LF=lfsha(vm),callers=[
 dict(context='initial loadVideo preferred-quality refresh',**excerpt('val effectiveVip = VideoRepository.refreshVipStatusForPreferredQualityIfNeeded(')),
 dict(context='related prefetch; not a second main-load call',**excerpt('.refreshVipStatusForPreferredQualityIfNeeded(')),
 dict(context='accepted deferred nav refresh primary VIP write',**excerpt('environment.account.updatePrimaryVip(true)'))],
 originalLoadSignature=body[body.index('    fun loadVideo(\n'):body.index('    fun loadVideo(\n')+450],
 pending='Root fixed event/immutable intent capture and fresh-load consumer',silentRetagForbidden=True,
 notes='Prefetch may retire current accepted authorization; separately reload same accepted subject, never prefetch target.'))

orig=[]
for path in ['app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt',
 'app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt',
 'app/src/main/java/com/android/purebilibili/data/repository/VideoNoteRepository.kt']:
 r=subprocess.run(['git','-C',str(CANDIDATE),'show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+path],capture_output=True,check=True)
 orig.append(dict(path=path,commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',sha256Bytes=hashlib.sha256(r.stdout).hexdigest(),ownership='reuse installed source identity/sole producer'))
manual=P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerRequestRepository.kt'
hunks=json.loads(wide(P/'exact-hunks.json').read_text());pause=json.loads(wide(P/'retained-pause-producer-hunk.json').read_text())
dump(P/'install-contract.json',dict(actualProduct='actual69 source-only baseline',copyWhitelist=[dict(source=str(manual.relative_to(P)),target='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+manual.name,sha256LF=lfsha(manual))],
 prerequisites=[dict(manifest='desktop/.local/stable-video-captured-metadata-binding-parity/frozen-handoff.json',sha256Bytes='73b2cb6a872a561c14f67eb5d72288d8e26e6f07ec8a64abdabd5da4165639f2')],
 existingFamilyPatchOrder=['exact-hunks.json:0','exact-hunks.json:1','retained-pause-producer-hunk.json'],
 wholeFileReplacementAllowed=False,gradleChange=[],sourceRegistryDelta=[],resourceDelta=[],dependencyDelta=[],
 mounted=False,controllerRetired=False,nativeAcceptance=False))
dump(P/'source-inventory.json',dict(original=orig,manual=dict(path=str(manual.relative_to(P)),sha256LF=lfsha(manual)),
 existingFamilies=hunks,soleProducerDelta=pause,replayReceipt='retained-pause-replay-proof.json',
 actualSnapshot=dict(manifest=str(SNAP/'manifest.json'),sha256Bytes=bytesha(SNAP/'manifest.json'),orderedCP=str(SNAP/'ordered-runtime-cp.json'),orderedCPSHA256Bytes=bytesha(SNAP/'ordered-runtime-cp.json'),entries=101),
 unchangedPriorPackages=[dict(path='desktop/.local/stable-video-full-owner-parity/install-packet-13/frozen-handoff.json',sha256Bytes='51f079a6a39bbe00cf0c19cc40259b26b7696d7e4a5250aa740f3e62fdba5ff4'),dict(path='desktop/.local/stable-video-holder-ui-parity/frozen-handoff.json',sha256Bytes='e0764d9788d8993fb46b2ff86b6eeaa4e469da65d414610465835e9da75b46f9')]))

raw=[]; excluded=[]
for folder,dirs,names in os.walk(wide(P)):
 dirs[:]=[d for d in dirs if d!='__pycache__']
 for name in names:
  file=Path(folder)/name;rel=str(file).removeprefix(str(wide(P))+os.sep).replace('\\','/')
  if file.suffix.lower()in{'.jar','.class','.dll','.pyc'}:
   excluded.append(dict(path=rel,sha256Bytes=bytesha(file),reason='compiled runtime/test-only reference; never install/archive binary'))
  elif rel not in {'frozen-handoff.json','excluded-runtime-artifacts.json'}:
   raw.append(dict(path=rel,sha256Bytes=bytesha(file),size=wide(file).stat().st_size))
dump(P/'excluded-runtime-artifacts.json',excluded)
raw.append(dict(path='excluded-runtime-artifacts.json',sha256Bytes=bytesha(P/'excluded-runtime-artifacts.json'),size=wide(P/'excluded-runtime-artifacts.json').stat().st_size))
dump(P/'frozen-handoff.json',dict(schema=1,scope='actual69 captured request composition + explicit retained pause source delta',artifacts=sorted(raw,key=lambda r:r['path']),
 sourceOnlyInstallContract='install-contract.json',actual69ProductOverrideFamilies=allowed,fixtureGroups=5,fixtureAssertions=15,
 HTTP=False,native=False,mounted=False,controllerRetired=False,pauseRuntimeAccepted=False,historyPreserved=True,
 limitations=['Root actual fresh VIP event consumer/pending immutable intent capture','same bound byte cache CdnPrefetcher/Portrait consumers','whole unique factory and atomic clients/facade switch','native pause acceptance after Root whole build']))
for row in raw:assert bytesha(P/row['path'])==row['sha256Bytes']
print('Frozen',len(raw),'raw; manifest',bytesha(P/'frozen-handoff.json'),'manual LF',lfsha(manual))
