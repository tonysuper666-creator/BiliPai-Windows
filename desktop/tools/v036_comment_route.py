"""Fixed original v0.3.6 comment read route and complete short-term memo."""
from pathlib import Path
import hashlib,json
COMMIT = '3608e0347ba3314ac74190d724d78598fdbad879'
MANIFEST_SHA = 'dbc0622e79f5005183c3c3b67f836befc8ac29bc8f967e42ea3f56e9ebc0a35d'
PINS = [{'path': 'core-data/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt', 'filename': 'CommentRepository.kt', 'commit': '3608e0347ba3314ac74190d724d78598fdbad879', 'bytes': 77439, 'sha256Bytes': 'b122acc8bd2b3efed20994c48a0a7833a9b528fccee4cbd64f13574951f23158', 'gitBlob': 'b8a98a07706e026f379e209fe74e60f13bc86dcc', 'mode': 'runtime-selected-read-members'}, {'path': 'core-data/src/main/java/com/android/purebilibili/data/repository/CommentReadRouteMemo.kt', 'filename': 'CommentReadRouteMemo.kt', 'commit': '3608e0347ba3314ac74190d724d78598fdbad879', 'bytes': 2188, 'sha256Bytes': 'cd094150334c57f2cc8cf9fe146c5cc980e549bc6d55b91648034dcb269f6e02', 'gitBlob': '77774daa0ef3303628413a9a662efe4b0378b787', 'mode': 'runtime-complete-original-file'}]
def digest(raw):return hashlib.sha256(raw).hexdigest()
def read_sources():
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v036-comment-route'
    manifest_raw=(archive/'manifest.json').read_bytes()
    if digest(manifest_raw)!=MANIFEST_SHA:raise ValueError('Fixed comment route manifest changed')
    manifest=json.loads(manifest_raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['hashNormalization']!='raw' or manifest['files']!=PINS:raise ValueError('Unknown comment route source contract')
    result={}
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw)!=row['bytes'] or digest(raw)!=row['sha256Bytes'] or blob!=row['gitBlob']:raise ValueError('Fixed comment route raw source mismatch: '+row['path'])
        result[row['filename']]=raw.decode('utf-8')
    return result
def select_read(canonical):
    source=read_sources()['CommentRepository.kt']
    row=PINS[0]
    return source,row['path'],dict(fixedUpstreamTag='v0.3.6',fixedUpstreamCommit=COMMIT,source=row,canonicalBeforeSHA256LF=digest(canonical.encode()),completeReadMembers=True,fullRepositoryObjectPorted=False)
def generate_memo(output):
    source=read_sources()['CommentReadRouteMemo.kt']
    path=output/'generated/com/android/purebilibili/data/repository/DesktopOriginalCommentReadRouteMemo.kt'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(source.encode('utf-8'))
    return path
