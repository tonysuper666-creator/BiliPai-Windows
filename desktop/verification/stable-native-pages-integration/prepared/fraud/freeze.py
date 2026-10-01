"""Freeze this task lane only; keep all failed proof history and copied inputs."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def digest(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def main():
 manifest=HERE/'frozen-handoff.json';assert not safe(manifest).exists()
 assert json.loads(read(HERE/'compile-02/compile-result.json'))['status']=='PASS'
 accepted=json.loads(read(HERE/'fixture-04/accepted-result.json'));assert accepted['status']=='PASS' and accepted['caseCount']==12 and accepted['assertions']==40
 audit=json.loads(read(HERE/'source-audit.json'));assert audit['status']=='PASS' and audit['checkCount']==22
 artifacts=[]
 for path in sorted(safe(HERE).rglob('*')):
  if not path.is_file():continue
  relative=path.relative_to(safe(HERE)).as_posix()
  assert '__pycache__' not in relative
  artifacts.append(dict(path=relative,byteSize=path.stat().st_size,sha256Bytes=digest(path)))
 result=dict(status='FROZEN_PREPARED',artifactCount=len(artifacts),artifacts=artifacts,
  originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',originalSourceSha256LF='4950b91a708e4d22ddbc6b6f39ef6880879e175dad543ff1fd5b833518b2b820',
  actualStableSnapshot11Sha256Bytes='22ceb7285e89fb0ead5731a8035f98f7e3fc0790be264699671edb921aa924c7',orderedCp92Sha256Bytes='a158698f0c336c7c28d63efc3b07f830576a70c1432a877d5ca2f92aaa3237fa',
  sourceAuditSha256Bytes=digest(HERE/'source-audit.json'),compileResultSha256Bytes=digest(HERE/'compile-02/compile-result.json'),fixtureAcceptedSha256Bytes=digest(HERE/'fixture-04/accepted-result.json'),
  caseCount=12,assertions=40,sourceCheckCount=22,sourceContract='contract.txt',
  installPayload=['prepared/desktop/tools/extract-upstream-comment-fraud-protocol.py','prepared/desktop/src/main/kotlin/com/android/purebilibili/data/repository/DesktopCommentFraudRawTransport.kt','operations-member.fragment.kt'],
  forbidInstalling=['proof-only','base-inputs','dependency-inputs','compile-01','compile-02','fixture-01','fixture-02','fixture-03','fixture-04'],
  statusPolicyPersistenceOwner='/root/video_save_alignment',preserveOriginalType1=True,originalRawInvisibleContainsLimitPreserved=True,
  noMainOrSiblingEdits=True,noSharedGradle=True,noExternalHTTP=True,noSocket=True,noHWND=True,noActualAccount=True,
  preparedOnly=True,notBgmUiOrPersistentRecordE2E=True,failedAttemptHistoryRetained=True)
 safe(manifest).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(path=str(manifest),sha256Bytes=digest(manifest),artifactCount=len(artifacts))))
if __name__=='__main__':main()
