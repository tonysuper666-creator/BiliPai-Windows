"""Fixed complete v0.3.6 video comment VM and bounded in-memory cache."""
from pathlib import Path
import hashlib,json
COMMIT='3608e0347ba3314ac74190d724d78598fdbad879'
MANIFEST_SHA='18c1f6fb5afb2d0b34beee73d22ce51159e9ced3cc5526bfa56aa81c9f2bd3bf'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt', 'filename': 'VideoCommentViewModel.kt', 'bytes': 63779, 'sha256Bytes': 'ffd867ca4aec758cc498370229b738703cea4abad7f7c32d92a0cea650880675', 'gitBlob': 'f20224fcdb771f69a624ffa54a452c73edcf3d91'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentLoadPolicy.kt', 'filename': 'VideoCommentLoadPolicy.kt', 'bytes': 4070, 'sha256Bytes': 'e4bc9991467171b415ad4a8d0ae0584b55b234a1f6666fc70bfe63ed20097961', 'gitBlob': 'acc65e8a6f0d312780a022cbfe5981f31e4a6091'}, {'path': 'core-data/src/main/java/com/android/purebilibili/data/repository/CommentPaginationPolicy.kt', 'filename': 'CommentPaginationPolicy.kt', 'bytes': 4241, 'sha256Bytes': '41b8436bb1e027f01880270bbabbfab0b883ae86f027dc8314fa14bb726d515d', 'gitBlob': 'df533b338d6a7c20c80f890d41d2321e53bb46ff'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/CommentPaginationPolicy.kt', 'filename': 'VideoCommentPaginationPolicy.kt', 'bytes': 934, 'sha256Bytes': '8ee3360046b1b8a7e5c989c79d152c5c236edb01fad964430bce82388423b523', 'gitBlob': '765a21976b4c0de9a59d14a6f704c0892205e8d2'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/SubReplySortPolicy.kt', 'filename': 'SubReplySortPolicy.kt', 'bytes': 941, 'sha256Bytes': 'f023f49f8f7b9a91308ba46dcf9a2d20be36131693b08f885bafa9e1d3591655', 'gitBlob': 'c626b7dacfdd59e6390c0307cb0276fea0ec58d4'}]
def digest(raw):return hashlib.sha256(raw).hexdigest()
def read_sources():
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v036-video-comment-cache'
    raw=(archive/'manifest.json').read_bytes()
    if digest(raw)!=MANIFEST_SHA:raise ValueError('Fixed video comment cache manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['files']!=PINS:raise ValueError('Unknown video comment cache source contract')
    sources={}
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if digest(raw)!=row['sha256Bytes'] or len(raw)!=row['bytes'] or blob!=row['gitBlob']:raise ValueError('Fixed video comment cache raw source changed')
        sources[row['filename']]=raw.decode('utf8')
    return sources
def select_vm(canonical):
    source=read_sources()['VideoCommentViewModel.kt']
    return source,dict(fixedUpstreamTag='v0.3.6',fixedUpstreamCommit=COMMIT,source=PINS[0],canonicalBeforeSHA256LF=digest(canonical.encode()),wholeOriginalSelected=True)
def generate_cache(output):
    source=read_sources()['VideoCommentLoadPolicy.kt']
    path=output/'generated/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoCommentLoadPolicy.kt'
    path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(source.encode())
    counts=output/'generated/com/android/purebilibili/data/repository/DesktopOriginalCommentPaginationPolicy.kt'
    counts.parent.mkdir(parents=True,exist_ok=True);counts.write_bytes(read_sources()['CommentPaginationPolicy.kt'].encode())
    for name,target in [('SubReplySortPolicy.kt','DesktopOriginalSubReplySortPolicy.kt')]:
        wrapped=output/'generated/com/android/purebilibili/feature/video/viewmodel'/target
        wrapped.parent.mkdir(parents=True,exist_ok=True);wrapped.write_bytes(read_sources()[name].encode())
    return path
