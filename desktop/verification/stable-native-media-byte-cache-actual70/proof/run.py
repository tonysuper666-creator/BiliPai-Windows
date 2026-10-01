from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent; MAIN=H.parents[2]
SNAPSHOT=70; S=MAIN/'desktop/.local/stable-product-snapshot-70'
EXPECTED_MANIFEST='f1e410523c6991a1f588ffc4e1d571f1251c81f6ab2b2d25ed9c947f9be03621'
EXPECTED_CP='3e2c79ad73fd69d07025ab12d617d8aa9932cef580ef1046e3550458b8482aba'
EXPECTED_KOTLIN='816e0d12b3f27b48cde35cecc8a5e7e4466692f748503d2ecaaa2c79a1159802'

def wide(p):
 s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def data(p): return wide(p).read_bytes()
def sha(p): return hashlib.sha256(data(p)).hexdigest()
def row(p): return dict(path=str(p),sha256Bytes=sha(p),size=len(data(p)))
def write(p,b): wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode() if isinstance(b,str) else b)
def dump(p,v): write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')

assert sha(S/'manifest.json')==EXPECTED_MANIFEST
assert sha(S/'ordered-runtime-cp.json')==EXPECTED_CP
cp=json.loads(data(S/'ordered-runtime-cp.json'));assert len(cp)==101
assert cp[1]['sha256Bytes']==EXPECTED_KOTLIN
out=H/'runs'/sys.argv[1]; wide(out).mkdir(parents=True,exist_ok=False)
def pins():
 for r in cp: assert sha(r['path'])==r['sha256Bytes'],r['path']
 return [row(r['path']) for r in cp]
before=pins();dump(out/'pins-before.json',before)
native=MAIN/'desktop/native/windows-x64/libmpv-2.dll'
assert sha(native)=='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
native_before=row(native)
frozen=MAIN/'desktop/.local/stable-native-media-byte-cache-parity'
assert sha(frozen/'frozen-handoff.json')=='62a15351de76f512ecb6ccae86e206f8e2fd5eeb8f0582124deff30d2af488e6'
media=frozen/'mpd-media'; media_inputs=[row(media/name) for name in ['dash.mpd','dash-stream0.mp4','dash-stream1.mp4','dash-stream2.mp4','generator-receipt.json']]
source=out/'inputs/InstalledMediaCarrierProof.kt';write(source,data(H/source.name))
dump(out/'inputs.json',dict(fixture=[row(source)],snapshotManifest=row(S/'manifest.json'),orderedCp=row(S/'ordered-runtime-cp.json'),native=native_before,media=media_inputs,productionSourceInputs=[],productionOverrides=0))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path'] for r in cp),'-d',str(out/'classes'),str(source)]
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
command=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')]
dump(out/'compiler-command.json',command)
r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=120)
write(out/'compile.log',r.stdout+r.stderr);dump(out/'compile-result.json',dict(exit=r.returncode,snapshot=70,entries=101,productionOverrides=0,inputs=[row(source)]))
if r.returncode: print(r.stdout+r.stderr);dump(out/'pins-after.json',pins());sys.exit(r.returncode)
own={p.relative_to(wide(out/'classes')).as_posix() for p in wide(out/'classes').rglob('*.class')}; existing=set()
origins={}
classes=['com/bilipai/desktop/player/PlaybackSource.class','com/bilipai/desktop/player/MpvPlayer.class','com/bilipai/desktop/player/cache/DesktopMediaByteCache.class','com/bilipai/desktop/player/cache/DesktopNativeMediaTransport.class','com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.class','com/bilipai/desktop/data/DesktopSessionStore.class','com/bilipai/desktop/data/DesktopRepository.class']
for entry in cp:
 with zipfile.ZipFile(wide(entry['path'])) as z:
  names=set(z.namelist());existing.update(names)
  for name in classes:
   if name in names:origins.setdefault(name,[]).append(dict(jar=row(entry['path']),sha256ClassBytes=hashlib.sha256(z.read(name)).hexdigest()))
overlap=sorted(own&existing);assert not overlap,overlap
assert set(origins)==set(classes) and all(len(v)==1 and v[0]['jar']['sha256Bytes']==EXPECTED_KOTLIN for v in origins.values())
dump(out/'class-origins.json',origins);dump(out/'overlap.json',dict(fixtureClasses=len(own),classOverlap=overlap,productionOverrides=0))
command=[str(cc.JAVA),'--add-modules','jdk.httpserver','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Dbilipai.mpv.path='+str(native),'-cp',str(out/'classes')+';'+';'.join(r['path'] for r in cp),'com.bilipai.desktop.player.cache.InstalledMediaCarrierProofKt',str(out/'owned-data'),str(media)]
dump(out/'runtime-command.json',command)
try:
 r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=90)
 write(out/'runtime.log',r.stdout+r.stderr); print(r.stdout+r.stderr)
 runtime_exit=r.returncode
except subprocess.TimeoutExpired as e:
 write(out/'runtime.log',(e.stdout or b'')+(e.stderr or b''));runtime_exit='timeout';print('Native fixture timeout; raw failure retained.')
after=pins();dump(out/'pins-after.json',after);assert before==after
dump(out/'runtime-result.json',dict(exit=runtime_exit,snapshot=70,entries=101,productionOverrides=0,nativeBefore=native_before,nativeAfter=row(native)))
dump(out/'receipt.json',dict(snapshot=70,entries=101,productionOverrides=0,fixtureInputs=[row(source)],compilePASS=True,runtimeExit=runtime_exit,pinsUnchanged=before==after,nativeUnchanged=native_before==row(native),MainShellAccepted=False))
sys.exit(runtime_exit if isinstance(runtime_exit,int) else 1)
