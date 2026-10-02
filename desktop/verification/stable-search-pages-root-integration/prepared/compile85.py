from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
P=Path(__file__).resolve().parent;M=P.parents[2];S=M/'desktop/.local/stable-product-snapshot-85'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def data(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def write(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(v.encode()if isinstance(v,str)else v)
def dump(p,v):write(p,json.dumps(v,indent=2)+'\n')
assert sha(S/'manifest.json')=='417663152a99ecf23e6887e2940b15c7c1cfd06c6c3f0b2b1925430ed4ca4012'
assert sha(S/'ordered-runtime-cp.json')=='6398174d444cf02365bc66480c1c37bb00800a520e681c4649100067af68b419'
cp=json.loads(data(S/'ordered-runtime-cp.json'));assert len(cp)==101
def check():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
check();out=P/'compile'/sys.argv[1];out.mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('c',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list((P/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+list((P/'generated').rglob('*.kt'))+list((P/'direct').rglob('*.kt'))
if len(sys.argv)>2 and sys.argv[2]=='fixture':sources+=[P/'SearchOwnerFixture.kt']
if 'fresh-settings' in sys.argv:
 sources+=list((P/'producer-check/full-navigation-settings').rglob('*.kt'))+list((P/'producer-check/settings-privacy').rglob('*.kt'))
pins=dict(actual85Manifest=sha(S/'manifest.json'),orderedCP=sha(S/'ordered-runtime-cp.json'),sourceInputs=[dict(path=str(p),sha256Bytes=sha(p))for p in sources],candidateWritten=False)
dump(out/'pins-before.json',pins)
classpath=[Path(r['path'])for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xplugin='+str(c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')),'-Xfriend-paths='+str(cp[1]['path']),'-cp',';'.join(map(str,classpath)),'-d',str(out/'prospective.jar')]+list(map(str,sources))
write(out/'compile.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx4g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,timeout=240)
write(out/'compile.log',r.stdout+r.stderr);check()
after=dict(pins,sourceInputs=[dict(path=str(p),sha256Bytes=sha(p))for p in sources])
dump(out/'pins-after.json',after);assert after==pins,'Prepared source changed during compile'
dump(out/'result.json',dict(passed=r.returncode==0,exitCode=r.returncode,inputs=len(sources),actual85Runtime101=True,ShellCompiled=any(p.name=='DesktopShell.kt'for p in sources),RootMounted=False,native=False,HTTP=False))
sys.stdout.reconfigure(encoding='utf8');print((r.stdout+r.stderr).decode('utf8',errors='replace')[-22000:]);r.check_returncode()
