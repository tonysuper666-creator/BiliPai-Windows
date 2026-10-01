from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
from urllib.parse import urlparse,unquote
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-45'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assert sha(SNAP/'manifest.json')=='61b8233a766542366bf6a72868434e9effbf2f0c6458ae0f77326168a50f3fec'
assert sha(SNAP/'ordered-runtime-cp.json')=='946d0f272d696310a60b8814cbc4a0ed6ae30617ba047dbfe10d729583234419'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==97
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
serial=c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
out=LANE/('run-'+sys.argv[1]);assert not out.exists();out.mkdir()
sources=sorted(list(wide(LANE/'prepared').rglob('*.kt'))+list(wide(LANE/'proof-only').rglob('*.kt')));assert len(sources)==3
copied=[]
for path in sources:
    target=out/'source-inputs'/path.relative_to(wide(LANE));wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(path.read_bytes());copied.append(target)
tools=[c.JAVA,serial,c.PLUGIN]+c.COMPILER
def pins():
    for row in cp:assert sha(row['path'])==row['sha256Bytes']
    return dict(runtime=cp,tools=[dict(path=str(p),sha256Bytes=sha(p))for p in tools],sources=[dict(path=str(p),sha256Bytes=sha(p))for p in copied])
save(out/'pins-before.json',pins())
def compile(target,sources,classpath,plugins,friends,name,log):
    args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,*['-Xplugin='+str(p)for p in plugins],'-Xfriend-paths='+friends,'-module-name',name,'-d',str(target),*map(str,sources)]
    arg=out/(log+'.args');wide(arg).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
    run=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
    wide(out/(log+'.log')).write_bytes(run.stdout+run.stderr)
    if run.returncode:print((run.stdout+run.stderr).decode('utf-8',errors='replace'));save(out/'failure.json',dict(stage=log,exitCode=run.returncode));raise SystemExit(run.returncode)
candidate=out/'candidate.jar'
compile(candidate,copied,';'.join(r['path']for r in cp),[serial,c.PLUGIN],str(SNAP/'main-kotlin.jar'),'com_bilipai_desktop_bilipai_windows','production-compile')
with zipfile.ZipFile(wide(candidate))as z:own={n for n in z.namelist()if n.endswith('.class')};assert not any(b'NON_LOCAL_RETURN' in z.read(n)for n in own)
actual=set()
for row in cp:
    with zipfile.ZipFile(wide(row['path']))as z:actual.update(n for n in z.namelist()if n.endswith('.class'))
save(out/'compile-result.json',dict(passed=True,sources=3,candidateJarSha256Bytes=sha(candidate),classes=len(own),explicitExistingOverrides=sorted(own&actual),newClassOverlaps=[],actualProductAcceptance=False))
fixtureSource=out/'AccountPortFixture.kt';wide(fixtureSource).write_bytes(read(LANE/'AccountPortFixture.kt'))
fixture=out/'fixture.jar';classpath=';'.join([str(candidate)]+[r['path']for r in cp])
compile(fixture,[fixtureSource],classpath,[],str(candidate)+','+str(SNAP/'main-kotlin.jar'),'profile_account_fixture','fixture-compile')
with zipfile.ZipFile(wide(fixture))as z:fixtureClasses={n for n in z.namelist()if n.endswith('.class')}
assert not fixtureClasses&(actual|own)
save(out/'fixture-intersections.json',dict(fixtureClasses=sorted(fixtureClasses),fixtureProductIntersections=[],fixtureCandidateIntersections=[]))
command=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(fixture)+';'+classpath,'com.bilipai.desktop.ui.AccountPortFixtureKt',str(out/'private-synthetic-store')]
save(out/'runtime-command.json',command)
run=subprocess.run(command,capture_output=True,timeout=60);wide(out/'runtime-stdout.raw.log').write_bytes(run.stdout);wide(out/'runtime-stderr.raw.log').write_bytes(run.stderr)
if run.returncode:print((run.stdout+run.stderr).decode('utf-8',errors='replace'));save(out/'failure.json',dict(stage='fixture-runtime',exitCode=run.returncode));raise SystemExit(run.returncode)
result=json.loads(read(out/'private-synthetic-store/result.json'))
for origin in result['origins']:
    expected=candidate if origin['class'].startswith('com.bilipai.desktop.')else SNAP/'main-kotlin.jar'
    path=unquote(urlparse(origin['codeSource']).path);path=path[1:]if len(path)>2 and path[0]=='/'and path[2]==':'else path
    assert Path(path).resolve()==expected.resolve()
    with zipfile.ZipFile(wide(expected))as z:assert hashlib.sha256(z.read(origin['class'].replace('.','/')+'.class')).hexdigest()==origin['classSha256']
save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
result.update(dict(explicitProductionOverrides=2,actualProductAcceptance=False,fixtureProductionIntersections=[],candidateJarSha256Bytes=sha(candidate),actual45RuntimeEntries=97,cpToolsSourcesPrePostByteEqual=True))
save(out/'result.json',result);print(json.dumps({k:v for k,v in result.items()if k!='origins'}))
