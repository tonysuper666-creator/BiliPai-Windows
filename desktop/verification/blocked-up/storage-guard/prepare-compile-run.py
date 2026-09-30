"""Independent immutable product + visibly marked stage1 source overrides. No shared builds/windows."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def load(p,name):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
producer=HERE.parent/'settings-blocked-up-parity'
manifest=producer/'store-artifact-manifest.json'
assert sha(manifest)=='8793d8cb7860406ac02891dc7d2c3253407e297043f25f12984709544b6fb039'
for record in json.loads(manifest.read_text())['files']:assert sha(producer/record['path'])==record['sha256Bytes'],record['path']
deps=json.loads((HERE.parent/'network-proxy-product-proof/dependency-identities.json').read_text())
def verify_deps():
 for record in deps:assert sha(Path(record['path']))==record['sha256Bytes'],record['path']
verify_deps();write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
sources=[];overrides=[]
for name in ['DesktopDiscoveryPreferences.kt','DesktopDiscoveryRepository.kt','DesktopBlockedUpStore.kt']:
 relative='desktop/src/main/kotlin/com/bilipai/desktop/data/'+name
 upstream=HERE.parent/'blocked-up-store-main-review'/('review-delta' if name=='DesktopBlockedUpStore.kt' else 'payload')/relative
 destination=HERE/'marked-overrides'/relative
 safe(destination.parent).mkdir(parents=True,exist_ok=True);safe(destination).write_bytes(safe(upstream).read_bytes())
 assert sha(destination)==sha(upstream)
 overrides.append(dict(path=relative,source=str(upstream),sha256Bytes=sha(upstream),currentMainSha256Bytes=sha(REPO/relative)))
 sources.append(destination)
for original in (producer/'generated').rglob('*.kt'):
 destination=HERE/'marked-overrides/generated'/original.relative_to(producer/'generated')
 safe(destination.parent).mkdir(parents=True,exist_ok=True);safe(destination).write_bytes(safe(original).read_bytes())
 assert sha(destination)==sha(original);sources.append(destination)
write(HERE/'marked-overrides.json',json.dumps(overrides,indent=2)+'\n')
sources.extend(HERE/name for name in ['DesktopDiscoveryStorageGuard.kt','DesktopDiscoveryStorageBoundary.kt','DiscoveryStorageFixture.kt','DiscoveryStorageUiFixture.kt'])
compiler=load(HERE.parent/'source9-appearance/compile-miuix.py','independent_compiler')
serial=compiler.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
out=HERE/'classes-attempt1';safe(out).mkdir(parents=True,exist_ok=False)
cp=[record['path'] for record in deps]
def args(path,values):write(path,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
argfile=HERE/'compiler.args'
args(argfile,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(compiler.PLUGIN),'-Xplugin='+str(serial),
 '-Xfriend-paths='+cp[0],'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out),*map(str,sources)])
result=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],cwd=REPO,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(HERE/'compile.log',result.stdout+result.stderr);print(result.stdout+result.stderr);result.check_returncode()
proof=HERE/'proof';safe(proof).mkdir(parents=True,exist_ok=True)
sandbox=Path(tempfile.mkdtemp(prefix='sandbox-',dir=proof));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p.absolute())
for name,main,output in [('disk-guard','DiscoveryStorageFixtureKt',proof/'disk-guard.json'),('original-ui','DiscoveryStorageUiFixtureKt',proof/'ui')]:
 argfile=HERE/(name+'-runtime.args')
 args(argfile,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
  '-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+';'.join(cp),'com.bilipai.desktop.ui.'+main,str(output)])
 r=subprocess.run([str(compiler.JAVA),'@'+str(argfile)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
 write(HERE/(name+'-runtime.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
verify_deps()
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClassDirectory=out.name,compiledSourceCount=len(sources),
 sources=[dict(path=str(p.relative_to(HERE)),sha256Bytes=sha(p)) for p in sources],productSnapshotJars=deps[:3],
 compilerPluginSha256={str(compiler.PLUGIN):sha(compiler.PLUGIN),str(serial):sha(serial)},markedStage1Overrides=True,
 noSharedGradle=True,noHWND=True,noHTTP=True,noUserAccountFiles=True,rootRestoreFenceIncluded=False),indent=2)+'\n')
