from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
from urllib.parse import unquote,urlparse

sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
SNAP=MAIN/'desktop/.local/stable-product-snapshot-45'
def safe(p):
    value=str(Path(p).absolute())
    return Path(value if value.startswith('\\\\?\\')else'\\\\?\\'+value)
def read(p):return safe(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def main():
    assert len(sys.argv)==4,'run.py RUN EXPECTED_MANIFEST_SHA EXPECTED_ORDERED_CP_SHA; use only Root-announced actual45 pins'
    assert sha(SNAP/'manifest.json')==sys.argv[2]
    assert sha(SNAP/'ordered-runtime-cp.json')==sys.argv[3]
    cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==97
    out=LANE/('run-'+sys.argv[1]);assert not out.exists();out.mkdir()
    spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
    c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
    tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA]+c.COMPILER]
    def pins():
        for row in cp+tools:assert sha(row['path'])==row['sha256Bytes'],row['path']
        return dict(runtime=cp,tools=tools,actualSnapshot=45,manifestSha256Bytes=sha(SNAP/'manifest.json'),orderedCpSha256Bytes=sha(SNAP/'ordered-runtime-cp.json'))
    save(out/'pins-before.json',pins())
    safe(out/'input-manifest.json').write_bytes(read(SNAP/'manifest.json'))
    safe(out/'input-ordered-runtime-cp.json').write_bytes(read(SNAP/'ordered-runtime-cp.json'))
    groups=[dict(name='backend84',lane='stable-playback-account-protocol-parity',source='PlaybackAccountFixture.kt',package='com.bilipai.desktop.data',
        frozen='b990563ad4ec9cf4eca8ea495377fe0496073209684df8d8e922cbe8a08322a3',assertions=47,originCount=9),
        dict(name='final428',lane='stable-playback-final-publication-parity',source='PublicationRaceFixture.kt',package='com.bilipai.desktop',
        frozen='3c1f394d055a060a0f8e35a5e51f0f46da3d71f6f2ab6320080aeb306ab3bc5d',assertions=39,originCount=11)]
    accepted=[]
    for group in groups:
        original=MAIN/'desktop/.local'/group['lane'];assert sha(original/'frozen-handoff.json')==group['frozen']
        manifest=json.loads(read(original/'frozen-handoff.json'))
        row=next(r for r in manifest['artifacts']if r['path']==group['source'])
        originalSha=sha(original/group['source']);assert originalSha==row['sha256Bytes']
        directory=out/group['name'];directory.mkdir()
        source=directory/group['source'];safe(source).write_bytes(read(original/group['source']))
        assert sha(source)==originalSha
        save(directory/'fixture-source-receipt.json',dict(path=str(original/group['source']),sha256Bytes=originalSha,
            frozenManifestSha256Bytes=group['frozen'],sourceByteIdentical=True,businessAssertionsUnchanged=group['assertions'],
            provenanceNote='Historical prospective wording in unchanged fixture output is superseded only by this actual45 runner origin checks; no product override or Root mounted acceptance'))
        fixture=directory/'fixture.jar'
        args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),
            '-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','playback_account_fixture','-d',str(fixture),str(source)]
        argfile=directory/'compiler.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
        compileRun=subprocess.run([str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,timeout=150)
        safe(directory/'compiler-stdout.raw.log').write_bytes(compileRun.stdout);safe(directory/'compiler-stderr.raw.log').write_bytes(compileRun.stderr)
        safe(directory/'compiler.log').write_text((compileRun.stdout+compileRun.stderr).decode('utf-8',errors='replace'),encoding='utf-8')
        if compileRun.returncode:
            save(directory/'failure.json',dict(stage='fixture_compile',exitCode=compileRun.returncode));return compileRun.returncode
        with zipfile.ZipFile(safe(fixture))as z:fixtureClasses=sorted(n for n in z.namelist()if n.endswith('.class'))
        intersection=[];productEntries={}
        for row in cp:
            with zipfile.ZipFile(safe(row['path']))as z:
                overlap=sorted(set(fixtureClasses)&set(z.namelist()))
                if overlap:intersection.append(dict(path=row['path'],classes=overlap))
                if row['path']in [cp[0]['path'],cp[1]['path']]:
                    for name in z.namelist():
                        if name.endswith('.class'):
                            assert name not in productEntries
                            productEntries[name]=row['path']
        save(directory/'class-intersection.json',dict(fixtureClasses=fixtureClasses,intersections=intersection,productionOverrides=0))
        assert not intersection
        private=directory/'private-synthetic-store'
        command=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',';'.join([str(fixture)]+[r['path']for r in cp]),
            group['package']+'.'+group['source'].removesuffix('.kt')+'Kt',str(private)]
        save(directory/'runtime-command.json',command)
        run=subprocess.run(command,capture_output=True,timeout=100)
        safe(directory/'runtime-stdout.raw.log').write_bytes(run.stdout);safe(directory/'runtime-stderr.raw.log').write_bytes(run.stderr)
        safe(directory/'runtime.log').write_text((run.stdout+run.stderr).decode('utf-8',errors='replace'),encoding='utf-8')
        if run.returncode:
            save(directory/'failure.json',dict(stage='fixture_run',exitCode=run.returncode));return run.returncode
        result=json.loads(read(private/'result.json'))
        assert result['status']=='PASS'and result['assertions']==group['assertions']and len(result['origins'])==group['originCount'],result
        origins=[]
        for origin in result['origins']:
            classPath=origin['class'].replace('.','/')+'.class'
            expected=productEntries[classPath]
            uri=urlparse(origin['codeSource']);assert uri.scheme=='file'
            path=unquote(uri.path)
            if len(path)>2 and path[0]=='/'and path[2]==':':path=path[1:]
            assert Path(path).resolve()==Path(expected).resolve(),(origin,expected)
            with zipfile.ZipFile(safe(expected))as z:digest=hashlib.sha256(z.read(classPath)).hexdigest()
            assert digest==origin['classSha256']
            origins.append(dict(origin,expectedProductJar=expected,actualCodeSourceAndBytesVerified=True))
        save(directory/'actual-origins.json',origins)
        result.update(dict(actualSnapshot=45,actualProductClassProof=True,actualRootAccountAcceptance=False,
            productionOverrides=0,fixtureClassIntersections=[],fixtureSourceByteIdentical=True,fixtureJarSha256Bytes=sha(fixture),
            actualRuntimeEntries=97,HTTP=False,socket=False,HWND=False,GUI=False,Gradle=False,
            provenanceNote='Original fixture business claims and output unchanged; actual installed product origins verified by runner, no candidate JAR present'))
        save(directory/'result.json',result);accepted.append(result)
        print(json.dumps(dict(group=group['name'],passed=True,assertions=result['assertions'],origins=len(origins),productionOverrides=0)),flush=True)
    save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
    result=dict(passed=True,actualSnapshot=45,manifestSha256Bytes=sys.argv[2],orderedRuntimeCpSha256Bytes=sys.argv[3],
        runtimeEntries=97,productionOverrides=0,cpPrePostByteEqual=True,businessAssertions=86,groups=8,fixtures=accepted,
        scope='Same installed Store/repository/receipt/native publication/cast writer/download classes with private synthetic memory transport and explicit local admission',
        notClaimed=['Native DLL execution','Root account/page mounted end-to-end','HTTP/socket','HWND/GUI','user files or credentials'])
    save(out/'result.json',result);save(LANE/'result.json',result);return 0
if __name__=='__main__':raise SystemExit(main())
