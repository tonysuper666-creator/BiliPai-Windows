from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];OLD=HERE.parent/'settings-home-dynamic-parity'
ATTEMPT=sys.argv[1] if len(sys.argv)>1 else '1'
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def args(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
r=subprocess.run([sys.executable,str(HERE/'prepare.py')],capture_output=True,text=True,encoding='utf-8',errors='replace');print(r.stdout+r.stderr);r.check_returncode()
old=json.loads((OLD/'dependency-identities.json').read_text(encoding='utf-8'))
snapshot=HERE.parent/'dynamic-crash-main-product-snapshot/manifest.json'
assert sha(snapshot)=='c01330bf9345f8f5d1be2fa70bce6d7c1c980179448ee22f2ad0f467292a6345'
deps=[dict(path=r['path'],sha256Bytes=r['sha256Bytes'])for r in json.loads(snapshot.read_text(encoding='utf-8'))['artifacts']]+old[3:]
for row in deps:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('tabs_compiler',HERE.parent/'source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=[]
sources+=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared/desktop/src').rglob('*.kt'))
sources+=[p for p in [HERE/'JunitFixture.kt',HERE/'UiFixture.kt',HERE/'TransportFixture.kt'] if p.exists()]
cp=[row['path'] for row in deps];out=HERE/('classes-attempt'+ATTEMPT);safe(out).mkdir(exist_ok=False)
file=HERE/('compiler-'+ATTEMPT+'.args');args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+cp[0],
 '-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out)]+[str(p) for p in sources])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=55)
write(HERE/('compile-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
sandbox=Path(tempfile.mkdtemp(prefix='bpd-tabs-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
safe(HERE/'proof').mkdir(exist_ok=True)
for name,main in [('junit','JunitFixtureKt'),('ui','UiFixtureKt'),('transport','TransportFixtureKt')]:
 if not (HERE/(name.capitalize()+'Fixture.kt')).exists() and name!='junit':continue
 if name=='junit' and not (HERE/'JunitFixture.kt').exists():continue
 file=HERE/(name+'-'+ATTEMPT+'.args');args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
 '-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+';'.join(cp),'com.bilipai.desktop.ui.'+main,str(HERE/'proof')])
 r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
 write(HERE/(name+'-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
for row in deps:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClasses=out.name,sources=[dict(path=str(p),sha256Bytes=sha(p))for p in sources],
 productJars=deps[:3],explicitNotMainTabsOverlay=True,mainFilesChanged=False,sharedGradle=False,HWND=False,HTTP=False),indent=2)+'\n')
