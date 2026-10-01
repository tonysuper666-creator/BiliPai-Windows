from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
S=MAIN/'desktop/.local/stable-product-snapshot-49'
assert sha(S/'manifest.json')=='7db2b7bc2816c9b7f10521c1ad1678196f4446a8bcde5cc5a837d678ac675a35'
assert sha(S/'ordered-runtime-cp.json')=='569b953e5def0dcaba76d9a6a6d96fea9f3572ad910e6edf13f23b88d91ee821'
cp=json.loads(safe(S/'ordered-runtime-cp.json').read_text());assert len(cp)==97
for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
run=HERE/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));safe(run).mkdir(parents=True,exist_ok=False)
sources=list((HERE/'prepared/manual').rglob('*.kt'))+list((HERE/'prepared/selected').rglob('*.kt'))
if '--integrated' in sys.argv:sources+=list((HERE/'prepared/existing').rglob('*.kt'))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
serial=cc.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name',('com_bilipai_desktop_bilipai_windows' if '--integrated' in sys.argv else 'desktop_personal_queue_candidate'),
 '-Xfriend-paths='+str(S/'main-kotlin.jar'),'-Xplugin='+str(cc.PLUGIN),'-Xplugin='+str(serial),'-cp',';'.join(r['path'] for r in cp),'-d',str(run/'classes')]+[str(p) for p in sources]
safe(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
ident=dict(actualSnapshot=str(S),manifestSha=sha(S/'manifest.json'),orderedCpSha=sha(S/'ordered-runtime-cp.json'),cpEntries=97,
 sources=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],prepared=True,mainIntegration=False,
 productOverrides='none' if '--integrated' not in sys.argv else 'explicit four Controller/Listen/Bridge/Shell existing source families',
 serializationCompiler=dict(path=str(serial),sha256Bytes=sha(serial)))
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=240)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');ident['exit']=r.returncode
safe(run/'inputs.json').write_text(json.dumps(ident,indent=2)+'\n',encoding='utf-8')
if r.returncode==0:
 with zipfile.ZipFile(safe(run/'candidate.jar'),'w',zipfile.ZIP_DEFLATED) as z:
  for p in (run/'classes').rglob('*'):
   if safe(p).is_file():z.writestr(p.relative_to(run/'classes').as_posix(),safe(p).read_bytes())
for row in cp:assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('exit',r.returncode);sys.exit(r.returncode)
