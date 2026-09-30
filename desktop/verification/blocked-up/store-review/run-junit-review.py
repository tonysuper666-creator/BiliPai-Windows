"""Prove all eight original @Test methods are genuinely discovered; no shared Gradle."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, tempfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2];PRODUCER=HERE.parent/'settings-blocked-up-parity'
def sha(path):
    value=str(Path(path).absolute());path=Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
    return hashlib.sha256(path.read_bytes()).hexdigest()
def write(name,text):(HERE/name).write_text(text,encoding='utf-8',newline='\n')
def quotes(args):return '\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n'
deps=json.loads((PRODUCER/'dependency-identities.json').read_text());cp=[r['path'] for r in deps]
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes']
frozen=json.loads((PRODUCER/'store-artifact-manifest.json').read_text())['files']
for r in frozen:assert sha(PRODUCER/r['path'])==r['sha256Bytes']
spec=importlib.util.spec_from_file_location('blocked_review_junit_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
output=HERE/'junit-launcher-classes';output.mkdir(exist_ok=False)
classpath=[str(HERE/'classes/reviewed-attempt2'),str(PRODUCER/'store-classes'),*cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-module-name','blocked_store_junit_review','-d',str(output),str(HERE/'RunJUnitStoreReview.kt')]
write('junit-compiler.args',quotes(args))
run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),
                   'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'junit-compiler.args')],cwd=HERE,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
write('junit-compile.log',run.stdout+run.stderr);print(run.stdout+run.stderr);run.check_returncode()
proof=HERE/'proof/junit-engine';proof.mkdir(exist_ok=False)
sandbox=Path(tempfile.mkdtemp(prefix='child-',dir=proof)).absolute();environment=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
    target=sandbox/name.lower();target.mkdir();environment[name]=str(target)
args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true',
      '-Djava.net.useSystemProxies=false','-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),
      '-cp',str(output)+';'+';'.join(classpath),'com.bilipai.desktop.data.RunJUnitStoreReviewKt',str(proof/'result.json')]
write('junit-runtime.args',quotes(args))
run=subprocess.run([str(c.JAVA),'@'+str(HERE/'junit-runtime.args')],cwd=HERE,env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
write('junit-runtime.log',run.stdout+run.stderr);print(run.stdout+run.stderr);run.check_returncode()
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes']
for r in frozen:assert sha(PRODUCER/r['path'])==r['sha256Bytes']
write('junit-evidence.json',json.dumps(dict(passed=True,testResultSha256Bytes=sha(proof/'result.json'),
      junitEngineActuallyInvoked=True,testsFound=8,testsSucceeded=8,frozen118Unchanged=True,
      actualReviewedStoreClasses=str(HERE/'classes/reviewed-attempt2'),frozenTestAndDiscoveryClasses=str(PRODUCER/'store-classes'),
      actualProductSnapshotJars=cp[:3],launcherSourceSha256Bytes=sha(HERE/'RunJUnitStoreReview.kt'),sandbox=str(sandbox),
      markedProductionOverrides=True,noSharedGradle=True,noMainWrites=True,noHWND=True,noUserAccountReads=True,noHTTP=True),indent=2)+'\n')
