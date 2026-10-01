from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
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
S=MAIN/'desktop/.local/stable-product-snapshot-74'
assert sha(S/'manifest.json')=='6e6f2ca97fc5e98f00f3806ad7ffeceff271466a20cc90d68479e37e38ffe210'
assert sha(S/'ordered-runtime-cp.json')=='e5e3a3375a6d5a85fab9d51343af496bb16b46eff71aadeb98e227b766dbf2cd'
cp=json.loads(data(S/'ordered-runtime-cp.json'));assert len(cp)==101
assert cp[1]['sha256Bytes']=='fd88da4f4435cf0adc0bc989f1c15342002900765c9ea2f8adf6d1d744768866'
out=H/'runs'/sys.argv[1];wide(out).mkdir(parents=True,exist_ok=False)
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
for name in ['MpvCapabilityProof.kt','NativeByteFailureProof.kt']:
 source=out/'inputs'/name;write(source,data(H/name));sources.append(source)
dump(out/'inputs.json',dict(sources=[row(s)for s in sources],snapshotManifest=row(S/'manifest.json'),orderedCp=row(S/'ordered-runtime-cp.json'),
 native=nativeBefore,media=mediaInputs,workerResources=workerInputs,productionOverrides=0,MainShellAccepted=False,actualWindowAccepted=False))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(cc.PLUGIN),
 '-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path']for r in cp),'-d',str(out/'classes')]+list(map(str,sources))
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
command=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')]
dump(out/'compiler-command.json',command);r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=120)
write(out/'compile.log',r.stdout+r.stderr);dump(out/'compile-result.json',dict(exit=r.returncode,entries=101,productionOverrides=0,inputs=[row(s)for s in sources]))
if r.returncode:print(r.stdout+r.stderr);sys.exit(r.returncode)
own={p.relative_to(wide(out/'classes')).as_posix()for p in wide(out/'classes').rglob('*.class')};existing=set();origins={}
names=['com/bilipai/desktop/player/MpvPlayer.class','com/bilipai/desktop/player/MpvDecoderCapabilities.class','com/bilipai/desktop/player/MpvDecoderCapabilitiesKt.class',
 'com/bilipai/desktop/ui/DesktopOriginalMpvPlaybackCapabilities.class','com/bilipai/desktop/ui/DesktopOriginalVideoCachedMediaFactory.class',
 'com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.class','com/bilipai/desktop/ui/DesktopOriginalVideoOwnerPluginBridge.class',
 'com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackInvocationPorts.class','com/bilipai/desktop/player/cache/DesktopNativeByteFailure.class',
 'com/bilipai/desktop/player/cache/DesktopMediaByteCache.class','com/bilipai/desktop/player/cache/DesktopMediaByteLease.class',
 'com/bilipai/desktop/data/DesktopSessionStore.class','com/bilipai/desktop/data/DesktopRepository.class','com/bilipai/desktop/plugins/DesktopPluginRuntime.class']
for entry in cp:
 with zipfile.ZipFile(wide(entry['path']))as z:
  present=set(z.namelist());existing.update(present)
  for n in names:
   if n in present:origins.setdefault(n,[]).append(dict(jar=row(entry['path']),sha256ClassBytes=hashlib.sha256(z.read(n)).hexdigest()))
overlap=sorted(own&existing);assert not overlap,overlap
assert set(origins)==set(names) and all(len(v)==1 and v[0]['jar']['sha256Bytes']==cp[1]['sha256Bytes']for v in origins.values())
dump(out/'class-origins.json',origins);dump(out/'overlap.json',dict(fixtureClasses=len(own),classOverlap=overlap,productionOverrides=0))
exits={}
for label,klass,args in [('capabilities','com.bilipai.desktop.player.MpvCapabilityProofKt',[]),
 ('byte-recovery','com.bilipai.desktop.ui.NativeByteFailureProofKt',[str(out/'owned-data'),str(media),'74'])]:
 command=[str(cc.JAVA),'--add-modules','jdk.httpserver','-Dfile.encoding=UTF-8','-Djava.awt.headless=true',
  '-Dbilipai.mpv.path='+str(native),'-Dbilipai.js.workerResources='+str(worker),'-cp',str(out/'classes')+';'+';'.join(r['path']for r in cp),klass]+args
 dump(out/(label+'-command.json'),command)
 try:
  r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=120)
  write(out/(label+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);exits[label]=r.returncode
 except subprocess.TimeoutExpired as e:
  write(out/(label+'.log'),(e.stdout or b'')+(e.stderr or b''));exits[label]='timeout';print(label+' timeout; retained')
 if exits[label]!=0:break
after=pins();dump(out/'pins-after.json',after);assert before==after and nativeBefore==row(native)
assert all(row(r['path'])==r for r in workerInputs)
dump(out/'runtime-result.json',dict(exit=exits,entries=101,snapshot=74,productionOverrides=0,nativeBefore=nativeBefore,nativeAfter=row(native),
 pinsUnchanged=True,workerUnchanged=True,MainShellAccepted=False,actualWindowAccepted=False,actualAccountAccepted=False))
sys.exit(0 if len(exits)==2 and all(v==0 for v in exits.values())else 1)
