from pathlib import Path
import json,hashlib
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2].parent/'BiliPai-v023'
recipe=json.loads((HERE/'installation.json').read_text(encoding='utf-8'))
assert recipe['originalCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def sha(raw): return hashlib.sha256(raw).hexdigest()
for row in recipe['sources']:
    assert sha((REPO/row['path']).read_bytes().replace(b'\r\n',b'\n'))==row['baseSha256LF'],row['path']
    assert sha((HERE/'prepared'/row['path']).read_bytes())==row['candidateSha256LF'],row['path']
for row in recipe['sources']:
    (REPO/row['path']).write_bytes((HERE/'prepared'/row['path']).read_bytes())
print('Installed original quality dialog and online fallback consumers; whole build pending.')
