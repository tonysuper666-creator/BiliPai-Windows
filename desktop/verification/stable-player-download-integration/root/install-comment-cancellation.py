from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-comment-image-cancel-completion-parity'
def ext(p): return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p): return ext(p).read_bytes()
def sha(raw): return hashlib.sha256(raw).hexdigest()
raw=read(LANE/'evidence-manifest.json')
assert sha(raw)=='8e0f1ebe81621f8da61d240f796c63ace4d9791b761e01913d26b3347a6fd940'
manifest=json.loads(raw)
for row in manifest['artifacts']:
    data=read(LANE/row['path']);assert sha(data)==row['sha256Bytes'] and len(data)==row['sizeBytes']
raw=read(LANE/'installation-recipe.json');assert sha(raw)==manifest['installationRecipeSha256Bytes']
recipe=json.loads(raw)
for row in recipe['sources']:
    assert sha(read(REPO/row['path']).replace(b'\r\n',b'\n'))==row['baseSha256LF']
    assert sha(read(LANE/'prepared'/row['path']))==row['candidateSha256LF']
for row in recipe['sources']: ext(REPO/row['path']).write_bytes(read(LANE/'prepared'/row['path']))
(HERE/'comment-cancellation-install.json').write_text(json.dumps(dict(candidateInstalled=True,mainInstalled=False,
    verifiedArtifacts=len(manifest['artifacts']),installedSources=recipe['sources'],wholeCompiled=False,stableRuntimeAccepted=False),indent=2)+'\n',encoding='utf-8')
print('Verified 115 frozen artifacts; installed matching cancellation completion in three sole producers.')
