from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists());ATTEMPT=sys.argv[1]
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def args(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"'for v in values)+'\n')
deps=json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8'))
for row in deps:assert sha(row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('dynrun',REPO/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sandbox=Path(tempfile.mkdtemp(prefix='bpd-card-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
out=HERE/('classes-attempt'+ATTEMPT);proof=HERE/('proof-attempt'+ATTEMPT);proof.mkdir(exist_ok=True)
for name,main,source in [('ui','com.bilipai.desktop.ui.UiFixtureKt','UiFixture.kt'),('policy','com.bilipai.desktop.ui.PolicyFixtureKt','PolicyFixture.kt'),('protocol','com.bilipai.desktop.data.EditorTransportFixtureKt','protocol-review/EditorTransportFixture.kt')]:
 if len(sys.argv)>2 and name!=sys.argv[2]:continue
 if not (HERE/source).exists():continue
 file=HERE/(name+'-'+ATTEMPT+'.args');args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
 '-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+';'.join(r['path']for r in deps),main,str(proof)])
 r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=65)
 write(HERE/(name+'-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
for row in deps:assert sha(row['path'])==row['sha256Bytes']
write(HERE/('run-evidence-'+ATTEMPT+'.json'),json.dumps(dict(passed=True,productJars=deps[:3],classes=out.name,proof=proof.name,
preparedProductOverrideFQNs=['com.bilipai.desktop.data.DesktopDynamicCardOperations'],MainIntegration=False,mainChanged=False,sharedGradle=False,HWND=False,realAccount=False),indent=2)+'\n')
