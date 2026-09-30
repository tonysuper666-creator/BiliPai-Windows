"""Compile three uniquely named fixtures against immutable product jars only."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
SNAPSHOT=HERE.parent/'dynamic-crash-main-product-snapshot/manifest.json'
SNAPSHOT_PIN='c01330bf9345f8f5d1be2fa70bce6d7c1c980179448ee22f2ad0f467292a6345'

def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_bytes(t.encode('utf-8'))
def args(p,items):write(p,'\n'.join('"'+str(i).replace('\\','/')+'"' for i in items))
def load(p,n):
    s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

p=argparse.ArgumentParser();p.add_argument('--run-name',required=True);p.add_argument('--worker-root',type=Path,required=True);o=p.parse_args()
assert o.run_name.replace('-','').isalnum()
assert sha(SNAPSHOT)==SNAPSHOT_PIN,'Immutable snapshot manifest changed'
snapshot=json.loads(read(SNAPSHOT));product=snapshot['artifacts'];assert len(product)==3
historical=json.loads(read(HERE.parent/'settings-diagnostics-parity/dependency-identities.json'))
dependencies=[dict(path=e['path'],sha256Bytes=e['sha256Bytes']) for e in product]+historical[3:]
assert len(dependencies)==234,'Exactly three product jars and 231 fixed runtime dependencies'
for e in dependencies:assert sha(e['path'])==e['sha256Bytes'],e['path']
cp=[e['path'] for e in dependencies]
sourcepins=[]
for e in snapshot['sourceFiles']+snapshot['generatedProductFiles']:
    assert sha(REPO/e['path'])==e['sha256Bytes'],e['path'];sourcepins.append(e)
copied=[('CrashPromptFixture.kt','diagnostics-crash-prompt-parity'),('DiagnosticProductLifecycleFixture.kt','diagnostics-final-product-lifecycle-proof')]
for name,lane in copied:assert sha(HERE/name)==sha(HERE.parent/lane/name),'Frozen fixture copy changed'
sources=[HERE/name for name in ['CrashPromptFixture.kt','DiagnosticProductLifecycleFixture.kt','RootCrashPromptFixture.kt']]
prefixes=['com/bilipai/desktop/diagnostics/crashproof/','com/bilipai/desktop/diagnostics/proof/','com/bilipai/desktop/diagnostics/rootcrashproof/']
for source,prefix in zip(sources,prefixes):assert ('package '+prefix[:-1].replace('/','.')) in read(source)
with zipfile.ZipFile(safe(cp[0])) as jar:
    names=set(jar.namelist());metadata=[n for n in names if n.startswith('META-INF/') and n.endswith('.kotlin_module')];assert len(metadata)==1
    module=Path(metadata[0]).stem
    for required in ['com/android/purebilibili/CrashLogPromptAction.class','com/android/purebilibili/DesktopCrashLogPromptPolicyKt.class',
                     'com/android/purebilibili/DesktopPendingCrashLogPromptKt.class','com/bilipai/desktop/diagnostics/DesktopCrashPromptController.class',
                     'com/bilipai/desktop/diagnostics/DesktopDiagnosticLifecycle.class','com/bilipai/desktop/diagnostics/DesktopCrashPromptHostKt.class']:
        assert required in names,'Actual product class missing: '+required
out=HERE/o.run_name;assert not out.exists(),'Evidence is never overwritten';out.mkdir();classes=out/'classes';classes.mkdir()
compiler=load(HERE.parent/'source9-appearance/compile-miuix.py','productcompiler')
scratch=HERE.parents[3]/'.local'/('cmp-'+o.run_name);scratch.mkdir(parents=True,exist_ok=True);environment=os.environ.copy()
for key in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
    value=scratch/key.lower();value.mkdir(exist_ok=True);environment[key]=str(value)
environment['JAVA_TOOL_OPTIONS']='-Duser.home='+str(scratch/'home').replace('\\','/')
def run(file,log,timeout=100):
    r=subprocess.run([str(compiler.JAVA),'@'+str(file)],env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    write(log,r.stdout+r.stderr);print((r.stdout+r.stderr)[-4500:],flush=True);r.check_returncode()
file=out/'compiler.args';args(file,['-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),
    '-Xplugin='+str(compiler.PLUGIN),'-Xfriend-paths='+cp[0],'-module-name',module,'-d',str(classes)]+sources)
run(file,out/'compile.log')
classfiles=list(safe(classes).rglob('*.class'))
assert classfiles
for path in classfiles:
    relative=path.relative_to(safe(classes)).as_posix();assert any(relative.startswith(prefix) for prefix in prefixes),relative
cases=[]
for case in ['disk','material3','miuix']:cases.append(('crash-'+case,'com.bilipai.desktop.diagnostics.crashproof.CrashPromptFixtureKt',[case]))
for case in ['startup','observers','accepted-writer','close-bypass','hook-failure','root-startup-material3','root-startup-miuix']:
    cases.append(('lifecycle-'+case,'com.bilipai.desktop.diagnostics.proof.DiagnosticProductLifecycleFixtureKt',[case]))
for style in ['MATERIAL3','MIUIX']:
    for action in ['share','dismiss','outside','pending-shutdown']:
        cases.append(('root-'+style.lower()+'-'+action,'com.bilipai.desktop.diagnostics.rootcrashproof.RootCrashPromptFixtureKt',[style,action]))
for case,entry,parameters in cases:
    destination=out/case;destination.mkdir();file=out/(case+'.args')
    args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.security.manager=allow','-Djava.io.tmpdir='+str(scratch/'temp'),
        '-XX:ErrorFile='+str(out/'hs_err_pid%p.log'),'-Dbilipai.js.workerResources='+str(o.worker_root.resolve()),
        '-cp',';'.join(cp[:3]+[str(classes)]+cp[3:]),entry]+parameters+[str(destination)])
    run(file,out/(case+'.log'))
    result=json.loads(read(destination/'result.json'));assert result['passed'],case
    print('SCENARIO PASS '+case+' checks='+str(len(result['checks'])),flush=True)
write(out/'dependency-identities.json',json.dumps(dependencies,indent=2)+'\n')
write(out/'compile-evidence.json',json.dumps(dict(passed=True,snapshotManifestSha256Bytes=SNAPSHOT_PIN,productJarPins=dependencies[:3],
    productJarsFirstInCompilerAndRuntimeClasspath=True,moduleName=module,sourceAndGeneratedPins=sourcepins,
    compiledFixtureSourceCount=3,compiledFixtureClasses=len(classfiles),compiledPackages=prefixes,
    fixtureSources=[dict(path=str(s.relative_to(HERE)),sha256Bytes=sha(s)) for s in sources],
    noProductionSourcesCompiled=True,noProductClassOverrides=True,noShadowRenderer=True,
    freshJvmPerScenario=True,scenarioCount=len(cases),nativeWindowCreated=False,sharedGradleInvoked=False,
    realAccountOrHttpUsed=False,rootOSChooserClicked=False,workerResources=str(o.worker_root.resolve())),indent=2)+'\n')
