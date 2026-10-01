from pathlib import Path
import hashlib,json,subprocess,importlib.util,sys,zipfile,re
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('proof',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
cp=json.loads(read(MAIN/'desktop/.local/stable-product-snapshot-47/ordered-runtime-cp.json'));assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes']
compiled=json.loads(read(LANE/('compile-'+(sys.argv[2] if len(sys.argv)>2 else '03'))/'compile-result.json'));jar=compiled['jar'];assert sha(jar)==compiled['jarSha256Bytes']
shared=compiled['sharedGestureReference']['jar'];assert sha(shared)==compiled['sharedGestureReference']['sha256Bytes']
native=MAIN/'desktop/native/windows-x64/libmpv-2.dll';assert sha(native)=='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
clip=MAIN/'desktop/.local/stable-home-native-media-proof/runs/actual33-01/local-media/moving.mp4';assert sha(clip)=='0e09ab9a6a9af00cee99508e4a3ff41e9436f7d1850e4b31c6ceb0c22d8c4656'
factory=MAIN/'desktop/.local/stable-download-list-actual47-proof/ActualDownloadFixtureConstruction.kt'
assert sha(factory)=='7dbb20cce76d6f8cd86804360235741385f123dc0fbf213821ad76482521d984'
number=sys.argv[1] if len(sys.argv)>1 else '01';out=LANE/('proof-'+number);assert not safe(out).exists();safe(out).mkdir()
classpath=[jar,shared]+[r['path'] for r in cp]
sources=[str(LANE/'OfflineOriginalFixture.kt'),str(factory)]
pins={'runtimeEntries':cp,'prospectiveCandidate':{'path':jar,'sha256Bytes':sha(jar)},'prospectiveShared':{'path':shared,'sha256Bytes':sha(shared)},'fixtureSources':[{'path':p,'sha256Bytes':sha(p)} for p in sources]}
safe(out/'pins-before.json').write_text(json.dumps(pins,indent=2),encoding='utf-8')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+jar+','+shared+','+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','offline_original_fixture','-d',str(out/'classes')]+sources
argfile=out/'compiler.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print(r.stderr);sys.exit(r.returncode)
fixture=out/'offline-original-fixture.jar'
with zipfile.ZipFile(safe(fixture),'w',zipfile.ZIP_DEFLATED) as z:
 for p in safe(out/'classes').rglob('*'):
  if p.is_file():z.writestr(p.relative_to(safe(out/'classes')).as_posix(),p.read_bytes())
command=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true','-Dbilipai.mpv.path='+str(native),'-cp',';'.join([str(fixture)]+classpath),'com.bilipai.desktop.ui.OfflineOriginalFixtureKt',str(out/'local-fixture'),str(clip)]
safe(out/'runtime-command.json').write_text(json.dumps(command,indent=2),encoding='utf-8')
r=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=70)
safe(out/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print(r.stdout+r.stderr);sys.exit(r.returncode)
match=re.search(r'OFFLINE_ORIGINAL_PROOF (\{[^\n]+\})',r.stdout);assert match,r.stdout
result=json.loads(match.group(1));result.update({'actualSnapshot':47,'actualRuntimeEntries':97,'fixtureJarSha256Bytes':sha(fixture),'candidateJarSha256Bytes':sha(jar),'all97PinsVerifiedBeforeAfter':True,'declaredProspectiveOverrides':compiled['declaredProspectiveOverrides'],'sharedGestureReference':compiled['sharedGestureReference'],'fixtureOnlyPublicationFactory':{'path':str(factory),'sha256Bytes':sha(factory)},'sharedGradleRun':False,'nativeLibrary':{'path':str(native),'sha256Bytes':sha(native)},'syntheticMediaReference':{'path':str(clip),'sha256Bytes':sha(clip)},'rootMounted':False})
for row in cp:assert sha(row['path'])==row['sha256Bytes']
for row in pins['fixtureSources']:assert sha(row['path'])==row['sha256Bytes']
safe(out/'pins-after.json').write_text(json.dumps(pins,indent=2),encoding='utf-8')
safe(out/'proof-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result,indent=2))
