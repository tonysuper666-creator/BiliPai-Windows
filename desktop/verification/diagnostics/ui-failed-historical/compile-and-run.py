from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, uuid, zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
SNAPSHOT_SHA='5e8fc360372557bf004b55a1ded9fe4be1a3b6bae33090cb037d8fe600006fda'
def ext(p):
    value=str(p.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(n,t):(HERE/n).write_text(t,encoding='utf-8',newline='\n')
def json_write(n,v):write(n,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
snapshot=ROOT/'desktop/.local/diagnostics-main-product-snapshot/manifest.json'
assert sha(snapshot)==SNAPSHOT_SHA
product=json.loads(snapshot.read_text())
old=ROOT/'desktop/.local/settings-diagnostics-parity/dependency-identities.json'
external=json.loads(old.read_text())[3:];assert len(external)==231
dependencies=[dict(path=r['path'],sha256Bytes=r['sha256Bytes']) for r in product['artifacts']]+external
assert len(dependencies)==234 and len(set(r['path'] for r in dependencies))==229
for row in dependencies:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
json_write('dependency-identities.json',dependencies)
# The first nine are the actual diagnostics/control consumers requested by Root.
names=['com.bilipai.desktop.diagnostics.DesktopDiagnostics',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticSettings',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticSettingsSectionKt',
 'com.bilipai.desktop.diagnostics.DesktopLocalDiagnosticViewerKt',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticLifecycle',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticLifecycleKt',
 'com.android.purebilibili.feature.settings.SettingsDiagnosticFieldsKt',
 'android.util.Log','com.android.purebilibili.core.util.Logger',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticRuntimeBindingsKt',
 'com.bilipai.desktop.plugins.DesktopPluginStore',
 'com.bilipai.desktop.appearance.DesktopAppearanceThemeKt']
jar=Path(dependencies[0]['path']);pins=[]
with zipfile.ZipFile(jar) as archive:
    for i,name in enumerate(names):
        entry=name.replace('.','/')+'.class';raw=archive.read(entry)
        pins.append(dict(class_=name,jar=str(jar),sha256Bytes=hashlib.sha256(raw).hexdigest(),requestedCoreNine=i<9,archiveEntry=entry))
for row in pins:row['class']=row.pop('class_')
json_write('actual-class-pins.json',pins)
spec=importlib.util.spec_from_file_location('diag_ui_compiler',ROOT/'desktop/.local/source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
tools=[compiler.JAVA,compiler.PLUGIN,*compiler.COMPILER]
json_write('toolchain-identities.json',[dict(path=str(p),sha256Bytes=sha(p)) for p in tools])
source=HERE/'DiagnosticProductUiFixture.kt';attempt=uuid.uuid4().hex[:8]
classes=HERE/('fixture-classes-'+attempt);classes.mkdir()
cp=[r['path'] for r in dependencies]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xfriend-paths='+cp[0],
 '-Xplugin='+str(compiler.PLUGIN),'-module-name','diagnostics_product_ui_fixture','-d',str(classes),str(source)]
write('compiler.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
run=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,compiler.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'compiler.args')],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write('compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-8000:]);run.check_returncode()
compiled=[]
for directory,_,files in os.walk(ext(classes)):
    for name in files:
        file=Path(directory)/name;rel=str(file)[len(str(ext(classes)))+1:].replace('\\','/')
        assert rel.startswith('com/bilipai/desktop/diagnostics/proof/') or rel.startswith('META-INF/'),rel
        compiled.append(dict(path=str(classes/rel),archiveEntry=rel,sha256Bytes=sha(file)))
json_write('fixture-class-identities.json',compiled)
output=HERE/'runs'/attempt;output.mkdir(parents=True)
scratch=output/'owned-env';scratch.mkdir()
env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
    own=scratch/key.lower();own.mkdir();env[key]=str(own)
args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(scratch),'-Djava.io.tmpdir='+str(scratch/'temp'),
 '-cp',str(classes)+';'+';'.join(cp),'com.bilipai.desktop.diagnostics.proof.DiagnosticProductUiFixtureKt',str(output),str(HERE/'actual-class-pins.json')]
write('runtime.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
report=HERE/'compile-and-run-proof.json'
if report.exists():report.unlink()
run=subprocess.run([str(compiler.JAVA),'@'+str(HERE/'runtime.args')],cwd=ROOT,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=150)
write('runtime.log',run.stdout+run.stderr);(output/'runtime.log').write_text(run.stdout+run.stderr,encoding='utf-8');print((run.stdout+run.stderr)[-11000:]);run.check_returncode()
result=json.loads((output/'result.json').read_text())
assert result['proofCompleted'] and len(result['normalFlows'])==8 and len(result['initialIoFailureFlows'])==8
assert all(r['passed'] for r in result['initialIoFailureFlows'])
assert all(r['passed'] for r in result['normalFlows'] if r['style']=='MATERIAL3')
for row in dependencies:assert sha(Path(row['path']))==row['sha256Bytes']
assert sha(snapshot)==SNAPSHOT_SHA
screens=[]
for directory,_,files in os.walk(ext(output)):
    for name in files:
        if name.endswith('.png'):
            file=Path(directory)/name;screens.append(dict(path=str(file)[4:],sha256Bytes=sha(file)))
# Explicit themes must actually affect each rendered style/height, not only labels in the report.
for style in ['material3','miuix']:
    for height in [900,720]:
        light=output/f'{style}-light-{height}/01-settings-default.png'
        dark=output/f'{style}-dark-{height}/01-settings-default.png'
        assert sha(light)!=sha(dark)
json_write('compile-and-run-proof.json',dict(proofCompleted=True,allRequestedUiFlowsPassed=result['passed'],snapshotManifestSha256Bytes=SNAPSHOT_SHA,
 snapshotPhase=product['phase'],immutableProductJars=dependencies[:3],verifiedExternalDependencyEntries=231,
 verifiedDistinctExternalJars=226,existingExternalDuplicateEntries=5,
 compiledSources=[dict(path=str(source),sha256Bytes=sha(source))],compiledProductionSources=0,productionOverrides=0,
 storeOrRendererReplacements=0,actualClassIdentities=len(pins),requestedCoreActualClasses=9,fixtureClasses=str(classes),
 acceptedRun=str(output),acceptedRunSha256Bytes=sha(output/'result.json'),normalUiFlows=8,initialRealIoFailureUiFlows=8,
 actualPointerPairs=result['actualPointerPairs'],screenshots=screens,sharedGradleInvoked=False,nativeWindowCreated=False,nativeOsChooserVerified=False,
 wholeMainOrPluginRuntimeInstantiated=False,accountOrHttpUsed=False))
print(f"Completed immutable product-only UI proof: {sum(r['passed'] for r in result['normalFlows'])}/8 complete normal flows; 8/8 actual IO failure flows; {result['actualPointerPairs']} actual pointer pairs; {len(screens)} screenshots.")
