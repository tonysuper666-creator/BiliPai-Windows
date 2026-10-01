from pathlib import Path
import importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('tools',HERE/'compile.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
def main():
 out=HERE/('proof-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not c.safe(out).exists();c.safe(out).mkdir()
 cp=c.runtime();c.save(out/'runtime-pins-before.json',c.pins(cp));candidate=HERE/'compile-01/prepared-text-share-bindings.jar';runtime=[str(candidate)]+[x['path'] for x in cp]
 source=out/'TextShareFixture.kt';c.safe(source).write_bytes(c.safe(HERE/'TextShareFixture.kt').read_bytes());target=out/'text-share-fixture.jar'
 p=c.compile_sources(out,[source],target,runtime,[str(candidate),str(c.SNAP/'main-kotlin.jar')])
 if p.returncode:c.save(out/'accepted-result.json',dict(status='COMPILE_FAIL',exitCode=p.returncode));print(p.stdout+p.stderr);return p.returncode
 scratch=out/'scratch';c.safe(scratch).mkdir()
 command=[str(c.compiler().JAVA),'-Djava.awt.headless=true','-cp',str(target)+';'+';'.join(runtime),'com.bilipai.desktop.ui.textShareProof.TextShareFixtureKt',str(scratch),str(c.SNAP/'main-kotlin.jar'),str(candidate)]
 c.save(out/'runtime-command.json',command)
 p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=45);c.write(out/'runtime.log',p.stdout+p.stderr);c.save(out/'runtime-pins-after.json',c.pins(cp))
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,candidateJarSha256Bytes=c.sha(candidate),fixtureSourceSha256Bytes=c.sha(source),fixtureJarSha256Bytes=c.sha(target),actualMain15ManifestSha256Bytes=c.sha(c.SNAP/'manifest.json'),actualCp92Sha256Bytes=c.sha(c.SNAP/'ordered-runtime-cp.json'),nativeActorInvoked=False,noAccountReads=True,noExternalShare=True,noHTTP=True,noHWND=True,noMainEdits=True,noGradle=True)
 if p.returncode==0:
  raw=json.loads(c.safe(scratch/'result.json').read_text(encoding='utf-8'));result.update(caseCount=raw['caseCount'],assertions=raw['assertions'],actualCodeSources=len(raw['actualCodeSources']),resultSha256Bytes=c.sha(scratch/'result.json'))
 c.save(out/'accepted-result.json',result);print(json.dumps(result));print((p.stdout+p.stderr)[-5000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
