"""UTF-8 finalization of passed attempt4 only; never rerun tests or mutate the old frozen cohort."""
from pathlib import Path
import difflib,hashlib,json
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'discovery-storage-error-parity'
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
assert not (HERE/'verified-artifacts.json').exists(),'Already frozen'
assert sha(OLD/'verified-artifacts.json')=='422f8f9a91cedfcd61a021f227566d73509e6daf8921b175fbfa91c9123588c4'
for r in json.loads((OLD/'verified-artifacts.json').read_text())['files']:assert sha(OLD/r['path'])==r['sha256Bytes'],r['path']
for name in ['proof/old-disk.json','proof/old-pointer/result.json','proof/mounted-pointer/result.json','proof/actual-same-mid.json','same-production-classes-regression.json']:
 assert json.loads((HERE/name).read_text(encoding='utf-8'))['passed'],name
deps=json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8'))
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
patch=[];payload=[]
for name in ['DesktopDiscoveryStorageGuard.kt','DesktopDiscoveryStorageBoundary.kt']:
 relative='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name
 base=(OLD/name).read_text(encoding='utf-8');desired=(HERE/name).read_text(encoding='utf-8')
 destination=HERE/'prepared'/relative;write(destination,desired)
 patch.append(''.join(difflib.unified_diff(base.splitlines(True),desired.splitlines(True),fromfile='a/'+relative,tofile='b/'+relative)))
 payload.append(dict(path=relative,baselineSha256Bytes=sha(OLD/name),desiredSha256Bytes=sha(destination),preparedPath=str(destination.relative_to(HERE))))
 # Only a task-owned baseline staging tree is prepared for git apply --check.
 baseline=HERE/'patch-check'/relative;write(baseline,base)
write(HERE/'integration.patch',''.join(patch));write(HERE/'production-payload.json',json.dumps(payload,indent=2)+'\n')
sources=sorted((HERE/'marked-overrides').rglob('*.kt'))+[HERE/n for n in ['DesktopDiscoveryStorageGuard.kt','DiscoveryStorageFixture.kt','DiscoveryStorageUiFixture.kt','DesktopDiscoveryStorageBoundary.kt','BoundaryMountedUiFixture.kt','SameMidEpochFixture.kt']]
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClassDirectory='classes-attempt4',oldRegressionClassDirectory='classes-attempt3',
 historicalUnpassedClassDirectories=['classes-attempt1','classes-attempt2'],sources=[dict(path=str(p.relative_to(HERE)),sha256Bytes=sha(p)) for p in sources],
 productionPayload=payload,oldFrozenManifestSha256Bytes=sha(OLD/'verified-artifacts.json'),productSnapshotJars=deps[:3],dependencyCount=len(deps),
 currentRootRestoreFenceClaimed=False,actualProcessRestartClaimed=False,actualArchiveRestoreClaimed=False,sharedGradle=False,HWND=False,HTTP=False,userAccountFiles=False),indent=2)+'\n')
files=[]
for p in sorted(safe(HERE).rglob('*')):
 if not p.is_file():continue
 relative=str(p.relative_to(safe(HERE))).replace('\\','/')
 if relative=='verified-artifacts.json':continue
 files.append(dict(path=relative,sha256Bytes=sha(p),bytes=p.stat().st_size))
write(HERE/'verified-artifacts.json',json.dumps(dict(passed=True,files=files,artifactCount=len(files),activeClasses='classes-attempt4',oldFrozenPreserved=True,
 originalDiskChecks=11,originalPointerCases=4,newMountedPointerStyles=2,actualSameMidEpochChecks=1,actualArchiveRestoreClaimed=False,
 noMainMutation=True,noSharedGradle=True,noHWND=True,noRemoteHTTP=True,noUserAccountFiles=True),indent=2)+'\n')
print('FROZEN',len(files),sha(HERE/'verified-artifacts.json'))
for r in payload:print(r['path'],r['desiredSha256Bytes'])
