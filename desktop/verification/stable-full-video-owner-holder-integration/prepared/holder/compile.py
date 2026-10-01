from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-65'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert sha(SNAP/'ordered-runtime-cp.json')=='024adaeff9d44585e5019f1f654c05db52b06f895d560dd120df02603dc6da8b'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==101
refs=[MAIN/'desktop/.local/stable-video-full-owner-parity/whole-compile-10-with-kotlin-module-02/candidate.jar']
expected=['e310cb71a3ccd2ae2fdc81f69fa8da8dbf0b06c4e28e11209bdf65765b1c7f67']
for p,s in zip(refs,expected):assert sha(p)==s,(p,sha(p))
s=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(s);s.loader.exec_module(c)
out=P/('runs/'+sys.argv[1]);assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=sorted(wide(P/'prepared/manual').rglob('*.kt'))+sorted(wide(P/'prepared/generated').rglob('*.kt'))+sorted(wide(P/'compile-reference').rglob('*.kt'));copied=[]
for p in sources:
 q=out/'source-inputs'/p.relative_to(wide(P));wide(q).parent.mkdir(parents=True,exist_ok=True);wide(q).write_bytes(read(p));copied.append(q)
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return dict(snapshotManifest=dict(path=str(SNAP/'manifest.json'),sha256Bytes=sha(SNAP/'manifest.json')),runtime=cp,prospectiveReferenceJars=[dict(path=str(p),sha256Bytes=sha(p))for p in refs],tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER],sources=[dict(path=str(p),sha256Bytes=sha(p))for p in copied])
save(out/'pins-before.json',pins());jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join([*map(str,refs),*(r['path']for r in cp)]),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+','.join([str(SNAP/'main-kotlin.jar'),*map(str,refs)]),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),*map(str,copied)]
arg=out/'compile.args';wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx4g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=240)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
actual=set(zipfile.ZipFile(SNAP/'main-kotlin.jar').namelist())|set(zipfile.ZipFile(SNAP/'main-java.jar').namelist())
classes=sorted(n for n in zipfile.ZipFile(jar).namelist() if n.endswith('.class')) if wide(jar).exists() else []
overlap=sorted(n for n in classes if n in actual)
save(out/'compile-result.json',dict(passed=r.returncode==0,sources=len(copied),classes=len(classes),exitCode=r.returncode,actual65RuntimeEntries=len(cp),productAcceptance=False,prospectiveClassOverlap=overlap,candidateJarSha256Bytes=sha(jar)if wide(jar).exists()else None))
sys.stdout.reconfigure(encoding='utf-8',errors='replace');print((r.stdout+r.stderr).decode('utf-8',errors='replace')[:26000]);raise SystemExit(r.returncode)
