"""Complete fixed original video comment tab and stable list slots."""
from pathlib import Path
import hashlib,json
COMMIT='3608e0347ba3314ac74190d724d78598fdbad879'
MANIFEST_SHA='4244f31ce6fca7048f3c4e4c8ac031541cb6225622a1c13abe27a4f4be8828f7'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoCommentSkeletonSlots.kt', 'filename': 'VideoCommentSkeletonSlots.kt', 'bytes': 2991, 'sha256Bytes': '892db4df95aec372bd99153348f5aafa831b762f0eded68eca466d4777fbb02d', 'gitBlob': 'dc30bace7697417f6abda3df22021cfae1f8986f'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoCommentSheetHost.kt', 'filename': 'VideoCommentSheetHost.kt', 'bytes': 53276, 'sha256Bytes': 'e49e06b6dbc936399f689736207edaaa90deb47a1bc26e723519b9594b089445', 'gitBlob': 'e2b2247a08dec1aff72faa35d0b107bdf4395e86'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoCommentRefreshContainer.kt', 'filename': 'VideoCommentRefreshContainer.kt', 'bytes': 904, 'sha256Bytes': '38865ed5d0b7e162afaf69341cc320d5eb03b03bc848da688b686dce1edc8162', 'gitBlob': '8bb326d9f2a85bece2bb43d0253536ce3a745091'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoContentSection.kt', 'filename': 'VideoContentSection.kt', 'bytes': 102518, 'sha256Bytes': 'c1732422eddf6a06676b77f3d53cee6a9af936a144b35c9f6d6a16c798b18786', 'gitBlob': '1a218758c5d7686d58034f7130f93454510c142f'}]

def fixed_sources():
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v036-video-comment-viewport'
    raw=(archive/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Fixed video viewport manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['files']!=PINS:raise ValueError('Unknown video viewport source contract')
    sources={}
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or blob!=row['gitBlob']:raise ValueError('Fixed original video viewport changed')
        sources[row['path']]=raw.decode('utf-8')
    return sources

def generate_slots(output):
    sources=fixed_sources();result=[]
    for name in ['VideoCommentSkeletonSlots.kt','VideoCommentRefreshContainer.kt']:
        row=next(r for r in PINS if r['filename']==name)
        target=output/'com/android/purebilibili'/row['path'].split('com/android/purebilibili/')[1]
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(sources[row['path']].encode());result.append(target)
    return result
