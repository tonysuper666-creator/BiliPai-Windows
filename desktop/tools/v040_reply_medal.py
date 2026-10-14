"""Fixed whole original medal model, protobuf member parser and existing UI leaf."""
from pathlib import Path
import hashlib
import json
from v038_listen_viewmodel import block_end

COMMIT='415d01ec5bc3ac82521a9ac47bcc449b8e202460'
ARCHIVE='desktop/upstream-slices/v040-reply-medal'
PINS={'model': {'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/ResponseModels.kt', 'archiveFile': 'ResponseModels.kt', 'bytes': 23866, 'sha256': 'a033f938ff2480cff4fda62472a740ea3bb844994f0aba364ceb9256f1a54cb2', 'gitBlob': '0cad9fe13f81770dbe9098f6276d9ca3144a6446'}, 'grpc': {'path': 'core-data/src/main/java/com/android/purebilibili/data/repository/CommentGrpcRepository.kt', 'archiveFile': 'CommentGrpcRepository.kt', 'bytes': 30589, 'sha256': '399f34d801a0faaa5392b3f2e18f51e62a6403d4bf468a2c59226322a318fd18', 'gitBlob': 'afe259c922d9be9993d274db77f86e7dc1cb15d7'}, 'renderer': {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyComponents.kt', 'archiveFile': 'ReplyComponents.kt', 'bytes': 133676, 'sha256': '2ff30fc6b3bc79393334169ebbc0830f0c2e32988083905303e0c47990a2eac3', 'gitBlob': '75f98155ea8a1899192fbf6f4268a4fafec97712'}}
BASE_UNIT_SHA256_LF={'model': 'e988a1c46169e26142c04da57e4917367e29c829201bcdc4906258284d40a392', 'grpc': '9fcced2819bceb170155b184a46cb9990b8fe693adbf65758792661003fe120d', 'renderer': 'b57093debf8cb3603356f88dd129b8f3fd544fb72a5e7f874417424adfbb1341'}
MANIFEST_SHA256='a8c5435e897a976f000cc9207b5a0fb773b791c96da92b29e139dfbadc295240'

def sha(raw):return hashlib.sha256(raw).hexdigest()

def fixed_sources(repo):
    root=Path(repo)/ARCHIVE
    raw=(root/'manifest.json').read_bytes()
    if sha(raw)!=MANIFEST_SHA256:raise ValueError('Reply medal manifest changed')
    manifest=json.loads(raw)
    if manifest!=dict(repository='jay3-yy/BiliPai',commit=COMMIT,files=PINS):
        raise ValueError('Reply medal original inventory changed')
    result={}
    for kind,row in PINS.items():
        path=root/row['archiveFile']
        if path.is_symlink():raise ValueError('Linked reply medal source rejected')
        raw=path.read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if (len(raw),sha(raw),blob)!=(row['bytes'],row['sha256'],row['gitBlob']):
            raise ValueError('Reply medal original bytes changed')
        result[kind]=raw.decode('utf-8').replace('\r\n','\n')
    return result

def selection(body,kind):
    if kind=='model':
        marker='@Serializable\ndata class ReplyFansDetail('
        if body.count(marker)!=1:raise ValueError('Reply medal model anchor changed')
        start=body.index(marker);end=body.index('\n)',start)+2
        return body[start:end]
    marker='    private fun parseMember(' if kind=='grpc' else '@Composable\nprivate fun FansMedalTag('
    if body.count(marker)!=1:raise ValueError('Reply medal original member anchor changed')
    start=body.index(marker)
    return body[start:block_end(body,body.index('{',start))]

def advance(repo,body,kind):
    originals=fixed_sources(repo)
    before=selection(body,kind)
    if sha(before.encode())!=BASE_UNIT_SHA256_LF[kind]:
        raise ValueError('Existing sole reply medal unit changed')
    after=selection(originals[kind],kind)
    at=body.index(before)
    generated=body[:at]+after+body[at+len(before):]
    if generated[:at]+before+generated[at+len(after):]!=body:
        raise ValueError('Reply medal complete inverse failed')
    return generated,dict(upstreamCommit=COMMIT,source=PINS[kind]['path'],kind=kind,
        completeOriginalUnitSHA256LF=sha(after.encode()),previousUnitSHA256LF=sha(before.encode()),
        wholeOriginalUnitRetained=True,remainderUnchanged=True,offset=at,
        before=before,after=after,sourceQualified=False,mainCompiled=False,runtimeExecuted=False)
