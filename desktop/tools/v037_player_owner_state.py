"""Fixed complete original v037 player owner/state inputs."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='e0d6f1455e4df8156ea7767efe17fbd660c87d14ae468a945998b6c2894a90b6'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt', 'filename': 'VideoPlayerSection.kt', 'bytes': 323390, 'sha256Bytes': '161cbcfe415ca6c5b0df477bda6fa66253a0c7f3f6dce12a17bf08fb971d1c9b', 'gitBlob': '80508f409a1c884289c060ab703cf5a49b153640'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionContracts.kt', 'filename': 'VideoPlayerSectionContracts.kt', 'bytes': 7877, 'sha256Bytes': '6a3da30635b753ec8adbe2c7fdfe463483401c142a41ba7c5727cc7adfd0214a', 'gitBlob': '57a6a08e75ce680064e260408fe329cf45a2d83d'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt', 'filename': 'VideoEngagementViewModel.kt', 'bytes': 33372, 'sha256Bytes': '76c8a05fe6ee2901a4b98a04c6ccb1f80c08c4ffd4440e6961219303a74de5b2', 'gitBlob': 'f0c158f6b4ab1be491750be50020a63ba64748b0'}]

def fixed_sources():
    root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-player-owner-state'
    raw=(root/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Fixed owner-state manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['files']!=PINS:raise ValueError('Unknown owner-state closure')
    result={}
    for row in PINS:
        raw=(root/row['filename']).read_bytes()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=row['gitBlob']:raise ValueError('Fixed owner-state source changed')
        result[row['path']]=raw.decode('utf-8')
    return result

def selected_source(path,canonical):
    return fixed_sources().get(path,canonical)
