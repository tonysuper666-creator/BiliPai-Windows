from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding="utf-8",errors="replace")
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())
ATTEMPT=sys.argv[1]
OUT=HERE/('proof-'+ATTEMPT)
SNAPSHOT=REPO/'desktop/.local/native-share-main-product-snapshot-01'
EDITOR=REPO/'desktop/.local/dynamic-editor-detail-parity/final-production-safe'
PARENT=HERE.parent
def safe(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def read(path):return safe(path).read_text(encoding='utf-8')
def write(path,text):
    assert HERE in Path(path).parents
    safe(path.parent).mkdir(parents=True,exist_ok=True)
    safe(path).write_text(text,encoding='utf-8',newline='\n')
assert not safe(OUT).exists()
safe(OUT).mkdir()
assert sha(SNAPSHOT/'manifest.json')=='4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f'
assert sha(SNAPSHOT/'ordered-runtime-cp.json')=='a733a3435e18c5dac83ff155023c36e6d0d1997822b0a6b77ba7d4efeff63fcc'
deps=json.loads(read(SNAPSHOT/'ordered-runtime-cp.json'))
assert len(deps)==89
deps+=json.loads(read(EDITOR/'dependency-identities.json'))[89:]
assert len(deps)==90
for dep in deps:assert sha(dep['path'])==dep['sha256Bytes'],dep['path']
write(OUT/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('reply_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
inputs=[Path(str(p).removeprefix('\\\\?\\')) for p in safe(HERE/'generated').rglob('*.kt')]
ops=PARENT/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
inputs+=[ops,HERE/'ReplyProtocolTransportFixture.kt',HERE/'RawReplyParserFixture.kt']
identities=[dict(path=str(path),sha256Bytes=sha(path)) for path in inputs]
assert len(inputs)==6
assert read(ops).replace('\r\n','\n').find(read(HERE/'DesktopDynamicCommentOperations.fragment.kt').replace('\r\n','\n').strip())>=0
cp=[dep['path'] for dep in deps]
classes=OUT/'classes';safe(classes).mkdir()
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),
    '-Xplugin='+str(compiler.PLUGIN),'-Xfriend-paths='+str(SNAPSHOT/'main-kotlin.jar'),
    '-module-name','com_bilipai_desktop_bilipai_windows','-d',str(classes)]+[str(p) for p in inputs]
write(OUT/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
compiled=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compiler.args')],
    capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=65)
write(OUT/'compile.log',compiled.stdout+compiled.stderr)
print(compiled.stdout+compiled.stderr)
evidence=dict(prepared=True,compilePassed=compiled.returncode==0,snapshotManifestSha256Bytes=sha(SNAPSHOT/'manifest.json'),
    orderedRuntimeCpSha256Bytes=sha(SNAPSHOT/'ordered-runtime-cp.json'),orderedRuntimeCpCount=89,
    extraTestDependency=deps[-1],sources=identities,classesPath=str(classes),MainIntegration=False,
    explicitPreparedProductOverride=['com.bilipai.desktop.data.DesktopDynamicCardOperations'],
    copiedOperations=False,editorMultipartBodyComesFromPreparedParentOperations=True,
    sharedGradle=False,networkSockets=False,HWND=False,realAccount=False,executed=False)
write(OUT/'evidence.json',json.dumps(evidence,indent=2)+'\n')
compiled.check_returncode()
for identity in identities:assert sha(identity['path'])==identity['sha256Bytes']
runtime=[str(compiler.JAVA),'-Dfile.encoding=UTF-8','-cp',str(classes)+';'+';'.join(cp),
    'com.bilipai.desktop.data.ReplyProtocolTransportFixtureKt',str(OUT/'result.json')]
write(OUT/'run-command.json',json.dumps(runtime,indent=2)+'\n')
try:
    run=subprocess.run(runtime,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35)
except subprocess.TimeoutExpired as timeout:
    def text(value):return value.decode('utf-8',errors='replace') if isinstance(value,bytes) else value or ''
    write(OUT/'run.log',text(timeout.stdout)+text(timeout.stderr)+'\nTIMEOUT: 35 seconds\n')
    evidence.update(executed=True,runtimePassed=False,runtimeTimedOut=True,runLogSha256Bytes=sha(OUT/'run.log'))
    write(OUT/'evidence.json',json.dumps(evidence,indent=2)+'\n')
    print(read(OUT/'run.log'));sys.exit(1)
write(OUT/'run.log',run.stdout+run.stderr);print(run.stdout+run.stderr)
evidence['executed']=True;evidence['runtimePassed']=run.returncode==0
evidence['compileLogSha256Bytes']=sha(OUT/'compile.log')
evidence['runLogSha256Bytes']=sha(OUT/'run.log')
if safe(OUT/'result.json').exists():evidence['resultSha256Bytes']=sha(OUT/'result.json')
evidence['classes']=[dict(path=str(p).removeprefix('\\\\?\\'),sha256Bytes=sha(p)) for p in safe(classes).rglob('*.class')]
for identity in identities:assert sha(identity['path'])==identity['sha256Bytes']
write(OUT/'evidence.json',json.dumps(evidence,indent=2)+'\n')
sys.exit(run.returncode)

