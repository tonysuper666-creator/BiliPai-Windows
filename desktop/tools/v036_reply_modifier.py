"""Exact original v035-to-v036 caller modifier delta on the sole reply renderer."""
from pathlib import Path
import hashlib,json
MANIFEST_SHA='00b356d5780f3e748b80049f2d9561033233f8cc93a3c5d2e019bed6bc40de4d'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyComponents.kt', 'filename': 'ReplyComponents-v035.kt', 'commit': '46eaf277e83512061785be9759d1f07aba3c4839', 'bytes': 132248, 'sha256Bytes': '8a128369a6af9e8c09ed6996f5347c1de4028e7a57a5ec82123f24d5cfe352a8', 'gitBlob': '3fdff5845a1c95289294d37e07febf31af4d0ebe'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyComponents.kt', 'filename': 'ReplyComponents-v036.kt', 'commit': '3608e0347ba3314ac74190d724d78598fdbad879', 'bytes': 132283, 'sha256Bytes': '68d7f0d728dc7e8df94b890f226b19a59ab6d116006a87c1d45b8a2c80dde3b2', 'gitBlob': 'c0572526a30d0e175e14fc75652e3878debc8451'}]

def apply(body):
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v036-reply-modifier'
    raw=(archive/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Reply modifier manifest changed')
    manifest=json.loads(raw)
    if manifest['files']!=PINS:raise ValueError('Unknown reply modifier source contract')
    texts=[]
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or blob!=row['gitBlob']:raise ValueError('Original reply modifier bytes changed')
        texts.append(raw.decode('utf-8'))
    edits=[('fun ReplyItemView(\n    item: ReplyItem,','fun ReplyItemView(\n    modifier: Modifier = Modifier,\n    item: ReplyItem,'),
        ('    Box(\n        modifier = Modifier\n            .fillMaxWidth()\n            .background(appearance.panelColor)',
         '    Box(\n        modifier = modifier\n            .fillMaxWidth()\n            .background(appearance.panelColor)')]
    original=texts[0]
    for before,after in edits:
        if original.count(before)!=1:raise ValueError('Original reply modifier delta seam changed')
        original=original.replace(before,after,1)
    if original!=texts[1]:raise ValueError('Reply modifier delta differs from complete original')
    before_body=body
    for before,after in edits:
        if body.count(before)!=1:raise ValueError('Sole Windows reply modifier admission seam changed')
        body=body.replace(before,after,1)
    from v029_comment_search import whole_proof
    return body,dict(previousUpstreamCommit=PINS[0]['commit'],fixedUpstreamCommit=PINS[1]['commit'],
        exactWholeOriginalDelta=True,existingRendererCompleteInverse=whole_proof(before_body,body),
        originalRendererBusinessUnchanged=True,originalCallerModifierAppliedToRootContainer=True)
