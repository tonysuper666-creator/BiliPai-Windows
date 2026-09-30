"""Freeze selected review/source/classes/raw evidence; never traverses junction victims via links."""
from pathlib import Path
import hashlib,json,os
HERE=Path(__file__).resolve().parent
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
paths=set();skipped=[]
def tree(directory):
    for entry in os.scandir(safe(directory)):
        ordinary=Path(str(entry.path).removeprefix('\\\\?\\'))
        if entry.is_symlink() or safe(ordinary).is_junction():
            skipped.append(ordinary.relative_to(HERE).as_posix());continue
        if entry.is_dir(follow_symlinks=False):tree(ordinary)
        elif entry.is_file(follow_symlinks=False):paths.add(ordinary)
for p in HERE.iterdir():
    if p.is_file() and p.name!='frozen-handoff.json':paths.add(p)
for directory in ['payload','generated-review','reference-only',
    'boundary-delta/payload','boundary-delta/classes-original','boundary-delta/classes-reviewed',
    'boundary-delta/run2-original-disk-proof','boundary-delta/run2-reviewed-disk-proof',
    'boundary-delta/run2-original-viewer-proof','boundary-delta/run2-reviewed-viewer-proof']:
    tree(HERE/directory)
for p in (HERE/'boundary-delta').iterdir():
    if p.is_file():paths.add(p)
first=HERE/'boundary-delta/original-disk-proof/result.json'
if first.exists():paths.add(first)
artifacts=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p),sizeBytes=safe(p).stat().st_size) for p in sorted(paths)]
result=dict(frozen=True,artifactCount=len(artifacts),artifacts=artifacts,
    producerManifestSha256Bytes='e04f8a6ec2b533695c3f988b4d67733d500089d49c238bbb1b976a8b930be5f7',producerFrozenArtifacts=322,
    installPlanSha256Bytes=sha(HERE/'install-plan.json'),deltaPlanSha256Bytes=sha(HERE/'boundary-delta/install-plan.json'),
    integrationDocSha256Bytes=sha(HERE/'ROOT-INTEGRATION.md'),skippedTaskOwnedJunctions=sorted(skipped),
    privateRuntimeEnvironmentExcluded=True,mainApplied=False,sharedGradleInvoked=False,nativeWindowCreated=False,
    claimsLimitedToLocalLoggingAndMarkedFixtures=True)
(HERE/'frozen-handoff.json').write_bytes((json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
print(json.dumps(dict(passed=True,artifactCount=len(artifacts),manifestSha256Bytes=sha(HERE/'frozen-handoff.json'),
    installPlanSha256Bytes=result['installPlanSha256Bytes'],deltaPlanSha256Bytes=result['deltaPlanSha256Bytes'],junctionsNotTraversed=len(skipped))))
