from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists());ATTEMPT=sys.argv[1]if len(sys.argv)>1 else'1'
def safe(p):
    v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\')else'\\\\?\\'+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def args(p,v):write(p,'\n'.join('"'+str(x).replace('\\','/')+'"'for x in v)+'\n')
r=subprocess.run([sys.executable,str(HERE/'prepare.py')],capture_output=True,text=True,encoding='utf-8',errors='replace');print(r.stdout+r.stderr);r.check_returncode()
snapshot=REPO/'desktop/.local/dynamic-full-card-main-product-snapshot-lifecycle-final/manifest.json'
assert sha(snapshot)=='04e5d4e029621ffa34d360dd590beede56ff9c9110634cc0d1b1bdb181c5e3f3'
runtime=snapshot.with_name('ordered-runtime-cp.json');assert sha(runtime)=='9a5c2a6ff1d34547ab0c6a15f97ce8d0166dd9ad8cc7d50c442508a3e4b34f00'
old=json.loads((REPO/'desktop/.local/settings-dynamic-tabs-parity/dependency-identities.json').read_text(encoding='utf-8'))
deps=json.loads(runtime.read_text(encoding='utf-8'))+[r for r in old[3:]if Path(r['path']).name.startswith('kotlin-test-')and'junit'not in Path(r['path']).name]
for row in deps:assert sha(row['path'])==row['sha256Bytes'],row['path']
write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('editor_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared/desktop/src').rglob('*.kt'))+list(HERE.glob('*Fixture.kt'))
sources+=list((HERE/'protocol-review').glob('*Fixture.kt'))
cp=[r['path']for r in deps];friend=next(p for p in cp if Path(p).name=='main-kotlin.jar');out=HERE/('classes-attempt'+ATTEMPT);safe(out).mkdir(exist_ok=False)
file=HERE/('compiler-'+ATTEMPT+'.args');args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+friend,'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out)]+[str(p)for p in sources])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=58)
write(HERE/('compile-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClasses=out.name,productJars=deps[:3],sources=[dict(path=str(p),sha256Bytes=sha(p))for p in sources],explicitProductOverride=['com.bilipai.desktop.data.DesktopDynamicCardOperations'],sharedGradle=False,mainChanged=False,HWND=False),indent=2)+'\n')
