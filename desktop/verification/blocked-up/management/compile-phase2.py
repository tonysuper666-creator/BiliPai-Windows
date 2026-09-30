from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,tempfile,sys
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;STAGE1=HERE.parent;ROOT=STAGE1.parents[2]
def ext(p):
    name=str(p.absolute());return Path(name if name.startswith('\\\\?\\') else '\\\\?\\'+name)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(n,t):(HERE/n).write_text(t,encoding='utf-8',newline='\n')
for p in [HERE/'compile-evidence.json',HERE/'proof/result.json',HERE/'ui-proof/result.json']:
    if p.exists():p.unlink()
snapshot=STAGE1.parent/'blocked-up-foundation-product-snapshot/manifest.json'
assert sha(snapshot)=='74165f272e012c1a13e9171e963bf76127dbd71e7003da9e2576b7af2a25e2e6'
product=json.loads(snapshot.read_text())
deps=[dict(path=r['path'],sha256Bytes=r['sha256Bytes']) for r in product['artifacts']]+json.loads((STAGE1/'dependency-identities.json').read_text())[3:]
for row in deps:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
cp=[r['path'] for r in deps]
write('dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('blocked_stage2_compiler',STAGE1.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared').rglob('*.kt'))
if (HERE/'RunBlockedManagementFixture.kt').exists():sources.append(HERE/'RunBlockedManagementFixture.kt')
if (HERE/'BlockedManagementComposeFixture.kt').exists():sources.append(HERE/'BlockedManagementComposeFixture.kt')
output=HERE/'classes-foundation-product';output.mkdir(exist_ok=True)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),
      '-Xplugin='+str(c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')),
      '-Xfriend-paths='+','.join(cp[:3]),'-module-name','blocked_up_management_stage','-d',str(output),*map(str,sources)]
write('compiler.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'compiler.args')],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write('compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-9000:]);run.check_returncode()
evidence=dict(passed=True,compiledSourceIdentities={p.relative_to(HERE).as_posix():sha(p) for p in sources},
    sharedGradleInvoked=False,mainEdited=False,productionOverrides=[r['path'] for r in json.loads((HERE/'owned-base-files.json').read_text())],
    parentStoreStage1ContractSha256=sha(STAGE1/'store-contract.json'),snapshotSha256=sha(snapshot),productSnapshot=str(snapshot),
    compiledOutput='classes-foundation-product',additionalFixtureOnlyProductionOverrides=[],
    foundationRuntimeAndRestoreFenceNotOverridden=True)
if (HERE/'RunBlockedManagementFixture.kt').exists():
    proof=HERE/'proof';proof.mkdir(exist_ok=True);appdata=Path(tempfile.mkdtemp(prefix='appdata-',dir=proof)).absolute()
    env=os.environ.copy();env['LOCALAPPDATA']=str(appdata)
    args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(output)+';'+';'.join(cp),
        'com.bilipai.desktop.data.RunBlockedManagementFixtureKt',str(proof/'result.json')]
    write('runtime.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
    run=subprocess.run([str(c.JAVA),'@'+str(HERE/'runtime.args')],cwd=ROOT,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=70)
    write('runtime.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-9000:]);run.check_returncode()
    evidence.update(normalRuntimeExit=True,childLocalAppData=str(appdata),runtimeReportSha256=sha(proof/'result.json'))
    if (HERE/'BlockedManagementComposeFixture.kt').exists():
        args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(output)+';'+str(HERE/'prepared/desktop/src/main/resources')+';'+';'.join(cp),
            'com.bilipai.desktop.data.BlockedManagementComposeFixtureKt',str(HERE/'ui-proof')]
        write('ui-runtime.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
        run=subprocess.run([str(c.JAVA),'@'+str(HERE/'ui-runtime.args')],cwd=ROOT,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
        write('ui-runtime.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-9000:]);run.check_returncode()
        evidence.update(normalComposeRuntimeExit=True,composeReportSha256=sha(HERE/'ui-proof/result.json'))
for row in deps:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
write('compile-evidence.json',json.dumps(evidence,indent=2)+'\n')
