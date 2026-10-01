"""Verify the prepared source receipt before changing the isolated candidate."""
from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];CANDIDATE=BASE.parent/'BiliPai-v023'
LANE=CANDIDATE/'desktop/.local/stable-dynamic-protocol-rebase'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(raw):return hashlib.sha256(raw).hexdigest()
manifest_path=LANE/'detail-reply-evidence-manifest.json'
assert sha(ext(manifest_path).read_bytes())=='0a08d27a090c6d0eb402fd58f42fbb9951895a76db62c0fb3992c9ac43976b46'
manifest=json.loads(ext(manifest_path).read_bytes())
for row in manifest['artifacts']:
    raw=ext(LANE/row['path']).read_bytes()
    assert len(raw)==row['sizeBytes'] and sha(raw)==row['sha256Bytes'],row['path']
handoff=json.loads(ext(LANE/'detail-reply-handoff.json').read_bytes())
assert handoff['stableCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert handoff['generatorPythonPassed'] and handoff['soleOperationsFragmentVerifierPassed']
assert not handoff['MainKotlinCompiled']
installed=[]
for row in handoff['sourceCandidates']:
    if not row['changed']:continue
    current=ext(CANDIDATE/row['path']).read_bytes().replace(b'\r\n',b'\n')
    assert sha(current)==row['baseLfSha256'],row['path']
    prepared=ext(LANE/'prepared'/row['path']).read_bytes().replace(b'\r\n',b'\n')
    assert sha(prepared)==row['candidateLfSha256'],row['path']
    ext(CANDIDATE/row['path']).write_bytes(prepared)
    installed.append(dict(path=row['path'],candidateLfSha256=sha(prepared)))
receipt=dict(schema='stable-protocol-candidate-install-v1',preparedArtifactsVerified=len(manifest['artifacts']),
    sourceManifestSha256Bytes=sha(ext(manifest_path).read_bytes()),candidateInstalled=True,
    mainInstalled=False,compiled=False,runtimeAccepted=False,installed=installed)
(HERE/'protocol-install.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(verifiedRaw=len(manifest['artifacts']),installedProducers=len(installed),mainChanged=False)))
