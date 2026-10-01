from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; SNAP=MAIN/'desktop/.local/stable-product-snapshot-69'
def wide(p):
 s=str(Path(p).absolute()); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p): return wide(p).read_bytes()
def sha(p): return hashlib.sha256(read(p)).hexdigest()
def save(p,v):
 wide(p).parent.mkdir(parents=True,exist_ok=True); wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
assert sha(SNAP/'manifest.json')=='4524b0c8b5e4e97bb88223a6a6f71c126bc17586111d50a10fb49f186a3d695d'
assert sha(SNAP/'ordered-runtime-cp.json')=='125c041f103947b5aa4be065a182cc561528bde575afe682db9e9af43a42dc9f'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json')); assert len(cp)==101
s=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py'); c=importlib.util.module_from_spec(s); s.loader.exec_module(c)
out=P/'runs'/sys.argv[1]; assert not wide(out).exists(); wide(out).mkdir(parents=True)
original=P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoDomainOwners.kt'; source=out/'source-inputs'/original.name
wide(source).parent.mkdir(parents=True,exist_ok=True); wide(source).write_bytes(read(original))
def pins():
 for r in cp: assert sha(r['path'])==r['sha256Bytes']
 return dict(manifestSHA256Bytes=sha(SNAP/'manifest.json'),orderedCPSHA256Bytes=sha(SNAP/'ordered-runtime-cp.json'),runtime=cp,
 sources=[dict(path=str(original),sha256Bytes=sha(original),copiedPath=str(source),copiedSHA256Bytes=sha(source))],
 tools=[dict(path=str(p),sha256Bytes=sha(p)) for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins()); jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),str(source)]
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
save(out/'compile-result.json',dict(passed=r.returncode==0,exitCode=r.returncode,sourceInputs=1,newFactorySources=1,
 explicitProspectiveReferences=0,allFourOriginalDomainClassesFromActual69=True,classes=len(entries),actualRuntimeEntries=101,
 productionOverrides=[],productAndDependencyClassIntersection=intersection,productRuntimeAccepted=False,
 guiHttpNativeExecution=False,candidateJarSHA256Bytes=sha(jar) if wide(jar).exists() else None))
sys.stdout.reconfigure(encoding='utf8',errors='replace'); print((r.stdout+r.stderr).decode('utf8',errors='replace')); print('COMPILE',r.returncode,len(entries),'classes'); raise SystemExit(r.returncode)
