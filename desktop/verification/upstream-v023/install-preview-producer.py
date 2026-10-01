"""Admit only the reviewed preview delta into the unchanged sole producer."""
from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];CANDIDATE=BASE.parent/'BiliPai-v023'
LANE=BASE/'desktop/.local/stable-image-preview-producer-parity'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(raw):return hashlib.sha256(raw).hexdigest()
manifest_path=LANE/'evidence-manifest.json'
assert sha(ext(manifest_path).read_bytes())=='aa2f0fded76142156e7470d40d999939e78fe9ea3a86300353ab8e1e805a92d9'
meta=json.loads(ext(manifest_path).read_bytes())
for row in meta['artifacts']:
    raw=ext(LANE/row['path']).read_bytes()
    assert len(raw)==row['bytes'] and sha(raw)==row['sha256Bytes'],row['path']
handoff=json.loads(ext(LANE/'handoff.json').read_bytes())
assert handoff['originalCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
relative='desktop/tools/extract-upstream-dynamic-card.py'
old=ext(BASE/relative).read_bytes().replace(b'\r\n',b'\n')
current=ext(CANDIDATE/relative).read_bytes().replace(b'\r\n',b'\n')
assert current==old,'Another full-card producer change requires merging, not replacement'
ext(CANDIDATE/relative).write_bytes(current)
patch=str(LANE/'producer.patch')
subprocess.run(['git','-c','core.longpaths=true','apply','--check',patch],cwd=CANDIDATE,check=True)
subprocess.run(['git','-c','core.longpaths=true','apply',patch],cwd=CANDIDATE,check=True)
prepared=ext(LANE/handoff['producer']).read_bytes().replace(b'\r\n',b'\n')
actual=ext(CANDIDATE/relative).read_bytes().replace(b'\r\n',b'\n')
assert actual==prepared and sha(actual)==handoff['producerSha256Bytes']
record=dict(schema='stable-preview-candidate-install-v1',verifiedPreparedArtifacts=len(meta['artifacts']),
    patchAppliedToUnchangedSoleProducer=True,candidateProducerSha256Lf=sha(actual),
    preparedSourceChecks=24,preparedPlatformPairs=33,candidateInstalled=True,
    mainInstalled=False,wholeProductCompiled=False,runtimeAccepted=False,callerIntegrationPending=True)
(HERE/'preview-install.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(verifiedPrepared=len(meta['artifacts']),candidateProducerSha256Lf=sha(actual),mainChanged=False)))
