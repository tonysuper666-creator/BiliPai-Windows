from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
entries=[]
for path in sorted(HERE.rglob('*')):
 if '__pycache__' in path.parts or path.name=='verified-artifacts.json':continue
 safe=Path('\\\\?\\'+str(path.absolute()))
 if not safe.is_file():continue
 entries.append(dict(path=path.relative_to(HERE).as_posix(),sha256Bytes=hashlib.sha256(safe.read_bytes()).hexdigest()))
manifest=HERE/'verified-artifacts.json'
manifest.write_text(json.dumps(dict(artifacts=entries,artifactCount=len(entries),activeClassDirectory='classes-cohort',priorAttemptClassDirectory='classes',applicationProxyPrepared=True,fullDiagnosticsComplete=False,mainModified=False,sharedGradleInvoked=False,nativeWindowCreated=False,accountOrExternalHttpRequestMade=False),indent=2),encoding='utf-8',newline='\n')
print(json.dumps(dict(count=len(entries),manifestSha256=hashlib.sha256(manifest.read_bytes()).hexdigest())))
