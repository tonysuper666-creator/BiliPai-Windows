from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-61'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert sha(SNAP/'ordered-runtime-cp.json')=='746bdf5e200774e0b1765b31fa0bd35918080cdddf04606318482147f774c057'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==101
s=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(s);s.loader.exec_module(c)
out=P/('runs/'+sys.argv[1]);assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=[P/'prepared/generated/com/android/purebilibili/feature/video/viewmodel/VideoComposerViewModel.kt',P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoComposerEnvironment.kt'];copied=[]
for p in sources:
 q=out/'source-inputs'/p.relative_to(P);wide(q).parent.mkdir(parents=True,exist_ok=True);wide(q).write_bytes(read(p));copied.append(q)
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return dict(snapshotManifest=dict(path=str(SNAP/'manifest.json'),sha256Bytes=sha(SNAP/'manifest.json')),runtime=cp,tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER],sources=[dict(path=str(p),sha256Bytes=sha(p))for p in copied])
save(out/'pins-before.json',pins());jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),*map(str,copied)]
arg=out/'compile.args';wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
actual=set(zipfile.ZipFile(SNAP/'main-kotlin.jar').namelist())|set(zipfile.ZipFile(SNAP/'main-java.jar').namelist())
classes=sorted(n for n in zipfile.ZipFile(jar).namelist() if n.endswith('.class')) if wide(jar).exists() else []
overlap=sorted(n for n in classes if n in actual)
save(out/'compile-result.json',dict(passed=r.returncode==0,sources=len(copied),classes=len(classes),exitCode=r.returncode,actual61RuntimeEntries=len(cp),productAcceptance=False,prospectiveClassOverlap=overlap,candidateJarSha256Bytes=sha(jar)if wide(jar).exists()else None))
print((r.stdout+r.stderr).decode('utf-8',errors='replace')[:16000]);raise SystemExit(r.returncode)
