from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];S=MAIN/'desktop/.local/stable-product-snapshot-66'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return safe(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def row(p):return dict(path=str(p),sha256Bytes=sha(p),size=len(data(p)))
def write(p,b):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_bytes(b.encode()if isinstance(b,str)else b)
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
assert sha(S/'manifest.json')=='e549c7badf5b11208e8b9fb3c2d5480759e9202eb157ae165feb0a8354cbcba3'
assert sha(S/'ordered-runtime-cp.json')=='43b1e432d592dd74088079e60731eda25d26f6c636df3570644a377b200ba9db'
cp=json.loads(data(S/'ordered-runtime-cp.json'));assert len(cp)==101
out=H/'runs'/sys.argv[1];safe(out).mkdir(parents=True,exist_ok=False)
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
 return [row(r['path'])for r in cp]
before=pins();dump(out/'pins-before.json',before)
prepared=H/'prepared/desktop/src/main/kotlin'
inputs=sorted(safe(prepared).rglob('*.kt'))
inputs+=sorted(safe(H/'generated').rglob('*.kt'))
if len(sys.argv)>2 and sys.argv[2] in ('proof','mpd'):inputs += [H/('MediaByteCacheProof.kt' if sys.argv[2]=='proof' else 'MediaByteMpdProof.kt')]
sources=[]
for p in inputs:
 dest=out/'inputs'/p.name;write(dest,data(p));sources.append(dest)
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path']for r in cp),'-d',str(out/'classes')]+list(map(str,sources))
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
command=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')]
r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=120);write(out/'compile.log',r.stdout+r.stderr)
dump(out/'compile-result.json',dict(exit=r.returncode,snapshot=66,entries=101,productionOverrides=0,inputs=[row(p)for p in sources]))
if r.returncode:print(r.stdout+r.stderr);sys.exit(r.returncode)
classes=list(safe(out/'classes').rglob('*.class'));own={p.relative_to(safe(out/'classes')).as_posix()for p in classes};existing=set()
for entry in cp:
 with zipfile.ZipFile(safe(entry['path']))as z:existing.update(z.namelist())
overlap=sorted(own&existing);assert not overlap,overlap
dump(out/'overlap.json',dict(classes=len(classes),classOverlap=overlap,productionOverrides=0))
if len(sys.argv)>2 and sys.argv[2] in ('proof','mpd'):
 clip=MAIN/'desktop/.local/stable-home-native-media-proof/runs/actual33-01/local-media/moving.mp4'
 assert sha(clip)=='0e09ab9a6a9af00cee99508e4a3ff41e9436f7d1850e4b31c6ceb0c22d8c4656'
 native=MAIN/'desktop/native/windows-x64/libmpv-2.dll';nativeBefore=row(native)
 classname='MediaByteCacheProofKt' if sys.argv[2]=='proof' else 'MediaByteMpdProofKt'
 command=[str(cc.JAVA),'--add-modules','jdk.httpserver','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Dbilipai.mpv.path='+str(native),'-cp',str(out/'classes')+';'+';'.join(r['path']for r in cp),'com.bilipai.desktop.player.cache.'+classname,str(out/'owned-data'),str(clip),str(H/'mpd-media')]
 dump(out/'runtime-command.json',command)
 r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=90);write(out/'runtime.log',r.stdout+r.stderr)
 dump(out/'runtime-result.json',dict(exit=r.returncode,snapshot=66,entries=101,productionOverrides=0,nativeBefore=nativeBefore,nativeAfter=row(native)))
 print(r.stdout+r.stderr)
after=pins();dump(out/'pins-after.json',after);assert before==after
dump(out/'receipt.json',dict(snapshot=66,entries=101,productionOverrides=0,explicitNewCacheSources=len(inputs),existingClassOverrides=[],compilePASS=True,runtimeExit=r.returncode if len(sys.argv)>2 else None,pinsUnchanged=True))
print('Prepared cache',len(classes),'classes; product overlap0; actual66 101 pins unchanged.');sys.exit(r.returncode)
