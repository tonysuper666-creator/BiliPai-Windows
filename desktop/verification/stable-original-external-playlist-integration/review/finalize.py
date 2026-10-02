from pathlib import Path
import hashlib,json
H=Path(__file__).resolve().parent
def read(p):return p.read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def dump(p,v):p.write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
assert not(H/'frozen-review-final.json').exists()
r=json.loads(read(H/'source-review.json'))
old='Original checkpoint uses global import_checkpoint_v1 key; receipt guards writes but this is not new per-account checkpoint partitioning.'
new='Original checkpoint uses global external_playlist_import_checkpoint_v1 key; receipt guards writes but this is not new per-account checkpoint partitioning.'
assert old in r['conclusions'];r['conclusions'][r['conclusions'].index(old)]=new
repo=Path(r['primaryInputs'][0]['path']).parents[10]
# Obtain the already pinned fixed original path from the root recipe, without guessing the key.
manual=Path(r['primaryInputs'][0]['path']);repo=manual
while repo.name!='BiliPai-v023':repo=repo.parent
source=(repo/'app/src/main/java/com/android/purebilibili/data/repository/ExternalPlaylistRepository.kt').read_text(encoding='utf-8')
needle='private val importCheckpointKey = stringPreferencesKey("external_playlist_import_checkpoint_v1")'
assert needle in source
r['sourceChecks'].append(dict(file=r['fixedOriginals'][1]['path'],line=source[:source.index(needle)].count('\n')+1,claim='Original global checkpoint key is retained exactly.',sourceExpression=needle))
r['receiptCorrection']='Spells the original checkpoint key exactly; prior source-review.json and its manifest retained unchanged. No source or behavior change.'
for row in r['primaryInputs']+r['referenceInputs']:assert sha(Path(row['path']))==row['sha256Bytes']
dump(H/'source-review-final.json',r)
raw=[]
for p in sorted(H.rglob('*')):
 if p.is_file()and p.name!='frozen-review-final.json':raw.append(dict(path=p.relative_to(H).as_posix(),sha256Bytes=sha(p),size=len(read(p))))
dump(H/'frozen-review-final.json',dict(rawArtifacts=raw,rawCount=len(raw),reviewOnly=True,sourceReview='source-review-final.json',sourceReviewSHA256Bytes=sha(H/'source-review-final.json')))
print(json.dumps(dict(sourceChecks=len(r['sourceChecks']),sourceReviewSHA256Bytes=sha(H/'source-review-final.json'),manifestSHA256Bytes=sha(H/'frozen-review-final.json'),rawCount=len(raw))))
