"""Compile fixtures only against Root's immutable actual product snapshot."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, tempfile, zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
SNAPSHOT=HERE.parent/'network-proxy-product-snapshot/manifest.json'
SNAPSHOT_PIN='c2c892064267e6a1e8c5425a3c4095a2070faacfe5debb1c07f43fae9957016d'
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,value):p.write_text(value,encoding='utf-8',newline='\n')
def load(p,name):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
preflight=load(HERE/'verify-inputs.py','product_proxy_preflight').verify()
assert sha(SNAPSHOT)==SNAPSHOT_PIN,'Root snapshot changed'
manifest=json.loads(SNAPSHOT.read_text(encoding='utf-8'))
assert manifest['frozen'] and manifest['afterSuccessfulMainCompile']
deps=json.loads((HERE.parent/'settings-network-proxy-parity/dependency-identities.json').read_text(encoding='utf-8'))
items=[dict(path=i['path'],sha256Bytes=i['sha256Bytes']) for i in manifest['artifacts']]+deps[3:]
for i in items:assert sha(i['path'])==i['sha256Bytes'],i['path']
for i in manifest['sourceFiles']:assert sha(REPO/i['path'])==i['sha256Bytes'],i['path']
with zipfile.ZipFile(items[0]['path']) as product:
 names=set(product.namelist())
 assert all(name in names for name in [
  'com/bilipai/desktop/network/DesktopNetworkProxyPlatform.class',
  'com/bilipai/desktop/settings/DesktopNetworkProxyBindings.class',
  'com/android/purebilibili/core/store/NetworkProxyStore.class',
  'com/android/purebilibili/feature/settings/SettingsNetworkProxyFieldsKt.class'])
write(HERE/'dependency-identities.json',json.dumps(items,indent=2))
(HERE/'product-snapshot-manifest.json').write_bytes(SNAPSHOT.read_bytes())
compiler=load(HERE.parent/'source9-appearance/compile-miuix.py','product_proxy_compiler')
cp=[i['path'] for i in items]
output=Path(tempfile.mkdtemp(prefix='fixture-classes-',dir=HERE))
sources=[HERE/'ProductProxyFixture.kt',HERE/'ProductProxyUiFixture.kt']
values=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(compiler.PLUGIN),
 '-Xfriend-paths='+','.join(cp[:3]),'-module-name','actual_product_proxy_fixture','-d',str(output),*map(str,sources)]
write(HERE/'compiler.args','\n'.join('"'+str(v).replace('\\','/')+'"' for v in values))
run=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(HERE/'compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-7000:]);run.check_returncode()
assert not list(output.rglob('DesktopRepository.class')) and not list(output.rglob('AdaptiveDialogComponentsKt.class'))
results=[]
for role,entry in [('transport','com.bilipai.desktop.network.ProductProxyFixtureKt'),('ui','com.bilipai.desktop.settings.ProductProxyUiFixtureKt')]:
 proof=HERE/'proof'/role;proof.mkdir(parents=True,exist_ok=True)
 sandbox=Path(tempfile.mkdtemp(prefix=role+'-env-',dir=HERE/'proof'))
 environment=os.environ.copy()
 for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:environment[name]=str(sandbox)
 values=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
  '-Duser.home='+str(sandbox),'-Djava.io.tmpdir='+str(sandbox),'-cp',str(output)+';'+';'.join(cp),entry,str(proof),str(sandbox)]
 write(HERE/(role+'-runtime.args'),'\n'.join('"'+v.replace('\\','/')+'"' for v in values))
 run=subprocess.run([str(compiler.JAVA),'@'+str(HERE/(role+'-runtime.args'))],env=environment,capture_output=True,text=True,
  encoding='utf-8',errors='replace',timeout=75)
 write(HERE/(role+'-runtime.log'),run.stdout+run.stderr);print((run.stdout+run.stderr)[-12000:]);run.check_returncode()
 results.append(dict(role=role,childEnvironmentRoot=str(sandbox),resultFile=str(proof/'result.json'),exitCode=run.returncode))
for i in items:assert sha(i['path'])==i['sha256Bytes'],i['path']
for i in manifest['sourceFiles']:assert sha(REPO/i['path'])==i['sha256Bytes'],i['path']
preflight=load(HERE/'verify-inputs.py','post_product_proxy_preflight').verify()
evidence=dict(compiledFixtureSources=[dict(path=p.name,sha256Bytes=sha(p)) for p in sources],fixtureSourceCount=2,
 actualCurrentProductSnapshotsOnly=True,productOverrideOrShadowRenderer=False,
 rootSnapshotManifestSha256=SNAPSHOT_PIN,activeFixtureClasses=str(output),immutableDependencies=items,
 runs=results,sharedGradleInvoked=False,mainModified=False,frozenModified=False,nativeWindowCreated=False,
 realAccountsOrExternalRequests=False,localOwnedSocketTrafficOnly=True,packagedExecutableTested=False)
write(HERE/'compile-evidence.json',json.dumps(evidence,indent=2))
print('Actual product snapshot transport/UI fixture executions completed; dialog acceptance scope in UI result.')
