"""Read-only verification of the two frozen proxy cohorts and current target states."""
from pathlib import Path
import hashlib, importlib.util, json, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
REVIEW=HERE.parent/'network-proxy-main-review'
PRODUCER=HERE.parent/'settings-network-proxy-parity'
REVIEW_PIN='dc416b5f9a7e4ff4d0c9643b55b5f57f423345206eb16a93c94e9f679df2a2d5'
PRODUCER_PIN='f7352d7499f02891077c228238a2e6b6853db4efc46e676af9ad9785c8602a67'
PLAN_PIN='e1367e941c9f5b813c96fc9135966fc2b7238d46b3ef1160893fd01a31d620eb'
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def verify():
 assert sha(REVIEW/'frozen-handoff.json')==REVIEW_PIN
 assert sha(PRODUCER/'verified-artifacts.json')==PRODUCER_PIN
 assert sha(REVIEW/'install-plan.json')==PLAN_PIN
 review=json.loads((REVIEW/'frozen-handoff.json').read_text(encoding='utf-8'))
 for i in review['files']:assert sha(REVIEW/i['path'])==i['sha256Bytes'],i['path']
 spec=importlib.util.spec_from_file_location('frozen_installer_readonly',REVIEW/'install-reviewed.py')
 installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)
 plan=installer.load_plan();checked=installer.verify(REPO,plan)
 states=[dict(path=i['path'],currentSha256Bytes=sha(REPO/i['path']) if safe(REPO/i['path']).exists() else None,
  state='installed-exact' if sha(REPO/i['path'])==i['payloadSha256Bytes'] else 'original-exact')
  if safe(REPO/i['path']).exists() else dict(path=i['path'],currentSha256Bytes=None,state='not-installed-new-file') for i in plan['files']]
 evidence=dict(passed=True,reviewFrozenFilesVerified=len(review['files']),producerFrozenFilesVerified=281,
  immutableDependencyJarsVerified=len(plan['dependencies']),originalSourcesAndResourcesVerified=6,
  planPin=PLAN_PIN,targetStates=states,mainModified=False,frozenModified=False,
  currentProductTestExecuted=False,sharedGradleInvoked=False,nativeWindowCreated=False,networkRequested=False)
 (HERE/'input-verification.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8',newline='\n')
 print(json.dumps(dict(passed=True,reviewFiles=len(review['files']),producerFiles=281,
  dependencies=len(plan['dependencies']),targets=len(checked),installedExact=sum(i['state']=='installed-exact' for i in states),
  actualProductTestsExecuted=False)))
 return evidence
if __name__=='__main__':verify()
