"""New independent delta after the frozen eight-target plan. No edits to that cohort or main."""
from pathlib import Path
import difflib, hashlib, importlib.util, json, os, subprocess, sys, tempfile
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REVIEW=HERE.parent;ROOT=HERE.parents[3]
PRODUCER=REVIEW.parent/'settings-blocked-up-parity'
BASE_BLOCKED='188189ca9b0e009dda2fbca44d59fab7ca64ce33e93da87e9c5c68d3d87e90bf'
def safe(p):
    value=str(Path(p).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(name,data):
    p=HERE/name;safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_bytes(data.encode() if isinstance(data,str) else data)
def quotes(args):return '\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n'
def verify_previous():
    manifest=REVIEW/'frozen-handoff.json'
    assert sha(manifest)=='3f1d0f4bf92c750b4a3aade45b74bc45e640fd4dc18836b214901289f376b6df'
    for r in json.loads(manifest.read_text())['files']:assert sha(REVIEW/r['path'])==r['sha256Bytes'],r['path']
    for r in json.loads((PRODUCER/'store-artifact-manifest.json').read_text())['files']:assert sha(PRODUCER/r['path'])==r['sha256Bytes'],r['path']
verify_previous()
store_relative='desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt'
blocked_relative='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBlockedUpStore.kt'
store_original=safe(ROOT/store_relative).read_bytes()
store=store_original.decode();newline='\r\n' if '\r\n' in store else '\n'
anchor='    internal fun preferences(name: String): JsonObject = synchronized(backing) {'
assert store.count(anchor)==1
helper='''    /** Validate the cached generation; never reload disk or revive a frozen backing. */
    internal fun requireObjectNamespace(name: String) = synchronized(backing) {
        val namespace = backing.document[name]
        require(namespace == null || namespace is JsonObject) { "设置命名空间格式无效" }
    }

'''.replace('\n',newline)
store=store.replace(anchor,helper+anchor,1)
blocked_path=REVIEW/'payload'/blocked_relative
assert sha(blocked_path)==BASE_BLOCKED
blocked_original=blocked_path.read_bytes();blocked=blocked_original.decode()
anchor='    private fun readRecords(): List<BlockedUp> {\n'
assert blocked.count(anchor)==1
blocked=blocked.replace(anchor,anchor+'        context.store.requireObjectNamespace(NAMESPACE)\n',1)
write('payload/'+store_relative,store);write('payload/'+blocked_relative,blocked)
for relative,old,new in [(store_relative,store_original.decode(),store),(blocked_relative,blocked_original.decode(),blocked)]:
    write('patches/'+Path(relative).name+'.patch',''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='after-stage1/'+relative,tofile='strict-review/'+relative)))
deps=json.loads((PRODUCER/'dependency-identities.json').read_text());cp=[r['path'] for r in deps]
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes']
spec=importlib.util.spec_from_file_location('strict_cached_namespace_compiler',REVIEW.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
serial=c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
variants=[]
for variant in ['original','strict']:
    output=HERE/'classes'/variant;safe(output).mkdir(parents=True,exist_ok=False)
    sources=[HERE/'StrictNamespaceFixture.kt']
    if variant=='strict':sources+=[HERE/'payload'/store_relative,HERE/'payload'/blocked_relative]
    classpath=[str(REVIEW/'classes/reviewed-attempt2'),str(PRODUCER/'store-classes'),*cp]
    args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xplugin='+str(serial),
          '-Xfriend-paths='+','.join(classpath[:5]),'-module-name','strict_namespace_review','-d',str(output),*map(str,sources)]
    write(variant+'-compiler.args',quotes(args))
    run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/(variant+'-compiler.args'))],cwd=HERE,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    write(variant+'-compile.log',run.stdout+run.stderr);print(run.stdout+run.stderr);run.check_returncode()
    proof=HERE/'proof'/variant;safe(proof).mkdir(parents=True,exist_ok=False)
    sandbox=Path(tempfile.mkdtemp(prefix='child-',dir=proof)).absolute();env=os.environ.copy()
    for key in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
        target=sandbox/key.lower();target.mkdir();env[key]=str(target)
    args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true',
          '-Djava.net.useSystemProxies=false','-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),
          '-cp',str(output)+';'+';'.join(classpath),'com.bilipai.desktop.data.StrictNamespaceFixtureKt',variant,str(proof/'result.json')]
    write(variant+'-runtime.args',quotes(args))
    run=subprocess.run([str(c.JAVA),'@'+str(HERE/(variant+'-runtime.args'))],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    write(variant+'-runtime.log',run.stdout+run.stderr);print(run.stdout+run.stderr);run.check_returncode()
    variants.append(dict(variant=variant,passed=True,sourceSha256Bytes={str(p):sha(p) for p in sources},sandbox=str(sandbox),
                         markedRootStoreAndBlockedStoreOverrides=variant=='strict',currentProductJars=cp[:3]))
verify_previous()
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes']
assert sha(ROOT/store_relative)==hashlib.sha256(store_original).hexdigest()
write('evidence.json',json.dumps(dict(passed=True,variants=variants,uniqueBoundaryChecks=5,
    originalNamespacePrimitiveOverwriteVerified=True,strictCachedValidationPreservesMalformedBytes=True,
    priorReview137AndProducer118Unchanged=True,mainEdited=False,sharedGradleInvoked=False,nativeWindowCreated=False,
    userAccountReads=False,networkRequests=False),ensure_ascii=False,indent=2)+'\n')
write('delta-contract.json',json.dumps(dict(status='frozen',appliesAfterInstallerPlanSha256Bytes='19ca03407d64e4c4fce848aac01c7b2b88eb8f884ae49c9795e031c9b8258125',
    files=[dict(path=store_relative,baseSha256Bytes=hashlib.sha256(store_original).hexdigest(),payloadSha256Bytes=sha(HERE/'payload'/store_relative)),
           dict(path=blocked_relative,baseSha256Bytes=BASE_BLOCKED,payloadSha256Bytes=sha(HERE/'payload'/blocked_relative))],
    rootOwnsPluginStoreHelper=True,requiresNoAdditionalSourceIdentity=True,newDependencies=[],newResources=[],
    originalSchemaAndImportPoliciesUnchanged=True,noDiskReload=True,noFrozenGenerationRevival=True,
    mainEdited=False,installerPlanChanged=False),ensure_ascii=False,indent=2)+'\n')
print('Strict cached namespace review original + corrected five boundary cases PASS; prior 137 + producer118 unchanged.')
