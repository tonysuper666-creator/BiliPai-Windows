from pathlib import Path
import hashlib,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REVIEW=HERE.parent;ROOT=HERE.parents[3]
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
assert sha(REVIEW/'frozen-handoff.json')=='3f1d0f4bf92c750b4a3aade45b74bc45e640fd4dc18836b214901289f376b6df'
for r in json.loads((REVIEW/'frozen-handoff.json').read_text())['files']:assert sha(REVIEW/r['path'])==r['sha256Bytes']
assert json.loads((HERE/'evidence.json').read_text())['passed']
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()=='ae57dc66884da3243a7f1d5c24bb734674069483'
root_status=subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).splitlines()
contract=json.loads((HERE/'delta-contract.json').read_text())
for row in contract['files']:
    assert sha(ROOT/row['path']) in (row['baseSha256Bytes'],row['payloadSha256Bytes']),row['path']
files=[];extended=safe(HERE)
for p in sorted(extended.rglob('*')):
    if not p.is_file():continue
    relative=p.relative_to(extended).as_posix()
    if '__pycache__' in p.parts or relative=='frozen-handoff.json':continue
    files.append(dict(path=relative,sha256Bytes=sha(p),bytes=p.stat().st_size))
manifest=dict(status='frozen',files=files,appliesAfterReviewStoreSha256Bytes='188189ca9b0e009dda2fbca44d59fab7ca64ce33e93da87e9c5c68d3d87e90bf',
    previous137ArtifactsUnchanged=True,producer118ArtifactsUnchanged=True,actualUniqueRealDiskChecks=5,variantRuns=2,
    mainEdited=False,sharedGradleInvoked=False,nativeWindowCreated=False,userAccountReads=False,networkRequests=False,
    rootOwnedIntegrationInProgress=bool(root_status),rootWorkingTreeStatusAtIndependentFreeze=root_status,
    deltaContractSha256Bytes=sha(HERE/'delta-contract.json'))
(HERE/'frozen-handoff.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
for r in files:assert sha(HERE/r['path'])==r['sha256Bytes']
print(json.dumps(dict(passed=True,files=len(files),manifestSha256Bytes=sha(HERE/'frozen-handoff.json'),contractSha256Bytes=sha(HERE/'delta-contract.json'))))
