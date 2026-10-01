from pathlib import Path
import argparse,hashlib,importlib.util,json,subprocess,sys,zipfile
from urllib.parse import urlparse,unquote
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-47'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
PACKETS=[dict(name='accounts',lane='stable-profile-account-port-parity',manifestSha='250100d194c524add5b3baf05b1efd1d470a63d4045ed4274c624e9b6292f5c2',source='AccountPortFixture.kt',mainClass='com.bilipai.desktop.ui.AccountPortFixtureKt',groups=3,assertions=30),
 dict(name='video',lane='stable-video-detail-admission-parity',manifestSha='ee4c6d9fea64b8294bfe6bd995c68f12304f41820594fae1181fc6b2787b4da9',source='RouteAdmissionFixture.kt',mainClass='com.bilipai.desktop.RouteAdmissionFixtureKt',groups=2,assertions=24)]
def inputPins():
    rows=[]
    for packet in PACKETS:
        root=MAIN/'desktop/.local'/packet['lane'];frozen=root/'frozen-handoff.json'
        assert sha(frozen)==packet['manifestSha']
        source=root/packet['source'];registered=next(r for r in json.loads(read(frozen))['artifacts']if r['path']==packet['source'])
        assert sha(source)==registered['sha256Bytes']
        rows.append(dict(name=packet['name'],manifest=str(frozen),manifestSha256Bytes=sha(frozen),source=str(source),sourceSha256Bytes=sha(source),groups=packet['groups'],assertions=packet['assertions']))
    return rows
if len(sys.argv)==1:
    save(LANE/'prepared-runner.json',dict(prepared=True,waitingForActualSnapshot=47,requiredSnapshotManifestAndCpSha=True,oldFixtureInputs=inputPins(),productionOverrides=0,requiredRuntimeEntries=97,claims=['fixture-only compilation, unchanged original assertions','No HTTP/socket/native DLL/GUI/window/user files','Host JVM loading is not actual Host UI mounting or native seek ACK'],invocation='python desktop/.local/stable-profile-video-admission-actual47-proof/run-proof.py 01 MANIFEST_SHA CP_SHA'))
    print(json.dumps(dict(prepared=True,waitingForActual47=True,fixtureInputs=2,originalGroups=5,originalAssertions=54)));raise SystemExit(0)
args=argparse.ArgumentParser();args.add_argument('run');args.add_argument('manifest_sha');args.add_argument('cp_sha');opts=args.parse_args()
assert SNAP.exists(),'Actual47 immutable snapshot is not ready; no run was performed'
assert sha(SNAP/'manifest.json')==opts.manifest_sha and sha(SNAP/'ordered-runtime-cp.json')==opts.cp_sha
manifest=json.loads(read(SNAP/'manifest.json'));cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==97 and manifest['runtimeEntries']==97
assert manifest['wholeCandidateClassesPassed']
inputRows=inputPins()
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
tools=[c.JAVA]+c.COMPILER
out=LANE/('run-'+opts.run);assert not out.exists();out.mkdir()
actual=set()
for row in cp:
    assert sha(row['path'])==row['sha256Bytes']
    with zipfile.ZipFile(wide(row['path']))as z:actual.update(n for n in z.namelist()if n.endswith('.class'))
def pins():
    assert sha(SNAP/'manifest.json')==opts.manifest_sha and sha(SNAP/'ordered-runtime-cp.json')==opts.cp_sha
    for row in cp:assert sha(row['path'])==row['sha256Bytes']
    return dict(manifestSha256Bytes=opts.manifest_sha,orderedRuntimeCpSha256Bytes=opts.cp_sha,runtime=cp,tools=[dict(path=str(p),sha256Bytes=sha(p))for p in tools],originalFixtureInputs=inputPins())
save(out/'pins-before.json',pins())
mainKotlin=SNAP/'main-kotlin.jar';results=[];origins=[]
try:
    for packet,inputRow in zip(PACKETS,inputRows):
        runDir=out/packet['name'];runDir.mkdir();source=runDir/packet['source'];wide(source).write_bytes(read(inputRow['source']))
        assert sha(source)==inputRow['sourceSha256Bytes']
        jar=runDir/'fixture.jar';compilerArgs=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xfriend-paths='+str(mainKotlin),'-module-name','actual47_'+packet['name']+'_fixture','-d',str(jar),str(source)]
        argfile=runDir/'compile.args';wide(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in compilerArgs)+'\n',encoding='utf-8')
        compileRun=subprocess.run([str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,timeout=180)
        wide(runDir/'compile.log').write_bytes(compileRun.stdout+compileRun.stderr)
        if compileRun.returncode:save(runDir/'failure.json',dict(stage='fixture-compile',exitCode=compileRun.returncode));print((compileRun.stdout+compileRun.stderr).decode('utf-8',errors='replace'));raise RuntimeError(packet['name']+' fixture compile failed')
        with zipfile.ZipFile(wide(jar))as z:classes={n for n in z.namelist()if n.endswith('.class')}
        intersections=sorted(classes&actual);save(runDir/'fixture-intersections.json',dict(fixtureClasses=sorted(classes),productClassIntersections=intersections));assert not intersections
        runtime=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(jar)+';'+';'.join(r['path']for r in cp),packet['mainClass'],str(runDir/'private-memory')]
        save(runDir/'runtime-command.json',runtime)
        run=subprocess.run(runtime,capture_output=True,timeout=60);wide(runDir/'runtime-stdout.raw.log').write_bytes(run.stdout);wide(runDir/'runtime-stderr.raw.log').write_bytes(run.stderr)
        if run.returncode:save(runDir/'failure.json',dict(stage='fixture-runtime',exitCode=run.returncode));print((run.stdout+run.stderr).decode('utf-8',errors='replace'));raise RuntimeError(packet['name']+' fixture runtime failed')
        data=json.loads(read(runDir/'private-memory/result.json'));assert data['passed']and data['groups']==packet['groups']and data['assertions']==packet['assertions']
        for origin in data['origins']:
            path=unquote(urlparse(origin['codeSource']).path);path=path[1:]if len(path)>2 and path[0]=='/'and path[2]==':'else path
            assert Path(path).resolve()==mainKotlin.resolve(),origin
            with zipfile.ZipFile(wide(mainKotlin))as z:assert hashlib.sha256(z.read(origin['class'].replace('.','/')+'.class')).hexdigest()==origin['classSha256']
        save(runDir/'actual-origins.json',dict(classes=data['origins'],expectedProductJar=str(mainKotlin),expectedProductJarSha256Bytes=sha(mainKotlin),zeroProductionOverrides=True,allCodeSourcesAndClassBytesVerified=True))
        data.update(dict(actualProductSnapshot=47,productionOverrides=0,fixtureProductClassIntersections=[],originalFixtureSourceUnchanged=True,originalFixtureLegacyProspectiveConsoleWordingRetained=True,fixtureSourceSha256Bytes=sha(source),fixtureJarSha256Bytes=sha(jar)))
        save(runDir/'result.json',data);results.append(data);origins+=data['origins']
    save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
    result=dict(passed=True,phase='actual47-profile-video-admission-zero-override',groups=sum(r['groups']for r in results),assertions=sum(r['assertions']for r in results),productionOverrides=0,runtimeEntries=97,cpToolsOriginalSourcesPrePostByteEqual=True,fixtureProductClassIntersections=[],actualProductOriginationRows=len(origins),uniqueActualProductClasses=len({r['class']for r in origins}),origins=origins,fixtures=results,limitations=['No actual Profile/Video Host mounting, native DLL, native seek ACK, HTTP/socket, root navigation, chooser or user files','Original fixture console labels describe their prior prospective run; this runner verifies only actual47 JAR/class-byte origins and has no production candidate JAR'])
    save(out/'result.json',result);save(LANE/'result.json',result);print(json.dumps({k:v for k,v in result.items()if k not in ('origins','fixtures')}))
except BaseException as error:
    save(out/'runner-failure.json',dict(type=type(error).__name__,message=str(error)));raise
