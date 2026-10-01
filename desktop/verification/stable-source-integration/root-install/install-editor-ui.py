from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];REPO=BASE.parent/'BiliPai-v023'
LANE=REPO/'desktop/.local/stable-editor-full-ui'
source_path='desktop/tools/extract-upstream-dynamic-editor.py'
def sha(raw):return hashlib.sha256(raw).hexdigest()
def lf(path):return Path(path).read_bytes().replace(b'\r\n',b'\n')
assert sha(lf(REPO/source_path))=='48ee4bce174611fc6271c6710711334d1a16adc65f6434b18b6f72b1b3e885ff'
candidate=lf(LANE/'prepared'/source_path)
assert sha(candidate)=='adf02e645c163afc52ba2b63db1f2f2564a1f68cbd58dc8d37590d766cfadac7'
proof=json.loads(lf(LANE/'full-composer-retention-proof.json'))
assert proof['passed'] and proof['fullOriginalBodyByteEqualAfterReversingOnlyActivityResultSeams']
assert proof['fixedStableCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
patch=str(LANE/'gradle-input-only.patch')
subprocess.run(['git','-c','core.longpaths=true','apply','--check',patch],cwd=REPO,check=True)
(REPO/source_path).write_bytes(candidate)
subprocess.run(['git','-c','core.longpaths=true','apply',patch],cwd=REPO,check=True)
receipt=dict(schema='stable-editor-ui-candidate-install-v1',candidateInstalled=True,mainInstalled=False,
    path=source_path,sha256Lf=sha(candidate),fullOriginalComposerBodyRetained=True,
    wholeStableCompiled=False,stableRuntimeAccepted=False,preparedAgentEvidenceAwaitingFreeze=True)
(HERE/'editor-ui-install.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print(json.dumps(receipt))
