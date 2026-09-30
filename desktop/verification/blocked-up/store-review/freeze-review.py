"""Freeze only this isolated review cohort after all fixed inputs and dry-run checks pass."""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode = True
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def safe(p):
    value=str(Path(p).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('blocked_freeze_installer',HERE/'install-reviewed.py')
installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)
plan=installer.load_plan();result=installer.install(ROOT,plan,False)
assert result['readOnly'] and result['changedFiles']==0
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()==plan['baseCommit']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip()
for relative in ['review-evidence.json','generated-verification.json','junit-evidence.json','installer-test-result.json']:
    assert json.loads((HERE/relative).read_text())['passed'],relative
files=[]
extended=safe(HERE)
for p in sorted(extended.rglob('*')):
    if not p.is_file():continue
    relative=p.relative_to(extended).as_posix()
    if '__pycache__' in p.parts or relative=='frozen-handoff.json':continue
    files.append(dict(path=relative,sha256Bytes=sha(p),bytes=p.stat().st_size))
manifest=dict(status='frozen',baseCommit=plan['baseCommit'],files=files,
      installerPlanSha256Bytes=sha(HERE/'install-plan.json'),installerTargetCount=8,
      reviewedStoreSha256Bytes=sha(HERE/'payload/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBlockedUpStore.kt'),
      producerArtifactManifestSha256Bytes=plan['frozenArtifactManifestSha256'],producerArtifactsVerified=118,
      dependenciesVerified=234,sourceCounts=plan['sourceCounts'],resourceCounts=plan['resourceCounts'],
      actualReviewJUnitEngineFound=8,actualReviewJUnitEnginePassed=8,newReviewBoundaryChecks=4,
      boundaryVariantRuns=2,installerUnittestPassed=9,mainEdited=False,productInstallerApplied=False,
      currentMainCompiled=False,sharedGradleInvoked=False,nativeWindowCreated=False,userAccountReads=False,networkRequests=False,
      requiresRootSeparateDiscoveryFailureAndRestoreFence=True,fullManagementUiImplemented=False,remoteSyncVerified=False)
(HERE/'frozen-handoff.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
for r in files:assert sha(HERE/r['path'])==r['sha256Bytes'],r['path']
print(json.dumps(dict(passed=True,files=len(files),manifestSha256Bytes=sha(HERE/'frozen-handoff.json'),planSha256Bytes=sha(HERE/'install-plan.json'))))
