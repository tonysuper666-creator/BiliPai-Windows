from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];PREFIX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=MAIN/'desktop/.local/stable-product-snapshot-49';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
assert sha(S/'manifest.json')=='7db2b7bc2816c9b7f10521c1ad1678196f4446a8bcde5cc5a837d678ac675a35'
assert sha(S/'ordered-runtime-cp.json')=='569b953e5def0dcaba76d9a6a6d96fea9f3572ad910e6edf13f23b88d91ee821'
for r in cp:assert sha(r['path'])==r['sha256Bytes']
core=HERE/'compile-02/classes';run=HERE/('proof-'+sys.argv[1]);wide(run).mkdir(exist_ok=False)
sources=sorted(wide(HERE/'fixtures').rglob('*.kt'))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
paths=[str(core)]+[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(core)+','+cp[1]['path'],'-cp',';'.join(paths),'-d',str(run/'classes')]+list(map(str,sources))
wide(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
wide(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
identity=dict(immutable49Manifest=sha(S/'manifest.json'),immutable49Cp=sha(S/'ordered-runtime-cp.json'),preparedOnly=True,compileExit=r.returncode,fixtureSources=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],candidateClasses=[dict(path=str(p.relative_to(wide(core))),sha256Bytes=sha(p)) for p in sorted(wide(core).rglob('*.class'))],declaredProductFamilyOverride='DesktopDynamicDetailArticleProtocol only; new nine-field model/UI/binding/policies')
if not r.returncode:
 p=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',';'.join([str(run/'classes')]+paths),'com.bilipai.desktop.ui.ArticleFixtureKt',str(run/'temporary')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
 wide(run/'run.log').write_text(p.stdout+p.stderr,encoding='utf-8');identity['runExit']=p.returncode;print((p.stdout+p.stderr).encode('ascii','backslashreplace').decode())
else:print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
wide(run/'proof.json').write_text(json.dumps(identity,indent=2)+'\n',encoding='utf-8')
for row in cp:assert sha(row['path'])==row['sha256Bytes']
sys.exit(identity.get('runExit',identity['compileExit']))
