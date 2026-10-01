from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
SNAPSHOT=MAIN/'desktop/.local/stable-product-snapshot-11'
def ext(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p): return hashlib.sha256(ext(p).read_bytes()).hexdigest()
assert sha(SNAPSHOT/'manifest.json')=='22ceb7285e89fb0ead5731a8035f98f7e3fc0790be264699671edb921aa924c7'
assert sha(SNAPSHOT/'ordered-runtime-cp.json')=='a158698f0c336c7c28d63efc3b07f830576a70c1432a877d5ca2f92aaa3237fa'
cp=json.loads((SNAPSHOT/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for row in cp: assert sha(row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('root_routing_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
OUT=HERE/'run-01';assert not OUT.exists();OUT.mkdir()
home=OUT/'home';temp=OUT/'temp';home.mkdir();temp.mkdir()
environment=os.environ.copy();environment['TEMP']=str(temp);environment['TMP']=str(temp)
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
def args(path,values):path.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8')
args(OUT/'compile.args',['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),
    '-Xfriend-paths='+main,'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(OUT/'fixture.jar'),str(HERE/'DownloadRoutingFixture.kt')])
result=subprocess.run([str(compiler.JAVA),'-Xmx512m','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compile.args')],env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
(OUT/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8');result.check_returncode()
args(OUT/'run.args',['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(home),'-Djava.io.tmpdir='+str(temp),
    '-cp',';'.join([str(OUT/'fixture.jar')]+[r['path'] for r in cp]),
    'com.android.purebilibili.feature.download.DownloadRoutingFixtureKt',str(OUT/'proof')])
result=subprocess.run([str(compiler.JAVA),'@'+str(OUT/'run.args')],env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
(OUT/'run.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout+result.stderr);result.check_returncode()
with zipfile.ZipFile(OUT/'fixture.jar') as z: fixture={p for p in z.namelist() if p.endswith('.class')}
existing=set()
for row in cp:
    assert sha(row['path'])==row['sha256Bytes']
    with zipfile.ZipFile(ext(row['path'])) as z: existing.update(z.namelist())
assert not fixture&existing
proof=json.loads((OUT/'proof/result.json').read_text(encoding='utf-8'))
(OUT/'accepted-evidence.json').write_text(json.dumps(dict(**proof,actualImmutableWholeStableGraph=True,productClassOverrides=0,
    snapshotManifestSha256Bytes=sha(SNAPSHOT/'manifest.json'),fixtureSourceSha256Bytes=sha(HERE/'DownloadRoutingFixture.kt'),
    fixtureJarSha256Bytes=sha(OUT/'fixture.jar')),indent=2)+'\n',encoding='utf-8')
