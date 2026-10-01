from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile,argparse
sys.stdout.reconfigure(encoding='utf-8',errors='replace');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2]
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def row(p):return dict(path=str(p),sha256Bytes=sha(p),size=len(data(p)))
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
parser=argparse.ArgumentParser();parser.add_argument('--run',required=True);parser.add_argument('--compile-only',action='store_true');opts=parser.parse_args()
S=MAIN/'desktop/.local/stable-product-snapshot-73'
assert sha(S/'manifest.json')=='4fe15a56b0a78bda5bf067e52f4e20ceb88244eee943a007db248e6f1bb9e829'
assert sha(S/'ordered-runtime-cp.json')=='f71557c1952176118a9eba16479da2e244cacfb6cc419a65f43dc5501f822d31'
cp=json.loads(data(S/'ordered-runtime-cp.json'));assert len(cp)==101
assert cp[1]['sha256Bytes']=='35d74192fc4906068b9dc750d2906a6e423df7ff005011e6a2b052c29971d16e'
out=H/'runs'/opts.run;wide(out).mkdir(parents=True,exist_ok=False)
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
 return [row(r['path'])for r in cp]
before=pins();dump(out/'pins-before.json',before)
native=MAIN/'desktop/native/windows-x64/libmpv-2.dll';nativeBefore=row(native)
assert sha(native)=='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
media=MAIN/'desktop/.local/stable-native-media-byte-cache-parity/mpd-media'
mediaInputs=[row(media/n)for n in ['dash.mpd','dash-stream0.mp4','dash-stream1.mp4','dash-stream2.mp4','generator-receipt.json']]
worker=MAIN.with_name('BiliPai-v023')/'desktop/build/jw/f3d99d27a55656ad/r'
catalog=json.loads(data(worker/'classpath.json'));workerInputs=[row(worker/'classpath.json')]+[row(worker/r['file'])for r in catalog['classpath']+catalog['resources']]
for r in catalog['classpath']+catalog['resources']:assert sha(worker/r['file'])==r['sha256']
sources=[]
for root in [H/'prepared/existing',H/'prepared/manual']:
 for p in sorted(wide(root).rglob('*.kt')):
  relative=p.relative_to(wide(root));dest=out/'inputs'/root.name/relative;write(dest,data(p));sources.append(dest)
fixture=out/'inputs/fixture/NativeByteFailureProof.kt';write(fixture,data(H/fixture.name));sources.append(fixture)
dump(out/'inputs.json',dict(sources=[row(s)for s in sources],snapshotManifest=row(S/'manifest.json'),orderedCp=row(S/'ordered-runtime-cp.json'),native=nativeBefore,media=mediaInputs,workerResources=workerInputs,declaredProductionFamilies=3,newManual=1,installedRoot=False))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(cc.PLUGIN),'-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path']for r in cp),'-d',str(out/'classes')]+list(map(str,sources))
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
command=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')]
dump(out/'compiler-command.json',command);r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=120)
write(out/'compile.log',r.stdout+r.stderr);dump(out/'compile-result.json',dict(exit=r.returncode,entries=101,declaredProductionFamilies=3,newManual=1,inputs=[row(s)for s in sources]))
if r.returncode:print(r.stdout+r.stderr);dump(out/'pins-after.json',pins());sys.exit(r.returncode)
own={p.relative_to(wide(out/'classes')).as_posix()for p in wide(out/'classes').rglob('*.class')};existing=set();origins={}
for entry in cp:
 with zipfile.ZipFile(wide(entry['path']))as z:
  names=set(z.namelist());existing.update(names)
  for n in ['com/bilipai/desktop/player/MpvPlayer.class','com/bilipai/desktop/player/PlaybackSource.class','com/bilipai/desktop/ui/DesktopOriginalVideoCachedMediaFactory.class','com/bilipai/desktop/plugins/DesktopPluginRuntime.class']:
   if n in names:origins.setdefault(n,[]).append(dict(jar=row(entry['path']),sha256ClassBytes=hashlib.sha256(z.read(n)).hexdigest()))
families=['com/bilipai/desktop/player/cache/'+s for s in ['DesktopMediaByteCache','DesktopMediaByteLease','DesktopBoundMediaByteCache','DesktopMediaResource','DesktopMediaByteCacheKt']]+['com/bilipai/desktop/ui/'+s for s in ['DesktopOriginalVideoNativeOwner','DesktopOrdinaryPlaybackQueueHandoff','DesktopOrdinaryPluginMuteHandoff','DesktopOrdinaryPlaybackHandoff','DesktopOriginalVideoAcceptedPublication','DesktopOriginalVideoInitialPublication','DesktopOriginalVideoOwnerPluginBridge']]
overlap=sorted(own&existing)
unexpected=[n for n in overlap if not any(n==f+'.class'or n.startswith(f+'$')for f in families)]
assert not unexpected,unexpected
assert all(len(v)==1 and v[0]['jar']['sha256Bytes']==cp[1]['sha256Bytes']for v in origins.values())
dump(out/'class-origins.json',origins);dump(out/'overlap.json',dict(ownClasses=len(own),declaredFamilyOverlap=overlap,unexpected=unexpected,installedRoot=False))
if opts.compile_only:dump(out/'pins-after.json',pins());print('COMPILE PASS');sys.exit(0)
command=[str(cc.JAVA),'--add-modules','jdk.httpserver','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Dbilipai.mpv.path='+str(native),'-Dbilipai.js.workerResources='+str(worker),'-cp',str(out/'classes')+';'+';'.join(r['path']for r in cp),'com.bilipai.desktop.ui.NativeByteFailureProofKt',str(out/'owned-data'),str(media),'73']
dump(out/'runtime-command.json',command)
try:
 r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=120);write(out/'runtime.log',r.stdout+r.stderr);print(r.stdout+r.stderr);exitCode=r.returncode
except subprocess.TimeoutExpired as e:
 write(out/'runtime.log',(e.stdout or b'')+(e.stderr or b''));exitCode='timeout';print('runtime timeout; failure retained')
after=pins();dump(out/'pins-after.json',after);assert before==after and nativeBefore==row(native)
assert all(row(r['path'])==r for r in workerInputs)
dump(out/'runtime-result.json',dict(exit=exitCode,entries=101,snapshot=73,declaredProductionFamilies=3,newManual=1,nativeBefore=nativeBefore,nativeAfter=row(native),pinsUnchanged=True,workerUnchanged=True,installedRoot=False))
sys.exit(exitCode if isinstance(exitCode,int)else 1)
