import importlib.util,json,sys
from pathlib import Path
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('tools',HERE/'compile.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
def main():
 target=HERE/'frozen-handoff.json';assert not c.safe(target).exists()
 proof=json.loads(c.safe(HERE/'proof-03/accepted-result.json').read_text());audit=json.loads(c.safe(HERE/'source-audit.json').read_text())
 assert proof['status']=='PASS' and proof['assertions']==34 and audit['status']=='PASS'
 rows=[]
 for p in sorted(c.safe(HERE).rglob('*')):
  if p.is_file():rows.append(dict(path=p.relative_to(c.safe(HERE)).as_posix(),sha256Bytes=c.sha(p),bytes=p.stat().st_size))
 c.save(target,dict(frozen=True,scope='prepared-only Windows ongoing download notification boundary',actualBaselineManifestSha256Bytes=c.sha(c.SNAP/'manifest.json'),actualCpSha256Bytes=c.sha(c.SNAP/'ordered-runtime-cp.json'),artifactCount=len(rows),artifacts=rows,installPayload=['prepared/desktop/src/main/kotlin/com/bilipai/desktop/download/DesktopDownloadNotifications.kt'],proof=dict(cases=1,assertions=34,codeSources=9,ownHiddenHWND=True,nativeTrayAddRemove=True,visibleTaskbarPixels=False,exposedHRESULT=False,actualRootMounted=False,noMainEdits=True,noSharedGradle=True,noExternalHTTP=True)))
 print(json.dumps(dict(path=str(target),artifacts=len(rows),sha256Bytes=c.sha(target))))
if __name__=='__main__':main()
