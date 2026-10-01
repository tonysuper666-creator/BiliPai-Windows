from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-71'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert sha(SNAP/'manifest.json')=='416f1ec5fdf577f13c607d52248ca3c97d39420d363bac78f500d119053e6ad1'
assert sha(SNAP/'ordered-runtime-cp.json')==json.loads(read(SNAP/'manifest.json'))['orderedRuntimeClasspathSha256Bytes']
actual=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(actual)==101
candidate=P/'compile/01/candidate.jar';assert sha(candidate)=='986577a7e6064995abe5cfb85a6822cc4e43d7de11aa5063da598fb62660fdc4'
cp=[dict(path=str(candidate),sha256Bytes=sha(candidate))]+actual
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=P/'runs'/sys.argv[1];assert not wide(out).exists();wide(out).mkdir(parents=True)
fixture=out/'PublicExecutionFixture.kt';wide(fixture).write_bytes(read(P/'PublicExecutionFixture.kt'));wide(out/'runner.py').write_bytes(read(Path(__file__)))
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return dict(snapshotManifestSHA256Bytes=sha(SNAP/'manifest.json'),orderedRuntimeSHA256Bytes=sha(SNAP/'ordered-runtime-cp.json'),runtime=cp,fixtureSourceSHA256Bytes=sha(fixture),tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins());jar=out/'fixture.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(candidate)+','+str(SNAP/'main-kotlin.jar'),'-module-name','fixture_sponsorpublic_only','-d',str(jar),str(fixture)]
arg=out/'compile.args';wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);save(out/'compile-result.json',dict(passed=r.returncode==0,fixtureOnly=True,prospectiveProductCandidateExplicit=True))
if r.returncode:
 save(out/'pins-after.json',pins());sys.stdout.reconfigure(encoding='utf8');print((r.stdout+r.stderr).decode('utf8',errors='replace'));raise SystemExit(r.returncode)
with zipfile.ZipFile(wide(jar))as z:fixtureEntries={e for e in z.namelist()if e.endswith('.class')}
productEntries=set()
for row in cp:
 with zipfile.ZipFile(wide(row['path']))as z:productEntries|={e for e in z.namelist()if e.endswith('.class')}
assert not fixtureEntries&productEntries
save(out/'class-overlap.json',dict(passed=True,fixtureClasses=sorted(fixtureEntries),productionClassIntersection=[]))
cmd=[str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Dfixture.output='+str(out/'runtime'),'-cp',';'.join([str(jar),*(r['path']for r in cp)]),'fixture.sponsorpublic.PublicExecutionFixtureKt']
save(out/'run-command.json',cmd);r=subprocess.run(cmd,capture_output=True,timeout=45)
wide(out/'run.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
if wide(out/'runtime/result.json').exists():
 result=json.loads(read(out/'runtime/result.json'))
 for origin in result['origins']:
  assert Path(origin['codeSource']).resolve() in [candidate.resolve(),(SNAP/'main-kotlin.jar').resolve()],origin
  with zipfile.ZipFile(wide(origin['codeSource']))as z:assert hashlib.sha256(z.read(origin['entry'])).hexdigest()==origin['classSHA256Bytes']
 save(out/'actual-code-origins.json',dict(passed=True,origins=result['origins'],prospectiveCandidate=True))
 save(out/'result.json',dict(**result,processExitCode=r.returncode,actual101CPAndCandidatePrePostVerified=True,loadedOriginsAndBytesVerified=3,fixtureClassIntersectionZero=True))
sys.stdout.reconfigure(encoding='utf8');print((r.stdout+r.stderr).decode('utf8',errors='replace'));raise SystemExit(r.returncode)
