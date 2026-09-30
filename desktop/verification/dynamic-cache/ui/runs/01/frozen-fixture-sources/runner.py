"""Fixture-only compile and UI proof; never builds or overrides production classes."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,subprocess,sys,zipfile,tempfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent

def ext(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)

def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def write(path,value):
    ext(path.parent).mkdir(parents=True,exist_ok=True)
    ext(path).write_text(value,encoding='utf-8',newline='\n')
def save(path,value):write(path,json.dumps(value,indent=2,ensure_ascii=False)+'\n')
def args_file(path,values):write(path,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
def load(path):return json.loads(ext(path).read_text(encoding='utf-8'))

PIN_NAMES=['com.bilipai.desktop.appearance.DesktopAppearanceThemeKt', 'com.bilipai.desktop.plugins.DesktopPluginStore', 'com.bilipai.desktop.data.DesktopDynamicCache', 'com.bilipai.desktop.data.DesktopDynamicCacheSession', 'com.bilipai.desktop.data.DesktopSessionStore', 'com.bilipai.desktop.data.DesktopRepository', 'com.bilipai.desktop.data.DesktopCommunityRepository', 'com.bilipai.desktop.ui.DesktopDynamicCacheBindingsKt', 'com.bilipai.desktop.ui.CommunityDynamicScreensKt', 'com.bilipai.desktop.ui.CommunityUiSupportKt', 'com.bilipai.desktop.ui.DesktopDynamicTimelineState', 'com.bilipai.desktop.ui.DesktopDynamicTabsHostKt', 'com.bilipai.desktop.ui.DesktopDynamicUsersState', 'com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicCacheKeys', 'com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicUserStatePolicyKt', 'com.android.purebilibili.data.repository.DesktopOriginalDynamicTimelineRepository', 'com.android.purebilibili.core.network.DynamicApi', 'com.android.purebilibili.core.network.BilibiliApi']
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('phase',choices=['verify','compile','run'])
parser.add_argument('--snapshot',type=Path,required=True)
parser.add_argument('--snapshot-sha',required=True)
parser.add_argument('--runtime-cp',type=Path,required=True)
parser.add_argument('--attempt',default='01')
a=parser.parse_args()
assert a.attempt.isalnum(), 'Attempt must be a plain local identifier'
assert sha(a.snapshot)==a.snapshot_sha, 'Wrong immutable product snapshot'
snapshot=load(a.snapshot)
product=snapshot['artifacts']
assert len(product)==3, 'Requires the actual immutable Kotlin, Java, resources JARs'
for row in product:
    assert Path(row['path']).suffix=='.jar'
    assert sha(row['path'])==row['sha256Bytes'],row['path']
    if 'entries' in row:
        with zipfile.ZipFile(ext(row['path'])) as jar:assert len(jar.namelist())==row['entries']
raw_cp=load(a.runtime_cp)
deps=raw_cp if isinstance(raw_cp,list) else raw_cp['entries']
assert deps and all(isinstance(r,dict) and 'path' in r and 'sha256Bytes' in r for r in deps)
# The Root export must retain Gradle order. Production directories are replaced by
# their corresponding immutable JARs in that export, never by local prepared code.
for row in deps:
    assert Path(row['path']).suffix=='.jar', 'Ordered CP must contain immutable JARs only'
    assert sha(row['path'])==row['sha256Bytes'],row['path']
product_paths={str(Path(r['path']).resolve()).casefold() for r in product}
actual_paths=[str(Path(r['path']).resolve()).casefold() for r in deps]
assert all(p in actual_paths for p in product_paths), 'Actual product artifacts missing from CP'
cp=[r['path'] for r in deps]
source_record=load(HERE/'fixture-source-identities.json')
for row in source_record['files']:assert sha(HERE/row['path'])==row['sha256Bytes']
assert len(source_record['files'])==2, 'Only the two fixture Kotlin files may compile'
sources=[HERE/row['path'] for row in source_record['files']]
compiler_module=HERE.parent/'source9-appearance/compile-miuix.py'
spec=importlib.util.spec_from_file_location('product_fixture_compiler',compiler_module)
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)

# Locate and pin classes in the first actual runtime CP JAR, not in the compiler's
# dependency set. Every production pin must originate in one of the immutable 3 JARs.
pins=[];all_entries=set()
remaining=set(PIN_NAMES)
for row in deps:
    with zipfile.ZipFile(ext(row['path'])) as jar:
        entries=set(jar.namelist());all_entries.update(entries)
        for name in PIN_NAMES:
            entry=name.replace('.','/')+'.class'
            if name in remaining and entry in entries:
                assert str(Path(row['path']).resolve()).casefold() in product_paths, ('Production class loaded from external override',name,row['path'])
                pins.append({'class':name,'codeSource':row['path'],'sha256Bytes':hashlib.sha256(jar.read(entry)).hexdigest(),'preparedOverlay':False})
                remaining.remove(name)
assert not remaining, ('Missing actual product classes',remaining)
assert len(pins)==18
if a.phase=='verify':
    print('PASS immutable 3-JAR identities and exact ordered runtime CP; no fixture execution')
    sys.exit(0)

out=HERE/'runs'/a.attempt
fixture=out/'fixture-only.jar'
if a.phase=='compile':
    assert not out.exists(), 'Each attempt is immutable; use a fresh attempt name'
    out.mkdir(parents=True)
    kotlin_jar=next(r['path'] for r in product if 'kotlin' in r['source'])
    values=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),
      '-Xfriend-paths='+kotlin_jar,'-Xplugin='+str(c.PLUGIN),
      '-module-name','dynamic_cache_product_ui_proof','-d',str(fixture)]+list(map(str,sources))
    file=out/'compiler.args';args_file(file,values)
    r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
      'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    write(out/'compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
    with zipfile.ZipFile(ext(fixture)) as jar:
        fixture_entries=jar.namelist()
        classes=[x for x in fixture_entries if x.endswith('.class')]
        assert classes
        assert not (set(classes)&all_entries), 'Fixture shadows a production/dependency class'
        assert all(x.startswith('com/bilipai/desktop/ui/productcacheproof/') for x in classes), 'Production package emitted in fixture'
        fixture_identity=[{'entry':x,'sha256Bytes':hashlib.sha256(jar.read(x)).hexdigest()} for x in fixture_entries]
    save(out/'fixture-class-identities.json',fixture_identity)
    save(out/'product-class-pins.json',pins)
    save(out/'compile-evidence.json',{'passed':True,'productionOverrides':0,'productClosureCompiled':False,
      'snapshot':str(a.snapshot),'snapshotSha256Bytes':a.snapshot_sha,'runtimeCp':str(a.runtime_cp),
      'runtimeCpSha256Bytes':sha(a.runtime_cp),'orderedDependencies':deps,
      'fixtureSources':source_record['files'],'fixtureJar':str(fixture),'fixtureJarSha256Bytes':sha(fixture),
      'compilerModuleSha256Bytes':sha(compiler_module),'compilerArtifacts':[
        {'path':str(p),'sha256Bytes':sha(p)} for p in list(c.COMPILER)+[c.PLUGIN]],
      'HWND':False,'sharedGradle':False})
    print('PASS compiled only 2 fixture .kt files; zero class overlap')
    sys.exit(0)

compiled=load(out/'compile-evidence.json')
assert compiled['snapshotSha256Bytes']==a.snapshot_sha
assert compiled['runtimeCpSha256Bytes']==sha(a.runtime_cp)
assert compiled['orderedDependencies']==deps
assert sha(fixture)==compiled['fixtureJarSha256Bytes']
assert not (out/'proof').exists(), 'Do not overwrite prior proof results'
proof=out/'proof';proof.mkdir()
sandbox=Path(tempfile.mkdtemp(prefix='bp-dc-ui-'))
assert len(str(sandbox / '.skiko' / ('skiko-windows-x64-' + '0'*64) / 'icudtl.dat')) < 240
save(out/'task-owned-environment.json',{'path':str(sandbox),'shortNativeExtractionPath':True,
 'actualEnvironmentOnly':True,'reason':'Keep isolated native ICU extraction below legacy Windows native path limit.'})
env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
    directory=sandbox/key.lower();directory.mkdir();env[key]=str(directory)
base=['--add-modules=jdk.httpserver','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8',
 '-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(sandbox),
 '-Djava.io.tmpdir='+str(sandbox/'temp'),'-cp',';'.join([str(fixture)]+cp)]

def execute(name,main,values,timeout):
    file=out/(name+'.args');args_file(file,base+[main]+values)
    r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    write(out/(name+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr)
    return r

cold_seeds=[]
for style in ['material3','miuix']:
    for mode in ['light','dark']:
        name=style+'-'+mode;root=out/'cold-roots'/name
        r=execute('seed-'+name,'com.bilipai.desktop.ui.productcacheproof.ColdCacheFixtureKt',['seed',root],20)
        r.check_returncode();cold_seeds.append({'cell':name,'separateJvm':True,'passed':True,'path':str(root)})
r=execute('ui','com.bilipai.desktop.ui.productcacheproof.UiFixtureKt',[proof,out/'product-class-pins.json'],220)
if r.returncode:
    save(proof/'runner-failure.json',{'passed':False,'exitCode':r.returncode,'snapshotSha256Bytes':a.snapshot_sha,
      'productionOverrides':0,'fixtureJarSha256Bytes':sha(fixture),'partialMatrix':str(proof/'ui.json'),'coldSeeds':cold_seeds})
    r.check_returncode()
ui=load(proof/'ui.json');assert ui['passed'] and len(ui['checks'])==4
assert {(r['style'],r['themeMode']) for r in ui['checks']}=={
 ('MATERIAL3','LIGHT'),('MATERIAL3','DARK'),('MIUIX','LIGHT'),('MIUIX','DARK')}
assert all(r['screenshots']==7 for r in ui['checks'])
assert sum(r['pointerPairs'] for r in ui['checks'])==32
cold=[]
for case in ui['checks']:
    name=case['style'].lower()+'-'+case['themeMode'].lower()
    r=execute('cold-guest-'+name,'com.bilipai.desktop.ui.productcacheproof.ColdCacheFixtureKt',['read-guest',case['coldRoot']],20)
    r.check_returncode();cold.append({'cell':name,'passed':True,'separateJvm':True,'root':case['coldRoot']})
assert len(list(proof.glob('*/*.png')))==28
# Recheck all immutable product and external dependency bytes after execution.
for row in deps:assert sha(row['path'])==row['sha256Bytes']
save(proof/'accepted-evidence.json',{'passed':True,'productionOverrides':0,'productClosureCompiled':False,
 'productManifestSha256Bytes':a.snapshot_sha,'orderedRuntimeCpSha256Bytes':sha(a.runtime_cp),
 'orderedDependencies':deps,'fixtureJarSha256Bytes':sha(fixture),'actualLoadedClasses':18,
 'matrixCells':4,'pointerPairs':32,'uiScreenshots':28,'independentSeedJvms':cold_seeds,'independentColdGuestReaderJvms':cold,
 'actualCommunityReady':True,'sameCookieJar':True,'notInterestedOriginalMenuInstalled':False,
 'fixtureSeamMarkButton':True,'taskOwnedReflectionTransport':True,'chainProceed':False,
 'HWND':False,'sharedGradle':False,'wholeMainOrRuntimeAcceptance':False,'liveAccountOrSocket':False})
print('PASS actual cache CommunityDynamicFeed 4-cell zero-override UI matrix; 32 pointer pairs; 28 screenshots; 8 independent cold JVMs')
