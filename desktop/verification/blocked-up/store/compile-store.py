from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def extended(path):
    name=str(path.absolute());return Path(name if name.startswith('\\\\?\\') else '\\\\?\\'+name)
def sha(path):return hashlib.sha256(extended(path).read_bytes()).hexdigest()
def write(name,text):(HERE/name).write_text(text,encoding='utf-8',newline='\n')
for previous in [HERE/'store-proof/result.json',HERE/'store-compile-evidence.json']:
    if previous.exists():previous.unlink()
write('store-contract.json',json.dumps(dict(status='preparing',stage=1,evidenceValidated=False))+'\n')
snapshot=json.loads((HERE.parent/'network-proxy-product-snapshot/manifest.json').read_text())
dependencies=json.loads((HERE.parent/'settings-tree-integration-proof/root-dependencies.json').read_text())[3:]
dependencies=[dict(path=r['path'],sha256Bytes=r['sha256Bytes']) for r in snapshot['artifacts']]+dependencies
for r in dependencies:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
cp=[r['path'] for r in dependencies]
write('dependency-identities.json',json.dumps(dependencies,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('blocked_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared').rglob('*.kt'))
if (HERE/'RunBlockedStoreFixture.kt').exists():sources.append(HERE/'RunBlockedStoreFixture.kt')
output=HERE/'store-classes';output.mkdir(exist_ok=True)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),
    '-Xplugin='+str(c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')),
    '-Xfriend-paths='+','.join(cp[:3]),'-module-name','blocked_up_store_stage','-d',str(output),*map(str,sources)]
write('store-compiler.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'store-compiler.args')],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write('store-compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-9000:]);run.check_returncode()
if (HERE/'RunBlockedStoreFixture.kt').exists():
    proof=HERE/'store-proof';proof.mkdir(exist_ok=True)
    appdata=Path(tempfile.mkdtemp(prefix='owned-appdata-',dir=proof)).absolute()
    env=os.environ.copy();env['LOCALAPPDATA']=str(appdata)
    args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(output)+';'+';'.join(cp),
        'com.bilipai.desktop.data.RunBlockedStoreFixtureKt',str(proof/'result.json')]
    write('store-runtime.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
    run=subprocess.run([str(c.JAVA),'@'+str(HERE/'store-runtime.args')],cwd=ROOT,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
    write('store-runtime.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-9000:]);run.check_returncode()
    for r in dependencies:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
    write('store-compile-evidence.json',json.dumps({'passed':True,'normalExit':run.returncode==0,'snapshotSha256':sha(HERE.parent/'network-proxy-product-snapshot/manifest.json'),
        'compiledSourceIdentities':{p.relative_to(HERE).as_posix():sha(p) for p in sources},
        'actualPatchedDiscoveryPreferencesRepositoryAndScreensCompiled':True,'markedProductionOverrides':[
            'DesktopDiscoveryPreferences.kt','DesktopDiscoveryRepository.kt','DiscoveryScreens.kt'],
        'mainEdited':False,'sharedGradleInvoked':False,'nativeWindowCreated':False,'accountRequests':False,'childLocalAppData':str(appdata)},indent=2)+'\n')
