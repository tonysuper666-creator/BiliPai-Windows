from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True; LANE=Path(__file__).resolve().parent; MAIN=LANE.parents[2]; SNAP=MAIN/'desktop/.local/stable-product-snapshot-49'
def wide(p):
 s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92); return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
sha=lambda b:hashlib.sha256(b).hexdigest()
assert sha(read(SNAP/'manifest.json'))=='7db2b7bc2816c9b7f10521c1ad1678196f4446a8bcde5cc5a837d678ac675a35'
assert sha(read(SNAP/'ordered-runtime-cp.json'))=='569b953e5def0dcaba76d9a6a6d96fea9f3572ad910e6edf13f23b88d91ee821'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json')); assert len(cp)==97
for row in cp: assert sha(read(row['path']))==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py'); c=importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
out=LANE/('compile-'+sys.argv[1]); assert not wide(out).exists(); wide(out).mkdir()
sources=sorted(wide(LANE/'prepared/selected').rglob('*.kt'))+sorted(wide(LANE/'prepared/manual').rglob('*.kt'))+[wide(LANE/'prepared/protocol/generated/com/android/purebilibili/data/repository/DesktopDynamicDetailArticleProtocol.kt')]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xfriend-paths='+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out/'classes')]+list(map(str,sources))
wide(out/'compiler.args').write_text('\n'.join('"'+str(x).replace('\\','/')+'"' for x in args),encoding='utf-8')
wide(out/'inputs.json').write_text(json.dumps(dict(immutable49=True,runtimeEntries=97,sources=[dict(path=str(p),sha256Bytes=sha(read(p))) for p in sources],explicitProspectiveOverrideFamilies=['com.android.purebilibili.data.repository.DesktopDynamicDetailArticleProtocol']),indent=2)+'\n',encoding='utf-8')
result=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=150)
wide(out/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8'); print(result.stdout+result.stderr)
if result.returncode: sys.exit(result.returncode)
with zipfile.ZipFile(wide(cp[1]['path'])) as z: product=set(z.namelist())
classes={p.relative_to(wide(out/'classes')).as_posix() for p in wide(out/'classes').rglob('*.class')}
overlap=sorted(classes&product); assert all(n.startswith('com/android/purebilibili/data/repository/DesktopDynamicDetailArticleProtocol') for n in overlap),overlap
for row in cp: assert sha(read(row['path']))==row['sha256Bytes']
wide(out/'result.json').write_text(json.dumps(dict(passed=True,inputSources=len(sources),classes=len(classes),explicitProspectiveProductOverrides=overlap,MainRuntimeAccepted=False,liveMutation=False),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(passed=True,inputSources=len(sources),classes=len(classes),prospectiveOverrides=len(overlap))))
