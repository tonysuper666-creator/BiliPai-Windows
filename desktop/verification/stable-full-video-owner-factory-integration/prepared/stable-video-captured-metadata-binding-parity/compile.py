from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-67'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==101 and sha(SNAP/'ordered-runtime-cp.json')=='25f8c144a4a8aeb5230beb25a927f294567abf019c6249173aec9967195e4807'
s=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(s);s.loader.exec_module(c)
out=P/'runs'/sys.argv[1];assert not wide(out).exists();wide(out).mkdir(parents=True)
source=out/'DesktopOriginalVideoRepositoryBinding.kt';wide(source).write_bytes(read(P/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt'))
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return dict(manifestSHA256Bytes=sha(SNAP/'manifest.json'),orderedCPSHA256Bytes=sha(SNAP/'ordered-runtime-cp.json'),runtime=cp,sourceSHA256Bytes=sha(source),tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins());jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),str(source)]
arg=out/'compile.args';wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=120)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
entries=[];overlap=[]
if wide(jar).exists():
 with zipfile.ZipFile(wide(jar))as z:entries=[e for e in z.namelist()if e.endswith('.class')]
 with zipfile.ZipFile(wide(SNAP/'main-kotlin.jar'))as z:actual=set(z.namelist())
 overlap=sorted(set(entries)&actual)
 assert all(e.startswith('com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding')for e in entries),entries
save(out/'compile-result.json',dict(passed=r.returncode==0,exitCode=r.returncode,sourceInputs=1,classes=len(entries),actualRuntimeEntries=101,declaredProspectiveOverrideFamily='DesktopOriginalVideoRepositoryBinding only',declaredClassOverlap=overlap,undeclaredProductionOverrides=[],repositoryVisitorVisibilityHunkSourceOnly=True,parentMetadataProtocolNotReproducedOrOverridden=True,productRuntimeAccepted=False,candidateJarSHA256Bytes=sha(jar)if wide(jar).exists()else None))
sys.stdout.reconfigure(encoding='utf8',errors='replace');print((r.stdout+r.stderr).decode('utf8',errors='replace'));print('COMPILE',r.returncode,len(entries),'classes');raise SystemExit(r.returncode)
