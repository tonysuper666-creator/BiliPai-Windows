from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];PRIMARY=REPO.parent/'BiliPai';EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(EXT) else EXT+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
run=HERE/'runs'/sys.argv[1];result=json.loads(safe(run/'compile-evidence.json').read_text(encoding='utf-8'));assert result['passed']
cp=result['orderedRuntimeCP'];assert len(cp)==92
for r in cp:assert sha(r['path'])==r['sha256Bytes']
jar=run/'candidate.jar';assert sha(jar)==result['candidateJarSha256']
spec=importlib.util.spec_from_file_location('existing_compiler',PRIMARY/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
scratch=run/'runtime';assert not scratch.exists();scratch.mkdir()
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
args=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(jar)+';'+';'.join(r['path'] for r in cp),'com.bilipai.desktop.ui.frostedAudioProof.FrostedAudioFixtureKt',str(scratch),str(jar),str(main)]
p=subprocess.run(args,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(run/'runtime.log',p.stdout+p.stderr)
for r in cp:assert sha(r['path'])==r['sha256Bytes']
e=dict(passed=p.returncode==0,exitCode=p.returncode,compiledJarSha256=sha(jar),snapshotManifest=result['snapshotManifest'],snapshotCP=result['snapshotCP'],orderedCPCount=92,runtimeLogSha256=sha(run/'runtime.log'),offscreenActualComposeScene=True,MainWritten=False,sharedGradle=False,noHTTP=True,noHWND=True)
if p.returncode==0:e.update(json.loads(safe(scratch/'result.json').read_text(encoding='utf-8')));e['resultSha256']=sha(scratch/'result.json')
save(run/'accepted-fixture.json',e);print(p.stdout+p.stderr);print(json.dumps(e));sys.exit(p.returncode)
