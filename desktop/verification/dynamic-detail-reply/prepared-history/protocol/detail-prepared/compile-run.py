from pathlib import Path
import ast,hashlib,importlib.util,json,re,subprocess,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'.git').exists())
OUT=HERE/('proof-'+sys.argv[1]);PIN=ROOT/'desktop/.local/dynamic-editor-main-product-snapshot-01'
def safe(path):
    value=str(Path(path).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def read(path):return safe(path).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def write(path,text):
    assert HERE in Path(path).parents
    safe(path.parent).mkdir(parents=True,exist_ok=True)
    safe(path).write_text(text,encoding='utf-8',newline='\n')
assert not safe(OUT).exists();safe(OUT).mkdir()
assert sha(PIN/'manifest.json')=='bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062'
assert sha(PIN/'ordered-runtime-cp.json')=='515bf598f56b27de45aa04d63493e1f8ad38b7e4710ef358fea97b9fc9d620c1'
deps=json.loads(read(PIN/'ordered-runtime-cp.json'))
assert len(deps)==89
deps+=json.loads(read(ROOT/'desktop/.local/dynamic-editor-detail-parity/final-production-safe/dependency-identities.json'))[89:]
for dep in deps:assert sha(dep['path'])==dep['sha256Bytes']
write(OUT/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
source=ROOT/'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
base=read(source)
assert sha(source)=='8130c63b4554ed9970250588144955e6accf04e25223b7218dab7784ec585e53'
write(OUT/'operations-base.kt',base)
tree=ast.parse(read(HERE.parent/'produce.py'))
defs=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in {'masked','balanced'}]
lex={'re':re};exec(compile(ast.Module(body=defs,type_ignores=[]),'frozen-lexer-functions','exec'),lex)
masked=lex['masked'];balanced=lex['balanced'];ma=masked(base)
start=ma.index('internal class DesktopDynamicCardOperations')
parameters=ma.index('(',start);parameters_end=balanced(ma,parameters)
op=ma.index('{',parameters_end);end=balanced(ma,op,'{','}')
assert not ma[end:].strip()
comment=read(HERE.parent/'DesktopDynamicCommentOperations.fragment.kt')
detail=read(HERE/'DesktopDynamicDetailOperations.fragment.kt')
assembled=base[:end-1]+'\n'+comment+'\n'+detail+'\n'+base[end-1:]
ops=OUT/'inputs/DesktopDynamicCardOperations.kt';write(ops,assembled)
inputs=[Path(str(p).removeprefix('\\\\?\\')) for p in safe(HERE.parent/'generated').rglob('*.kt')]
inputs+=[Path(str(p).removeprefix('\\\\?\\')) for p in safe(HERE/'generated').rglob('*.kt')]
inputs+=[ops,HERE/'DynamicDetailProtocolFixture.kt']
assert len(inputs)==8
identities=[dict(path=str(p),sha256Bytes=sha(p)) for p in inputs]
classes=OUT/'classes';safe(classes).mkdir()
spec=importlib.util.spec_from_file_location('reply_compiler',ROOT/'desktop/.local/source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
cp=[dep['path'] for dep in deps]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(compiler.PLUGIN),
    '-Xfriend-paths='+str(PIN/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(classes)]+[str(p) for p in inputs]
write(OUT/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
run=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compiler.args')],
    capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=65)
write(OUT/'compile.log',run.stdout+run.stderr);print(run.stdout+run.stderr)
evidence=dict(compilePassed=run.returncode==0,snapshotManifestSha256Bytes=sha(PIN/'manifest.json'),orderedRuntimeCpSha256Bytes=sha(PIN/'ordered-runtime-cp.json'),
    orderedRuntimeCpCount=89,extraTestDependency=deps[-1],sources=identities,MainIntegration=False,
    currentMainReadOnlyBaseSha256Bytes=sha(source),retainedBaseSha256Bytes=sha(OUT/'operations-base.kt'),
    preparedProductOverride=['com.bilipai.desktop.data.DesktopDynamicCardOperations'],
    deltaOnly=['frozen comment member fragment','new detail member fragment'],sharedGradle=False,HTTP=False,HWND=False,realAccount=False,executed=False)
write(OUT/'evidence.json',json.dumps(evidence,indent=2)+'\n')
if run.returncode:sys.exit(run.returncode)
for item in identities:assert sha(item['path'])==item['sha256Bytes']
runtime=[str(compiler.JAVA),'-Dfile.encoding=UTF-8','-cp',str(classes)+';'+';'.join(cp),'com.bilipai.desktop.data.DynamicDetailProtocolFixtureKt',str(OUT/'result.json')]
write(OUT/'run-command.json',json.dumps(runtime,indent=2)+'\n')
try:run=subprocess.run(runtime,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35)
except subprocess.TimeoutExpired as exc:
    def as_text(value):return value.decode('utf-8',errors='replace') if isinstance(value,bytes) else value or ''
    write(OUT/'run.log',as_text(exc.stdout)+as_text(exc.stderr)+'\nTIMEOUT35\n');print(read(OUT/'run.log'));sys.exit(1)
write(OUT/'run.log',run.stdout+run.stderr);print(run.stdout+run.stderr)
evidence.update(executed=True,runtimePassed=run.returncode==0,compileLogSha256Bytes=sha(OUT/'compile.log'),runLogSha256Bytes=sha(OUT/'run.log'))
if safe(OUT/'result.json').exists():evidence['resultSha256Bytes']=sha(OUT/'result.json')
evidence['classes']=[dict(path=str(p).removeprefix('\\\\?\\'),sha256Bytes=sha(p)) for p in safe(classes).rglob('*.class')]
for item in identities:assert sha(item['path'])==item['sha256Bytes']
write(OUT/'evidence.json',json.dumps(evidence,indent=2)+'\n')
sys.exit(run.returncode)

