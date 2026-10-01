from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];REPO=BASE.parent/'BiliPai-v023'
LANE=REPO/'desktop/.local/stable-editor-full-ui'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def read(path):return ext(path).read_bytes()
def lf(path):return read(path).replace(b'\r\n',b'\n')
def sha(raw):return hashlib.sha256(raw).hexdigest()
raw=read(LANE/'evidence-manifest.json')
assert sha(raw)=='9d7376c941a30725c3e745e8f99b5cb0f3591ace7efef5d48bacee1031ee98e2'
manifest=json.loads(raw)
for row in manifest['artifacts']:
    data=read(LANE/row['path']);assert sha(data)==row['sha256Bytes'],row['path']
    expected=next((row[k] for k in ['sizeBytes','bytes','byteCount','size'] if k in row),None)
    if expected is not None:assert len(data)==expected
raw=read(LANE/'frozen-handoff.json')
assert sha(raw)=='95352212d3525e6c37789ff25d4512fdbb3fd8de46f8f5300ca55fe50bcca774'
handoff=json.loads(raw)
rows=handoff['sourceCandidates']
installed=[]
for row in rows:
    path=row['path'];candidate=lf(LANE/'prepared'/path)
    assert sha(candidate)==row['candidateLfSha256']
    if path.endswith('extract-upstream-dynamic-editor.py'):
        assert lf(REPO/path)==candidate
        continue
    assert sha(lf(REPO/path))==row['baseLfSha256'],path
for row in rows:
    path=row['path']
    if path.endswith('extract-upstream-dynamic-editor.py'):continue
    candidate=lf(LANE/'prepared'/path);ext(REPO/path).write_bytes(candidate)
    installed.append(dict(path=path,sha256Lf=sha(candidate)))
receipt=dict(schema='stable-editor-capacity-candidate-install-v1',candidateInstalled=True,mainInstalled=False,
    installed=installed,verifiedArtifacts=len(manifest['artifacts']),maximumDynamicImages=18,maximumCommentImages=9,
    wholeStableCompiled=False,stableRuntimeAccepted=False)
(HERE/'editor-capacity-install.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(installed=len(installed),verifiedArtifacts=len(manifest['artifacts']))))
