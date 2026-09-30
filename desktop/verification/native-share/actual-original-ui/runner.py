"""Compile only the task fixture; run immutable actual Main with no product overrides."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
def ext(path):
    s=str(Path(path).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def write(path,value):
    ext(path.parent).mkdir(parents=True,exist_ok=True);ext(path).write_text(value,encoding='utf-8',newline='\n')
def save(path,value):write(path,json.dumps(value,indent=2,ensure_ascii=False)+'\n')
def load(path):return json.loads(ext(path).read_text(encoding='utf-8'))
def args_file(path,values):write(path,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
PIN_NAMES=[
 'com.bilipai.desktop.diagnostics.DesktopDiagnostics',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticLifecycle',
 'com.bilipai.desktop.diagnostics.DesktopCrashPromptController',
 'com.bilipai.desktop.diagnostics.DesktopCrashPromptState',
 'com.bilipai.desktop.diagnostics.DesktopCrashShareCache',
 'com.bilipai.desktop.diagnostics.DesktopCrashShareLease',
 'com.bilipai.desktop.diagnostics.DesktopNativeCrashShare',
 'com.bilipai.desktop.diagnostics.DesktopNativeShareTransport',
 'com.bilipai.desktop.diagnostics.DesktopNativeShareRetirement',
 'com.bilipai.desktop.diagnostics.DesktopCrashPromptHostKt',
 'com.bilipai.desktop.diagnostics.DesktopLocalDiagnosticViewerKt',
 'com.bilipai.desktop.diagnostics.DesktopDiagnosticLifecycleKt',
 'com.android.purebilibili.DesktopPendingCrashLogPromptKt',
 'com.android.purebilibili.DesktopCrashLogPromptPolicyKt',
 'com.bilipai.desktop.appearance.DesktopAppearanceThemeKt',
 'com.bilipai.desktop.plugins.DesktopPluginStore',
 'com.bilipai.desktop.update.UpdateStorage',
 'com.bilipai.desktop.MainKt',
 'com.bilipai.desktop.DesktopShellKt']
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('phase',choices=['verify','compile','run'])
p.add_argument('--snapshot',type=Path,required=True);p.add_argument('--snapshot-sha',required=True)
p.add_argument('--runtime-cp',type=Path,required=True);p.add_argument('--runtime-cp-sha',required=True)
p.add_argument('--attempt',default='01');a=p.parse_args()
assert a.attempt.isalnum()
assert sha(a.snapshot)==a.snapshot_sha,'Snapshot supplied pin mismatch'
assert sha(a.runtime_cp)==a.runtime_cp_sha,'Ordered CP supplied pin mismatch'
snapshot=load(a.snapshot);product=snapshot['artifacts'];assert len(product)==3
for row in product:
    assert Path(row['path']).suffix=='.jar' and sha(row['path'])==row['sha256Bytes']
    with zipfile.ZipFile(ext(row['path'])) as jar:assert len(jar.namelist())==row['entries']
raw=load(a.runtime_cp);deps=raw if isinstance(raw,list) else raw['entries'];assert len(deps)==89
for row in deps:assert Path(row['path']).suffix=='.jar' and sha(row['path'])==row['sha256Bytes']
product_paths={str(Path(r['path']).resolve()).casefold() for r in product}
actual_paths=[str(Path(r['path']).resolve()).casefold() for r in deps]
assert all(p in actual_paths for p in product_paths)
assert len([p for p in actual_paths if p not in product_paths])==86
cp=[r['path'] for r in deps]
sources=load(HERE/'fixture-source-identities.json')['files'];assert len(sources)==1
for row in sources:assert sha(HERE/row['path'])==row['sha256Bytes']
pins=[];all_entries=set();remaining=set(PIN_NAMES)
for row in deps:
    with zipfile.ZipFile(ext(row['path'])) as jar:
        entries=set(jar.namelist());all_entries.update(entries)
        for name in PIN_NAMES:
            entry=name.replace('.','/')+'.class'
            if name in remaining and entry in entries:
                assert str(Path(row['path']).resolve()).casefold() in product_paths,(name,'nonproduct override')
                pins.append({'class':name,'codeSource':row['path'],'sha256Bytes':hashlib.sha256(jar.read(entry)).hexdigest(),'preparedOverlay':False})
                remaining.remove(name)
assert not remaining,('Actual new Main missing native/share/UI pins',remaining)
if a.phase=='verify':print('PASS supplied immutable actual Main and exact 89 ordered CP; no execution');sys.exit(0)
compiler_module=HERE.parent/'source9-appearance/compile-miuix.py'
spec=importlib.util.spec_from_file_location('native_actual_ui_compiler',compiler_module);c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=HERE/'runs'/a.attempt;fixture=out/'fixture-only.jar'
if a.phase=='compile':
    assert not out.exists(),'Use a fresh immutable attempt'
    out.mkdir(parents=True);frozen=out/'frozen-fixture-sources';frozen.mkdir()
    for file in [HERE/r['path'] for r in sources]+[HERE/'runner.py',HERE/'fixture-source-identities.json',HERE/'README.md']:
        ext(frozen/file.name).write_bytes(ext(file).read_bytes())
    kotlin=next(r['path'] for r in product if 'kotlin' in r['source'])
    values=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xfriend-paths='+kotlin,
      '-Xplugin='+str(c.PLUGIN),'-module-name','native_share_actual_product_ui_proof','-d',str(fixture)]+[str(HERE/r['path']) for r in sources]
    file=out/'compiler.args';args_file(file,values)
    r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
       'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    write(out/'compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
    with zipfile.ZipFile(ext(fixture)) as jar:
        entries=jar.namelist();classes=[x for x in entries if x.endswith('.class')]
        assert classes and not (set(classes)&all_entries),'Fixture shadows actual product/dependency'
        assert all(x.startswith('com/bilipai/desktop/diagnostics/nativeproductuiproof/') for x in classes)
        save(out/'fixture-class-identities.json',[{'entry':x,'sha256Bytes':hashlib.sha256(jar.read(x)).hexdigest()} for x in entries])
    save(out/'product-class-pins.json',pins)
    save(out/'compile-evidence.json',{'passed':True,'productionOverrides':0,'productClosureCompiled':False,
      'snapshot':str(a.snapshot),'snapshotSha256Bytes':a.snapshot_sha,'runtimeCp':str(a.runtime_cp),'runtimeCpSha256Bytes':a.runtime_cp_sha,
      'orderedDependencies':deps,'fixtureSources':sources,'fixtureJarSha256Bytes':sha(fixture),'compilerModuleSha256Bytes':sha(compiler_module),
      'compilerArtifacts':[{'path':str(f),'sha256Bytes':sha(f)} for f in list(c.COMPILER)+[c.PLUGIN]],'MainExecuted':False,'sharedGradle':False})
    print('PASS task-only fixture compilation with zero emitted class overlap');sys.exit(0)
compiled=load(out/'compile-evidence.json')
assert compiled['snapshotSha256Bytes']==a.snapshot_sha and compiled['runtimeCpSha256Bytes']==a.runtime_cp_sha
assert compiled['orderedDependencies']==deps and compiled['fixtureSources']==sources
assert sha(fixture)==compiled['fixtureJarSha256Bytes']
assert not (out/'proof').exists(),'Do not overwrite proof or failures'
proof=out/'proof';proof.mkdir()
sandbox=Path(tempfile.mkdtemp(prefix='bp-ns-ui-'))
assert len(str(sandbox/'.skiko'/('skiko-windows-x64-'+'0'*64)/'icudtl.dat'))<240
env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
    directory=sandbox/key.lower();directory.mkdir();env[key]=str(directory)
save(out/'task-owned-environment.json',{'path':str(sandbox),'shortNativeExtractionPath':True,'noAccountData':True})
values=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true',
 '-Djava.security.manager=allow','-Duser.home='+str(sandbox),'-Djava.io.tmpdir='+str(sandbox/'temp'),'-cp',';'.join([str(fixture)]+cp),
 'com.bilipai.desktop.diagnostics.nativeproductuiproof.NativeShareProductUiFixtureKt',str(proof),str(out/'product-class-pins.json')]
file=out/'ui.args';args_file(file,values)
r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=240)
write(out/'ui.log',r.stdout+r.stderr);print(r.stdout+r.stderr)
if r.returncode:
    save(proof/'runner-failure.json',{'passed':False,'exitCode':r.returncode,'snapshotSha256Bytes':a.snapshot_sha,'productionOverrides':0,'syntheticNativeTransport':True})
    r.check_returncode()
ui=load(proof/'ui.json');assert ui['passed'] and len(ui['checks'])==18
assert ui['socketAndProcessFenceInstalled'] and not ui['deniedConnectionsOrProcesses']
matrix=[x for x in ui['checks'] if x['case'] in ['TERMINAL','UNAVAILABLE','MARKER_RETRY','DISMISS']]
assert len(matrix)==16 and {(x['style'],x['themeMode']) for x in matrix}=={
 ('MATERIAL3','LIGHT'),('MATERIAL3','DARK'),('MIUIX','LIGHT'),('MIUIX','DARK')}
assert all(x['passed'] and x['lifecycleDrainedBeforeStoreFreeze'] and x['chooserCalls']==0 for x in ui['checks'])
for row in deps:assert sha(row['path'])==row['sha256Bytes']
save(proof/'accepted-evidence.json',{'passed':True,'productionOverrides':0,'productClosureCompiled':False,
 'productManifestSha256Bytes':a.snapshot_sha,'orderedRuntimeCpSha256Bytes':a.runtime_cp_sha,'orderedDependencies':deps,
 'fixtureJarSha256Bytes':sha(fixture),'actualLoadedClasses':len(pins),'matrixCells':4,'focusedCases':18,
 'pointerPairs':sum(x['pointerPairs'] for x in ui['checks']),'screenshots':sum(x['screenshots'] for x in ui['checks']),
 'originalPromptPointerSHAREAndDISMISS':True,'realMarkerDeleteFailureAndPointerRetry':True,'actualViewerReadAndClear':True,
 'explicitSyntheticCompletionAndLateFailure':True,'actualLifecycleBeforeStoreFreeze':True,'syntheticNativeTransport':True,
 'allExternalSocketsAndChildProcessesFenced':True,'HWND':False,'ShareUI':False,'receiver':False,
 'MainRootWindowExecuted':False,'packagedDllVerified':False,'packageAcceptance':False,'sharedGradle':False})
print('PASS actual Main focused native share UI acceptance, zero product overrides; explicit synthetic native platform events only')
