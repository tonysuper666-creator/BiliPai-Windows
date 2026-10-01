import sys,subprocess,json,zipfile
from pathlib import Path
sys.dont_write_bytecode=True
import compile as c
HERE=c.HERE;SNAP=c.SNAP
out=HERE/('proof-'+(sys.argv[1] if len(sys.argv)>1 else '03'));assert not c.safe(out).exists();c.safe(out).mkdir()
cp=c.runtime();c.save(out/'runtime-pins-before.json',c.pins(cp))
compilePhase='compile-'+(sys.argv[2] if len(sys.argv)>2 else '03')
candidate=HERE/compilePhase/'original-danmaku-list-menu.jar';compiled=json.loads(c.safe(HERE/compilePhase/'compile-result.json').read_text(encoding='utf-8'));assert compiled['status']=='PASS' and c.sha(candidate)==compiled['jarSha256Bytes']
source=out/'DanmakuFixture.kt';c.safe(source).write_bytes(c.safe(HERE/'DanmakuFixture.kt').read_bytes())
fixture=out/'danmaku-fixture.jar'
p=c.compile_sources(out,[source],fixture,[str(candidate)]+[x['path'] for x in cp],[str(candidate),str(SNAP/'main-kotlin.jar')]);assert p.returncode==0,(p.stdout+p.stderr)
scratch=out/'scratch';c.safe(scratch).mkdir();result=scratch/'result.json'
command=[str(c.compiler().JAVA),'-Djava.awt.headless=true','-cp',';'.join([str(fixture),str(candidate)]+[x['path'] for x in cp]),'com.bilipai.desktop.ui.DanmakuFixtureKt',str(result)]
c.save(out/'runtime-command.json',command)
p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=60);c.write(out/'runtime.log',p.stdout+p.stderr)
c.save(out/'runtime-pins-after.json',c.pins(cp))
accepted=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,candidateJarSha256Bytes=c.sha(candidate),fixtureJarSha256Bytes=c.sha(fixture),fixtureSourceSha256Bytes=c.sha(source),actualMain16ManifestSha256Bytes=c.sha(SNAP/'manifest.json'),actualCp92Sha256Bytes=c.sha(SNAP/'ordered-runtime-cp.json'),noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True,noAccountReads=True)
if not p.returncode:
 raw=json.loads(c.safe(result).read_text(encoding='utf-8'));assert raw['status']=='PASS'
 actualPaths={Path(str(x['path'])).absolute() for x in cp}
 with zipfile.ZipFile(c.safe(candidate)) as own,zipfile.ZipFile(c.safe(SNAP/'main-kotlin.jar')) as actual:
  overlap=set(own.namelist())&set(actual.namelist())
  declared=compiled['declaredActualClassOverlap'];assert sorted(x for x in overlap if x.endswith('.class'))==declared
  for x in raw['actualCodeSources']:
   path=Path(x['path']);assert path.absolute() in actualPaths or path.absolute()==candidate.absolute()
   with zipfile.ZipFile(c.safe(path)) as z:assert c.hashlib.sha256(z.read(x['class'].replace('.','/')+'.class')).hexdigest()==x['classSha256Bytes']
 accepted.update(groupedCases=raw['groupedCases'],assertions=raw['assertions'],actualCodeSources=len(raw['actualCodeSources']),declaredActualClassOverlap=declared,zeroUnexpectedActualClassOverlap=True,resultSha256Bytes=c.sha(result),actualOriginalUiMounted=False,actualRootVideoStateProjectionConsumed=False,existingPrecompiledModelCopyCallerRuntimeClaim=False)
c.save(out/'accepted-result.json',accepted)
print(json.dumps(accepted));print(p.stdout+p.stderr);raise SystemExit(p.returncode)
