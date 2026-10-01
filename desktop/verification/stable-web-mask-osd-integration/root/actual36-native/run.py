"""Compile only a fixture; actual native execution rejects every prospective production override."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,subprocess,sys,urllib.parse,zipfile
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';NATIVE=MAIN/'desktop/native/windows-x64'
SOURCE=L/'NativeOsdMaskFixture.kt';MASK=L.parent/'stable-danmaku-web-mask-parity';OSD=L.parent/'stable-danmaku-native-osd-mask-delta';MEDIA=MAIN/'desktop/.local/stable-home-platform-media-parity'
EXPECTED_NATIVE={'libmpv-2.dll':'673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'}
LOCAL_MEDIA=[dict(path=str(MAIN/'desktop/.local/stable-home-native-media-proof/runs/actual33-01/local-media/moving.mp4'),sha256Bytes='0e09ab9a6a9af00cee99508e4a3ff41e9436f7d1850e4b31c6ceb0c22d8c4656'),dict(path=str(MAIN/'desktop/.local/stable-home-native-media-proof/runs/actual33-01/local-media/blue.mp4'),sha256Bytes='262f6f21541a05f071086581390c2484935dd329143e5b7bf06736f31eb4a965')]
REQUIRED_CLASSES=['com.bilipai.desktop.player.MpvPlayer','com.bilipai.desktop.player.MpvNative','com.bilipai.desktop.player.PlayerVideoOutputState','com.bilipai.desktop.player.PlayerVideoViewport','com.bilipai.desktop.danmaku.DesktopWebMaskPath','com.bilipai.desktop.danmaku.DesktopWebMaskPathKt','com.bilipai.desktop.danmaku.DesktopDanmakuPaintGeometry','com.bilipai.desktop.danmaku.AdvancedDanmakuRenderer','com.bilipai.desktop.danmaku.DanmakuOverlay','com.android.purebilibili.danmaku.engine.DanmakuMaskFrame','com.android.purebilibili.feature.video.danmaku.WebMaskParser']
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):
 digest=hashlib.sha256()
 with safe(p).open('rb') as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):digest.update(chunk)
 return digest.hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def classes(p):
 with zipfile.ZipFile(safe(p)) as z:return set(n for n in z.namelist() if n.endswith('.class'))
def compiler():
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c);return c
def verify_cp(snapshot,manifest_sha,cp_sha,expected_entries):
 assert sha(snapshot/'manifest.json')==manifest_sha
 assert sha(snapshot/'ordered-runtime-cp.json')==cp_sha
 cp=json.loads(read(snapshot/'ordered-runtime-cp.json'));assert len(cp)==expected_entries,('Strict actual ordered runtime count',len(cp),expected_entries)
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
 return cp
def main():
 parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['prepared-compile','actual-run']);parser.add_argument('run_name');parser.add_argument('--snapshot',type=Path);parser.add_argument('--manifest-sha');parser.add_argument('--cp-sha');parser.add_argument('--entries',type=int);options=parser.parse_args()
 assert options.run_name and all(c.isalnum() or c in '-_' for c in options.run_name)
 overlay=[]
 if options.mode=='prepared-compile':
  assert not any([options.snapshot,options.manifest_sha,options.cp_sha,options.entries])
  snapshot=MAIN/'desktop/.local/stable-product-snapshot-31';cp=verify_cp(snapshot,'999bc989c995f877761b22c5c00a6a0d99819100d8cef591a25f94b5d56a3a5d','0da93f5423ef53986a1939d97b37114a97e4a8e64b646052d34eaeaa916a6dee',92)
  for lane,pin in [(OSD,'bb8d61645481ba0eba1eeb2c0069cff4f9fde1bc46bbd57d655b5e0b327029b2'),(MASK,'5faef2a880cc1376185165edd4f71eefa09fb4711133ba7706a7bc79cfbd82c5'),(MEDIA,'9b9ced6df9540109469a25da9917407126e97bdce5656688b04627f2f05fe966')]:assert sha(lane/'frozen-handoff.json')==pin
  for lane,receipt,jar in [(OSD,'compile-02/compile-result.json','compile-02/native-osd-mask-candidate.jar'),(MASK,'compile-09/compile-result.json','compile-09/original-web-mask-candidate.jar'),(MEDIA,'runs/04/compile-result.json','runs/04/prepared-home-platform.jar')]:
   r=json.loads(read(lane/receipt));assert r['status']=='PASS' and sha(lane/jar)==r['jarSha256Bytes'];overlay.append(dict(path=str(lane/jar),sha256Bytes=r['jarSha256Bytes'],scope='PROSPECTIVE compile-only, forbidden for execution'))
  compile_cp=[r['path'] for r in overlay+cp];friends=[str(snapshot/'main-kotlin.jar')]+[r['path'] for r in overlay]
 else:
  assert all([options.snapshot,options.manifest_sha,options.cp_sha,options.entries]),'Native execution requires Root-provided immutable installed graph pins and strict runtime count'
  assert options.entries==97,'Current Root-installed terminal combination requires exactly 97 runtime entries'
  snapshot=options.snapshot.absolute();cp=verify_cp(snapshot,options.manifest_sha,options.cp_sha,options.entries);compile_cp=[r['path'] for r in cp];friends=[str(snapshot/'main-kotlin.jar')]
  assert str(snapshot/'main-kotlin.jar') in compile_cp
  inventories={r['path']:classes(r['path']) for r in cp}
  for name in REQUIRED_CLASSES:
   locations=[p for p,names in inventories.items() if name.replace('.','/')+'.class' in names]
   assert locations==[str(snapshot/'main-kotlin.jar')],('Missing installed closure or duplicate class',name,locations)
  assert not any(any(x in p for x in ['stable-danmaku-web-mask-parity','stable-danmaku-native-osd-mask-delta','stable-home-platform-media-parity']) for p in compile_cp),'No prospective production overlay allowed at native runtime'
 for name,pin in EXPECTED_NATIVE.items():assert sha(NATIVE/name)==pin
 for r in LOCAL_MEDIA:assert sha(r['path'])==r['sha256Bytes']
 out=L/'runs'/options.run_name;assert not out.exists(),'Never overwrite an earlier fixture run';out.mkdir(parents=True)
 c=compiler();toolchain=[dict(path=str(p),sha256Bytes=sha(p)) for p in [c.JAVA]+c.COMPILER]
 def pins():
  for r in cp+overlay:assert sha(r['path'])==r['sha256Bytes'],r['path']
  for r in LOCAL_MEDIA:assert sha(r['path'])==r['sha256Bytes'],r['path']
  assert sha(NATIVE/'libmpv-2.dll')==EXPECTED_NATIVE['libmpv-2.dll']
  currentToolchain=[dict(path=r['path'],sha256Bytes=sha(r['path'])) for r in toolchain];assert currentToolchain==toolchain
  provenance=[dict(path=str(p),sha256Bytes=sha(p)) for p in [MAIN/'desktop/third-party/libmpv/SOURCES.json',NATIVE/'provenance.json']]
  return dict(actualCP=cp,prospectiveCompileOnly=overlay,source=dict(path=str(SOURCE),sha256Bytes=sha(SOURCE)),runner=dict(path=str(L/'run.py'),sha256Bytes=sha(L/'run.py')),nativeDLL=dict(path=str(NATIVE/'libmpv-2.dll'),sha256Bytes=sha(NATIVE/'libmpv-2.dll')),nativeProvenance=provenance,localMedia=LOCAL_MEDIA,toolchain=currentToolchain)
 before=pins();save(out/'dependency-pins-before.json',before)
 frozenSource=out/'source-inputs/NativeOsdMaskFixture.kt';safe(frozenSource.parent).mkdir(parents=True);safe(frozenSource).write_bytes(safe(SOURCE).read_bytes())
 target=out/'native-osd-mask-fixture.jar';args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(compile_cp),'-Xfriend-paths='+','.join(friends),'-module-name','native_osd_mask_fixture','-d',str(target),str(frozenSource)]
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 r=subprocess.run([str(c.JAVA),'-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
 write(out/'compiler.log',r.stdout+r.stderr);save(out/'compile-result.json',dict(status='PASS' if r.returncode==0 else 'FAIL',exitCode=r.returncode,fixtureOnly=True,mode=options.mode,actualManifest=sha(snapshot/'manifest.json'),orderedActualCP=sha(snapshot/'ordered-runtime-cp.json'),prospectiveCompileOnly=overlay,nativeExecuted=False))
 if r.returncode:print(r.stdout+r.stderr);return r.returncode
 own=classes(target);allClasses=set()
 for p in compile_cp:allClasses.update(classes(p))
 overlap=sorted(own&allClasses);assert not overlap,overlap
 save(out/'fixture-symbol-audit.json',dict(status='PASS',fixtureClassCount=len(own),fixtureClasses=sorted(own),productionClassOverrides=0,intersection=overlap,fixtureJarSHA256Bytes=sha(target)))
 if options.mode=='prepared-compile':
  after=pins();assert before==after;save(out/'dependency-pins-after.json',after);print('PASS prospective fixture-only compile; no native/JVM fixture executed');return 0
 command=[str(c.JAVA),'-Xmx2g','-Djava.awt.headless=false','-Djava.security.manager=allow','-Dbilipai.mpv.path='+str(NATIVE/'libmpv-2.dll'),'-cp',';'.join([str(target)]+compile_cp),'com.bilipai.desktop.danmaku.nativeosdproof.NativeOsdMaskFixtureKt']+[r['path'] for r in LOCAL_MEDIA]+[str(out/'proof')]
 save(out/'java-command.json',command)
 r=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=130);write(out/'runtime.log',r.stdout+r.stderr);save(out/'runtime-exit.json',dict(exitCode=r.returncode,productionClassOverrides=0,prospectiveOverlay=False))
 if r.returncode:print(r.stdout+r.stderr);return r.returncode
 result=json.loads(read(out/'proof/result.json'));assert result['status']=='PASS' and result['productionClassOverrides']==0 and result['nativeHiddenAWTWindow'] and not result['RootComposeWindowMounted']
 assert {r['class'] for r in result['actualCodeSources']}==set(REQUIRED_CLASSES)
 with zipfile.ZipFile(safe(snapshot/'main-kotlin.jar')) as z:
  for row in result['actualCodeSources']:
   url=urllib.parse.urlparse(row['codeSource']);assert url.scheme=='file'
   path=Path(urllib.parse.unquote(url.path).lstrip('/'));assert path.resolve()==(snapshot/'main-kotlin.jar').resolve()
   assert hashlib.sha256(z.read(row['class'].replace('.','/')+'.class')).hexdigest()==row['classSha256Bytes']
 after=pins();assert before==after
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 for r in LOCAL_MEDIA:assert sha(r['path'])==r['sha256Bytes']
 assert sha(NATIVE/'libmpv-2.dll')==EXPECTED_NATIVE['libmpv-2.dll']
 save(out/'dependency-pins-after.json',after)
 save(out/'accepted.json',dict(status='PASS',actualManifest=sha(snapshot/'manifest.json'),orderedActualCP=sha(snapshot/'ordered-runtime-cp.json'),strictActualRuntimeEntries=len(cp),actualClassOriginsVerified=len(REQUIRED_CLASSES),productionClassOverrides=0,nativeDLL=before['nativeDLL'],resultSHA256Bytes=sha(out/'proof/result.json'),rootWindowAccepted=False,nativeOverlayScreenPaintAccepted=False,physicalMonitorPresentationObserved=False,scope='Actual default native HWND player observed OSD/source lifecycle + same production Java2D path/clip helper and original advanced renderer; private hidden AWT window only'))
 print('PASS actual installed product-only native OSD/source and Java2D mask proof')
 return 0
if __name__=='__main__':raise SystemExit(main())
