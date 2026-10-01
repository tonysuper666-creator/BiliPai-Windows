from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
P=Path(__file__).resolve().parent;M=P.parents[2];S=M/'desktop/.local/stable-product-snapshot-74'
PREFIX=chr(92)*2+'?'+chr(92)
def w(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(w(p).read_bytes()).hexdigest()
assert sha(S/'manifest.json')=='6e6f2ca97fc5e98f00f3806ad7ffeceff271466a20cc90d68479e37e38ffe210'
assert sha(S/'ordered-runtime-cp.json')=='e5e3a3375a6d5a85fab9d51343af496bb16b46eff71aadeb98e227b766dbf2cd'
cp=json.loads(w(S/'ordered-runtime-cp.json').read_text());assert len(cp)==101
for row in cp:assert sha(row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('c',M/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=P/'proof-actual74';assert not w(out).exists();w(out).mkdir(parents=True)
sources=list((P/'prepared/manual').rglob('*.kt'))+list((P/'generated').rglob('*.kt'))+[P/'ProgressStorageProof.kt']
for p in sources:
 dest=w(out/'inputs'/p.relative_to(P));dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(w(p).read_bytes())
pins=dict(snapshotManifest=sha(S/'manifest.json'),orderedCP=sha(S/'ordered-runtime-cp.json'),runtime=cp,
 inputs=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],compiler=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
w(out/'pins-before.json').write_text(json.dumps(pins,indent=2)+'\n',encoding='utf8')
jar=out/'candidate-fixture.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path'] for r in cp),'-d',str(jar)]+list(map(str,sources))
arg=out/'compile.args';w(arg).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx1g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=120)
w(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:print((r.stdout+r.stderr).decode('utf8',errors='replace'));raise SystemExit(r.returncode)
old=set()
for row in cp[:3]:
 with zipfile.ZipFile(w(row['path']))as z:old.update(z.namelist())
with zipfile.ZipFile(w(jar))as z:classes=sorted(n for n in z.namelist() if n.endswith('.class'))
assert not old.intersection(classes)
r=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',str(jar)+';'+';'.join(row['path']for row in cp),
 'com.bilipai.desktop.ui.ProgressStorageProofKt'],capture_output=True,timeout=30)
w(out/'run.log').write_bytes(r.stdout+r.stderr)
for row in cp:assert sha(row['path'])==row['sha256Bytes']
w(out/'pins-after.json').write_text(json.dumps(pins,indent=2)+'\n',encoding='utf8')
w(out/'result.json').write_text(json.dumps(dict(passed=r.returncode==0,exitCode=r.returncode,classes=len(classes),
 actualMain74=True,productOverrides=[],preparedNewProgressOnly=True,actualStore=True,
 HTTP=False,window=False,userAccount=False,originalProgressInstalledInMain=False),indent=2)+'\n',encoding='utf8')
sys.stdout.reconfigure(encoding='utf8',errors='replace');print((r.stdout+r.stderr).decode('utf8',errors='replace'))
raise SystemExit(r.returncode)
