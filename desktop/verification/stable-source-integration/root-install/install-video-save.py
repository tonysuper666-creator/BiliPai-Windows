"""Install only the frozen, verified video destination payload in the candidate."""
from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
BASE=HERE.parents[2]
CANDIDATE=BASE.parent/'BiliPai-v023'
LANE=BASE/'desktop/.local/video-default-save-parity'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def read(path):return ext(path).read_bytes()
def sha(raw):return hashlib.sha256(raw).hexdigest()
def lf(path):return read(path).replace(b'\r\n',b'\n')
raw=read(LANE/'evidence-manifest.json')
assert sha(raw)=='9dfb6a3ab5f9c95eea1e11ad3523176e337cec11d87899f67ce50dd8806e288b'
manifest=json.loads(raw)
for row in manifest['artifacts']:
    data=read(LANE/row['path'])
    assert sha(data)==row['sha256Bytes'],row['path']
    assert len(data)==row['sizeBytes'],row['path']
handoff=read(LANE/'frozen-handoff.json')
assert sha(handoff)=='ed921892b35ef1e65a6ec900580af87dfe1f8ed9e09ae793fa86bff54da026f7'
meta=json.loads(handoff)
for row in meta['sourceCandidates']:
    assert sha(lf(CANDIDATE/row['path']))==row['baseLfSha256'],row['path']
    assert sha(lf(row['candidate']))==row['candidateLfSha256'],row['path']
installed=[]
for row in meta['sourceCandidates']:
    data=lf(row['candidate']);ext(CANDIDATE/row['path']).write_bytes(data)
    installed.append(dict(path=row['path'],sha256Lf=sha(data)))
receipt=dict(schema='stable-video-save-candidate-install-v1',candidateInstalled=True,mainInstalled=False,
    upstreamStableHelperSha256Lf=meta['stableHelperLfSha256'],verifiedArtifacts=len(manifest['artifacts']),
    installed=installed,wholeStableCompiled=False,stableRuntimeAccepted=False)
(HERE/'video-save-install.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(installedPayloads=len(installed),verifiedArtifacts=len(manifest['artifacts']))))
