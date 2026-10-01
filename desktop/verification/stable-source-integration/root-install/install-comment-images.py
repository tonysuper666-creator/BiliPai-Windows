from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-dynamic-reply-image-composer-parity'
def read(path): return Path(path).read_bytes()
def lf(path): return read(path).replace(b'\r\n',b'\n')
def sha(raw): return hashlib.sha256(raw).hexdigest()
recipe=json.loads(read(LANE/'installation-recipe.json'))
assert recipe['originalCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert sha(read(recipe['compileResult']))==recipe['compileResultSha256Bytes']
changes=[]
for row in recipe['sources']:
    target=REPO/row['installPath']
    candidate=lf(row['preparedPath'])
    assert sha(lf(target))==row['baseSha256LF'],row['installPath']
    assert sha(candidate)==row['candidateSha256LF'],row['installPath']
    changes.append((target,candidate))
ops=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
raw=lf(ops)
assert sha(raw)==recipe['operations']['baseSha256LF']
fragment=read(recipe['operations']['memberFragment'])
assert sha(fragment)==recipe['operations']['memberFragmentSha256Bytes']
anchor=b'suspend fun uploadCommentImage(fileName:String,mimeType:String,bytes:ByteArray):Result<ReplyPicture> = result { mutate { csrf -> uploadEditorCommentImage(csrf,fileName,mimeType,bytes) } }'
assert raw.count(anchor)==1 and b'suspend fun uploadCommentImageBody(' not in raw
changes.append((ops,raw.replace(anchor,anchor+b'\n'+fragment,1)))
for target,candidate in changes: target.write_bytes(candidate)
receipt=dict(schema='stable-comment-images-candidate-install-v1',candidateInstalled=True,mainInstalled=False,
    installed=[dict(path=str(p.relative_to(REPO)).replace('\\','/'),sha256LF=sha(raw)) for p,raw in changes],
    maximumCommentImages=9,wholeStableCompiled=False,stableRuntimeAccepted=False,
    validation='Prepared targeted compile PASS; whole candidate compile is next.')
(HERE/'comment-images-install.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(installed=len(changes))))
