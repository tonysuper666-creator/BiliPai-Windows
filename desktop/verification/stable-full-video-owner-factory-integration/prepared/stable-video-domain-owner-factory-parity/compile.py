from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
P=Path(__file__).resolve().parent; MAIN=P.parents[2]
SNAP=MAIN/'desktop/.local/stable-product-snapshot-68'
HOLDER=MAIN/'desktop/.local/stable-video-holder-ui-parity'
def wide(p):
 s=str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p): return wide(p).read_bytes()
def sha(p): return hashlib.sha256(read(p)).hexdigest()
def save(p,v):
 wide(p).parent.mkdir(parents=True,exist_ok=True); wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
assert sha(SNAP/'manifest.json')=='605583c59ae5975391dc4f705834ab927cfef84d0ac6c3ac40b9844284dfabdd'
assert sha(SNAP/'ordered-runtime-cp.json')=='14eeaec07e050b1fe181cfe82fb199b2f5c481d959582407412b702932fbcec9'
assert sha(HOLDER/'frozen-handoff.json')=='e0764d9788d8993fb46b2ff86b6eeaa4e469da65d414610465835e9da75b46f9'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json')); assert len(cp)==101
s=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py'); c=importlib.util.module_from_spec(s); s.loader.exec_module(c)
out=P/'runs'/sys.argv[1]; assert not wide(out).exists(); wide(out).mkdir(parents=True)
inputs=[P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoDomainOwners.kt',
 HOLDER/'install-packet/manual/com/bilipai/desktop/ui/DesktopOriginalVideoComposerEnvironment.kt',
 HOLDER/'install-packet/standalone-generated/com/android/purebilibili/feature/video/viewmodel/VideoComposerViewModel.kt']
sources=[]
for p in inputs:
 target=out/'source-inputs'/p.name; wide(target).parent.mkdir(parents=True,exist_ok=True); wide(target).write_bytes(read(p)); sources.append(target)
def pins():
 for r in cp: assert sha(r['path'])==r['sha256Bytes']
 return dict(manifestSHA256Bytes=sha(SNAP/'manifest.json'),orderedCPSHA256Bytes=sha(SNAP/'ordered-runtime-cp.json'),runtime=cp,
             sources=[dict(path=str(p),sha256Bytes=sha(p),copiedPath=str(q),copiedSHA256Bytes=sha(q)) for p,q in zip(inputs,sources)],
             tools=[dict(path=str(p),sha256Bytes=sha(p)) for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins()); jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar)]+list(map(str,sources))
arg=out/'compile.args'; wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=120)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr); save(out/'pins-after.json',pins()); assert read(out/'pins-before.json')==read(out/'pins-after.json')
entries=[]; intersection=[]
if wide(jar).exists():
 with zipfile.ZipFile(wide(jar)) as z: entries=sorted(e for e in z.namelist() if e.endswith('.class'))
 actual=set()
 for row in cp:
  with zipfile.ZipFile(wide(row['path'])) as z: actual.update(e for e in z.namelist() if e.endswith('.class'))
 intersection=sorted(set(entries)&actual); assert not intersection,intersection
save(out/'class-symbols.json',dict(candidateClasses=entries,productAndDependencyClassIntersection=intersection))
save(out/'compile-result.json',dict(passed=r.returncode==0,exitCode=r.returncode,sourceInputs=3,newFactorySources=1,
 explicitUninstalledComposerReferences=2,composerReferencesLFIdenticalToFrozenHolder337=True,classes=len(entries),
 actualRuntimeEntries=101,productionOverrides=[],productAndDependencyClassIntersection=intersection,
 productRuntimeAccepted=False,guiHttpNativeExecution=False,candidateJarSHA256Bytes=sha(jar) if wide(jar).exists() else None))
sys.stdout.reconfigure(encoding='utf8',errors='replace'); print((r.stdout+r.stderr).decode('utf8',errors='replace')); print('COMPILE',r.returncode,len(entries),'classes'); raise SystemExit(r.returncode)
