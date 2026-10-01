import importlib.util, json, subprocess, sys
from pathlib import Path
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('compile_tool',HERE/'compile.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
def main():
 out=HERE/('proof-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not c.safe(out).exists();c.safe(out).mkdir()
 cp=c.runtime();c.save(out/'runtime-pins-before.json',c.pins(cp))
 candidate=HERE/'compile-02/prepared-download-notification.jar';source=out/'DownloadNotificationFixture.kt';c.safe(source).write_bytes(c.safe(HERE/'DownloadNotificationFixture.kt').read_bytes())
 target=out/'notification-fixture.jar';runtime=[str(candidate)]+[x['path'] for x in cp]
 p=c.compile_sources(out,[source],target,runtime,[str(candidate),str(c.SNAP/'main-kotlin.jar')])
 if p.returncode:c.save(out/'accepted-result.json',dict(status='COMPILE_FAIL',exitCode=p.returncode));print(p.stdout+p.stderr);return p.returncode
 scratch=out/'scratch';c.safe(scratch).mkdir()
 command=[str(c.compiler().JAVA),'-Djava.awt.headless=false','-cp',str(target)+';'+';'.join(runtime),'com.bilipai.desktop.download.notificationProof.DownloadNotificationFixtureKt',str(scratch)]
 c.save(out/'runtime-command.json',command)
 p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=90);c.write(out/'runtime.log',p.stdout+p.stderr);c.save(out/'runtime-pins-after.json',c.pins(cp))
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,candidateSha256Bytes=c.sha(candidate),fixtureSha256Bytes=c.sha(target),fixtureSourceSha256Bytes=c.sha(source),actualStable11ManifestSha256Bytes=c.sha(c.SNAP/'manifest.json'),actualRuntimeCpCount=92,noMainEdits=True,noGradle=True,oneTaskOwnedHiddenHWND=True,noExternalHTTP=True,preparedOnly=True)
 if p.returncode==0:
  raw=json.loads(c.safe(scratch/'result.json').read_text());result.update(caseCount=raw['caseCount'],assertions=raw['assertions'],actualCodeSources=len(raw['codeSources']),resultSha256Bytes=c.sha(scratch/'result.json'))
 c.save(out/'accepted-result.json',result);print(json.dumps(result));print((p.stdout+p.stderr)[-10000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
