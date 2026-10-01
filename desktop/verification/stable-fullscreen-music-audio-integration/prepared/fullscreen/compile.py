from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-55'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert sha(SNAP/'manifest.json')=='c26f15be9d6472c30b2a58a97d50c85cedc11d8074df2c1516620dd33334f124'
assert sha(SNAP/'ordered-runtime-cp.json')=='8118ea8bb2d916b8e81ad2f6e678115b437ac458a4c94cd42dc9c1a0813ea55a'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==101
refs=[]
refpins=[dict(path=str(p),sha256Bytes=sha(p),kind='explicit-prospective-parent-sole-CommentURL2')for p in refs]
s=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(s);s.loader.exec_module(c)
out=P/('runs/'+sys.argv[1]);assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=sorted(wide(P/'prepared/manual').rglob('*.kt'))+sorted(wide(P/'prepared/generated').rglob('*.kt'))+sorted(wide(P/'proof-only').rglob('*.kt'));copied=[]
sources += [MAIN/'desktop/.local/stable-video-comment-url-parity/generated/com/android/purebilibili/feature/video/screen/DesktopOriginalCommentUrlNavigation.kt']
for p in sources:
 rel=p.relative_to(wide(P)) if str(p).startswith(str(wide(P))) else Path('parent-reference')/p.name
 q=out/'source-inputs'/rel;wide(q).parent.mkdir(parents=True,exist_ok=True);wide(q).write_bytes(read(p));copied.append(q)
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return dict(runtime=cp,explicitProspectiveReferences=refpins,tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER],sources=[dict(path=str(p),sha256Bytes=sha(p))for p in copied])
save(out/'pins-before.json',pins());jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join([*map(str,refs),*(r['path']for r in cp)]),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+','.join([str(SNAP/'main-kotlin.jar'),*map(str,refs)]),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),*map(str,copied)]
arg=out/'compile.args';wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
overlap=[]
if wide(jar).exists():
 actual=set(zipfile.ZipFile(SNAP/'main-kotlin.jar').namelist())|set(zipfile.ZipFile(SNAP/'main-java.jar').namelist())
 overlap=sorted(n for n in zipfile.ZipFile(jar).namelist()if n.endswith('.class')and n in actual)
save(out/'compile-result.json',dict(passed=r.returncode==0,sources=len(copied),exitCode=r.returncode,actual55RuntimeEntries=len(cp),productAcceptance=False,prospectiveClassOverlap=overlap,candidateJarSha256Bytes=sha(jar)if wide(jar).exists()else None))
print((r.stdout+r.stderr).decode('utf-8',errors='replace')[:26000]);raise SystemExit(r.returncode)
