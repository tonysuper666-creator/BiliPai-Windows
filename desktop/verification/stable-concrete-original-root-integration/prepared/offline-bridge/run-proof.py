from pathlib import Path
import hashlib,json,subprocess,importlib.util,sys,zipfile,re
sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('proof',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
audit=json.loads(read(LANE/'input-audit.json'));cp=audit['verifiedDependencyPins'];assert len(cp)==97 and audit['actualSnapshot']==44
for row in cp:assert sha(row['path'])==row['sha256Bytes']
compiled=json.loads(read(LANE/'compile-03/compile-result.json'));jar=compiled['jar'];assert sha(jar)==compiled['jarSha256Bytes']
native=MAIN/'desktop/native/windows-x64/libmpv-2.dll';assert sha(native)=='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
clip=MAIN/'desktop/.local/stable-home-native-media-proof/runs/actual33-01/local-media/moving.mp4';assert sha(clip)=='0e09ab9a6a9af00cee99508e4a3ff41e9436f7d1850e4b31c6ceb0c22d8c4656'
number=sys.argv[1] if len(sys.argv)>1 else '01';out=LANE/('proof-'+number);assert not safe(out).exists();safe(out).mkdir()
classpath=[jar]+[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+jar+','+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','offline_task_fixture','-d',str(out/'classes'),str(LANE/'OfflineTaskPlayerFixture.kt')]
argfile=out/'compiler.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print(r.stderr);sys.exit(r.returncode)
fixture=out/'offline-task-fixture.jar'
with zipfile.ZipFile(safe(fixture),'w',zipfile.ZIP_DEFLATED) as z:
 for p in safe(out/'classes').rglob('*'):
  if p.is_file():z.writestr(p.relative_to(safe(out/'classes')).as_posix(),p.read_bytes())
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true','-Dbilipai.mpv.path='+str(native),'-cp',';'.join([str(fixture)]+classpath),'com.bilipai.desktop.ui.OfflineTaskPlayerFixtureKt',str(out/'local-fixture'),str(clip),str(jar)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=55)
safe(out/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print(r.stdout+r.stderr);sys.exit(r.returncode)
match=re.search(r'OFFLINE_TASK_PROOF (\{[^\n]+\})',r.stdout);assert match,r.stdout
result=json.loads(match.group(1));result.update({'actualSnapshot':44,'actualRuntimeEntries':97,'candidateNewClassesOnly':True,'prospectiveOverrides':[],'fixtureJarSha256Bytes':sha(fixture),'candidateJarSha256Bytes':sha(jar),'all97PinsVerified':True,'sharedGradleRun':False,'nativeLibrary':{'path':str(native),'sha256Bytes':sha(native)},'syntheticMediaReference':{'path':str(clip),'sha256Bytes':sha(clip)}})
for row in cp:assert sha(row['path'])==row['sha256Bytes']
safe(out/'proof-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result,indent=2))
