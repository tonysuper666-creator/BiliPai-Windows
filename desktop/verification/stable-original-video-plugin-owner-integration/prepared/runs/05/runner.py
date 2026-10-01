import sys
sys.dont_write_bytecode=True
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-69'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert sha(SNAP/'manifest.json')=='4524b0c8b5e4e97bb88223a6a6f71c126bc17586111d50a10fb49f186a3d695d'
assert sha(SNAP/'ordered-runtime-cp.json')=='125c041f103947b5aa4be065a182cc561528bde575afe682db9e9af43a42dc9f'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==101
candidate=P/'compile/04/candidate.jar'
assert sha(candidate)=='8e0a6c169c1d983a7d1ff38a27c0f6dc92438cc363f6015f2fe2dfd972c3d658'
baseCp=cp.copy();cp=[dict(path=str(candidate),sha256Bytes=sha(candidate))]+cp
with zipfile.ZipFile(wide(SNAP/'main-kotlin.jar'))as z:
 constant=z.read('com/bilipai/desktop/plugins/js/DesktopJsWorkerAssetHashKt.class');values=set(re.findall(rb'[0-9a-f]{64}',constant));assert len(values)==1;catalogHash=next(iter(values)).decode()
worker=None
for p in wide(REPO/'desktop/build/jw').rglob('classpath.json'):
 if sha(p)==catalogHash:worker=p.parent;break
assert worker is not None,'No actual resource directory matches the actual69 compiled worker catalog'
catalog=json.loads(read(worker/'classpath.json'));workerFiles=[]
for r in catalog['classpath']+catalog['resources']:
 p=worker/r['file'];assert sha(p)==r['sha256'] and len(read(p))==r['bytes'],p
 workerFiles.append(dict(path=str(p),sha256Bytes=sha(p),bytes=len(read(p))))
save(P/'worker-resource-identity.json',dict(actualCompiledCatalogSHA256=catalogHash,path=str(worker),catalogSHA256Bytes=sha(worker/'classpath.json'),files=workerFiles,generatedFakeCatalog=False,executedJsPlugins=False))
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=P/'runs'/sys.argv[1];assert not wide(out).exists();wide(out).mkdir(parents=True)
wide(out/'runner.py').write_bytes(read(Path(__file__)))
fixture=out/'FinalWriteFixture.kt';wide(fixture).write_bytes(read(P/'FinalWriteFixture.kt'))
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 for r in workerFiles:assert sha(r['path'])==r['sha256Bytes']
 assert sha(worker/'classpath.json')==catalogHash
 return dict(snapshotManifestSHA256Bytes=sha(SNAP/'manifest.json'),orderedRuntimeSHA256Bytes=sha(SNAP/'ordered-runtime-cp.json'),runtime=cp,workerResourceIdentitySHA256Bytes=sha(P/'worker-resource-identity.json'),fixtureSourceSHA256Bytes=sha(fixture),tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins());jar=out/'fixture.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(candidate)+','+str(SNAP/'main-kotlin.jar'),'-module-name','fixture_finalwrite_only','-d',str(jar),str(fixture)]
arg=out/'compile.args';wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
save(out/'compile-result.json',dict(passed=r.returncode==0,exitCode=r.returncode,onlyFixtureCompiled=True,productionOverrides=4))
if r.returncode:
 save(out/'pins-after.json',pins());sys.stdout.reconfigure(encoding='utf8');print((r.stdout+r.stderr).decode('utf8',errors='replace'));raise SystemExit(r.returncode)
with zipfile.ZipFile(wide(jar))as z:fixtureEntries={e for e in z.namelist()if e.endswith('.class')}
productEntries=set()
for row in cp:
 with zipfile.ZipFile(wide(row['path']))as z:productEntries|={e for e in z.namelist()if e.endswith('.class')}
assert not fixtureEntries&productEntries
save(out/'class-overlap.json',dict(passed=True,fixtureClasses=sorted(fixtureEntries),actualRuntimeEntries=101,prospectiveOverrideJarSHA256Bytes=sha(candidate),productionClassIntersection=[],productionOverrides=4))
cmd=[str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Dbilipai.js.workerResources='+str(Path(str(worker).removeprefix('\\\\?\\'))),'-Dfixture.output='+str(out/'runtime'),'-cp',';'.join([str(jar),*(r['path']for r in cp)]),'fixture.finalwrite.FinalWriteFixtureKt']
wide(out/'run-command.json').write_bytes((json.dumps(cmd,indent=2)+'\n').encode())
r=subprocess.run(cmd,capture_output=True,timeout=75)
wide(out/'run.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
if wide(out/'runtime/result.json').exists():
 result=json.loads(read(out/'runtime/result.json'))
 origins=[]
 for origin in result['origins']:
  assert Path(origin['codeSource']).resolve() in [candidate.resolve(),(SNAP/'main-kotlin.jar').resolve()],origin
  with zipfile.ZipFile(wide(origin['codeSource']))as z:assert hashlib.sha256(z.read(origin['entry'])).hexdigest()==origin['classSHA256Bytes']
  origins.append(origin)
 save(out/'actual-code-origins.json',dict(passed=True,productionOverrides=4,loaded=origins))
 save(out/'result.json',dict(**result,processExitCode=r.returncode,actualCodeOriginsVerified=len(origins),actual101CPAndCandidatePrePostVerified=True,fixtureClassOverlapZero=True,workerResourcesCatalogAndEveryFileVerified=True))
sys.stdout.reconfigure(encoding='utf8',errors='replace');print((r.stdout+r.stderr).decode('utf8',errors='replace')[-14000:]);raise SystemExit(r.returncode)
