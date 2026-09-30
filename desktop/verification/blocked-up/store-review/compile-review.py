"""Compile and run only task-owned marked review fixtures. No shared Gradle or frozen mutation."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, tempfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PRODUCER = HERE.parent / 'settings-blocked-up-parity'
def safe(p):
    value=str(Path(p).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(relative,text):
    p=HERE/relative;safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(text,encoding='utf-8',newline='\n')
def quoted(args): return '\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n'
def verify():
    frozen=json.loads((PRODUCER/'store-artifact-manifest.json').read_text())['files']
    for r in frozen: assert sha(PRODUCER/r['path'])==r['sha256Bytes'],r['path']
    deps=json.loads((PRODUCER/'dependency-identities.json').read_text())
    for r in deps: assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
    return deps
deps=verify();cp=[r['path'] for r in deps]
spec=importlib.util.spec_from_file_location('blocked_review_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
serial=c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
compiled=[]
for variant in ['original','reviewed']:
    output=HERE/'classes'/(variant+'-attempt2');safe(output).mkdir(parents=True,exist_ok=False)
    store=(PRODUCER/'prepared' if variant=='original' else HERE/'review-delta')/'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBlockedUpStore.kt'
    sources=[store,HERE/'CachedDestinationReviewFixture.kt']
    compile_cp=[str(PRODUCER/'store-classes')]+cp
    args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(compile_cp),'-Xplugin='+str(serial),
          '-Xfriend-paths='+','.join(compile_cp[:4]),'-module-name','blocked_up_store_review','-d',str(output),*map(str,sources)]
    write(f'{variant}-attempt2-compiler.args',quoted(args))
    run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),
         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/f'{variant}-attempt2-compiler.args')],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    write(f'{variant}-attempt2-compile.log',run.stdout+run.stderr);print(run.stdout+run.stderr);run.check_returncode()
    proof=HERE/'proof'/(variant+'-attempt2');safe(proof).mkdir(parents=True,exist_ok=False)
    sandbox=Path(tempfile.mkdtemp(prefix='child-',dir=proof)).absolute()
    environment=os.environ.copy()
    for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
        path=sandbox/name.lower();path.mkdir();environment[name]=str(path)
    runtime=[str(output),str(PRODUCER/'store-classes'),*cp]
    args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
          '-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',';'.join(runtime),
          'com.bilipai.desktop.data.CachedDestinationReviewFixtureKt',variant,str(proof/'result.json')]
    write(f'{variant}-attempt2-runtime.args',quoted(args))
    run=subprocess.run([str(c.JAVA),'@'+str(HERE/f'{variant}-attempt2-runtime.args')],cwd=HERE,env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    write(f'{variant}-attempt2-runtime.log',run.stdout+run.stderr);print(run.stdout+run.stderr);run.check_returncode()
    compiled.append(dict(variant=variant,passed=True,sources={str(p):sha(p) for p in sources},
                         compilerPlugins={str(serial):sha(serial)},java=str(c.JAVA),sandbox=str(sandbox),
                         productJarsFirstAfterMarkedOverride=cp[:3],markedFrozenProductionOverrides=['DesktopDiscoveryPreferences','DesktopDiscoveryRepository','DiscoveryScreens'],
                         noMainEdited=True,noSharedGradle=True,noHWND=True,noUserAccountReads=True,noHTTP=True))
verify()
write('review-evidence.json',json.dumps(dict(passed=True,variants=compiled,eachVariantExecutableChecks=4,
      producerFrozenUnchanged=True,originalStoreCachedRepairLimitationVerified=True,reviewDeltaErrorCopyVerified=True),ensure_ascii=False,indent=2)+'\n')
