from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-47'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
assert sha(SNAP/'manifest.json')=='72e862d74e18c2b388853634f0ef47c48ca1adba25b5d074ecd23fc6ecb7c3e0'
assert sha(SNAP/'ordered-runtime-cp.json')=='9fecec31b818a1b1d8be25f628ff6ec69e354a5c179ca42445aa1a1db3d52e94'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==97
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
phase=LANE/('runs/'+sys.argv[1]);compiled=json.loads(read(phase/'compile-result.json'));candidate=phase/'candidate.jar';assert compiled['passed'] and sha(candidate)==compiled['candidateJarSha256Bytes']
out=LANE/('proof/'+sys.argv[2]);assert not wide(out).exists();wide(out).mkdir(parents=True)
source=out/'VideoEngagementFixture.kt';wide(source).write_bytes(read(LANE/'VideoEngagementFixture.kt'))
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return dict(runtime=cp,candidateJarSha256Bytes=sha(candidate),fixtureSourceSha256Bytes=sha(source),tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins())
fixture=out/'fixture.jar';args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join([str(candidate)]+[r['path']for r in cp]),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+','.join([str(candidate),str(SNAP/'main-kotlin.jar')]),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(fixture),str(source)]
arg=out/'compile.args';wide(arg).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
cmd=[str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)]
p=subprocess.run(cmd,capture_output=True,timeout=90);wide(out/'compile.log').write_bytes(p.stdout+p.stderr)
save(out/'compile-result.json',dict(passed=p.returncode==0,exitCode=p.returncode,fixtureJarSha256Bytes=sha(fixture)if wide(fixture).exists()else None))
if p.returncode:print((p.stdout+p.stderr).decode(errors='replace'));raise SystemExit(p.returncode)
scratch=out/'private-scratch';wide(scratch).mkdir();result=scratch/'result.json'
runtime=[str(c.JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(fixture),str(candidate)]+[r['path']for r in cp]),'com.bilipai.desktop.ui.VideoEngagementFixtureKt',str(result),str(scratch/'store')]
save(out/'runtime-command.json',runtime)
p=subprocess.run(runtime,capture_output=True,timeout=60);wide(out/'runtime.log').write_bytes(p.stdout+p.stderr)
save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
accepted=dict(status='PASS'if p.returncode==0 else'FAIL',exitCode=p.returncode,prospectiveProductionOverrides=['new Video full-unit classes','existing Metadata visibility','existing Operations added-member buddy'],actual47RuntimeEntries=97,actualProductAcceptance=False)
if p.returncode==0:
 value=json.loads(read(result));assert value['status']=='PASS'
 wide(out/'result.json').write_bytes(read(result))
 with zipfile.ZipFile(wide(fixture))as z,zipfile.ZipFile(wide(candidate))as own:
  fixtureClasses={n for n in z.namelist()if n.endswith('.class')}
  assert not fixtureClasses&set(own.namelist())
  for r in cp:
   if str(r['path']).endswith('.jar'):
    with zipfile.ZipFile(wide(r['path']))as actual:assert not fixtureClasses&set(actual.namelist())
 for origin in value['origins']:
  with zipfile.ZipFile(wide(origin['path']))as z:assert hashlib.sha256(z.read(origin['class'].replace('.','/')+'.class')).hexdigest()==origin['classSha256Bytes']
 accepted.update(groupedCases=value['groupedCases'],assertions=value['assertions'],loadedClassOrigins=len(value['origins']),fixtureProductClassOverlap=0,resultSha256Bytes=sha(out/'result.json'))
save(out/'accepted-result.json',accepted);print(json.dumps(accepted));print((p.stdout+p.stderr).decode(errors='replace'));raise SystemExit(p.returncode)
