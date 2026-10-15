"""Fixed complete original shared player glass material inputs."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='e891a4b7b7c6986005778d4728b96c6b8fceba51fc0447d5e5da8242b7812e6b'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/FloatingDockChrome.kt', 'filename': 'FloatingDockChrome.kt', 'bytes': 19598, 'sha256Bytes': 'a63c905461965acc4093921217e50eb007a075a851444d9f8fd9e7900a55dca8', 'gitBlob': '879224b839663db620bdbacff10604da90cf942e'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarMatchedLiquidChrome.kt', 'filename': 'BottomBarMatchedLiquidChrome.kt', 'bytes': 25117, 'sha256Bytes': '6f5dbf1136a5795e4ec671461bc972227b63d0a0a869406252ef7db8cfb18522', 'gitBlob': 'dae7554a3cbd13b30956b6edcac1dff61690c120'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppTopBarIconDecoration.kt', 'filename': 'AppTopBarIconDecoration.kt', 'bytes': 878, 'sha256Bytes': 'f232c8cd3ac5e0bb71c9e47ca425c9e6670ea4c95b801c7a2142d4783137e2b9', 'gitBlob': 'c86b4f39854f305833d851d3c0012a6922a9ed2f'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/BottomBar.kt', 'filename': 'BottomBar.kt', 'bytes': 218034, 'sha256Bytes': 'a18376a4aad30a2ec7a2e044d96bb2ec86dca098f30dda860ebde794061433f1', 'gitBlob': '01dabfba5885b3335a2758f299f5b9e19d6c9676'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/miuix/InteractiveHighlight.kt', 'filename': 'InteractiveHighlight.kt', 'bytes': 3299, 'sha256Bytes': 'a069a4065917180901a61b76208cc6e4cac38197edd6c96a9ef36eee0e62c7ed', 'gitBlob': '8a985da3ab4f7957e7fb2ef2ce15faff828c3c72'}]

def fixed_sources():
    root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-player-shared-material'
    raw=(root/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Fixed shared material manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['files']!=PINS:raise ValueError('Unknown shared material source closure')
    result={}
    for row in PINS:
        raw=(root/row['filename']).read_bytes()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=row['gitBlob']:raise ValueError('Fixed original shared material changed')
        result[row['path']]=raw.decode('utf-8')
    return result

def selected_source(path,canonical):
    from v040_native_chrome import selected_source as native_chrome_source
    return native_chrome_source(Path(__file__).resolve().parents[2],path,fixed_sources().get(path,canonical))

def identity_for(path):
    from v040_native_chrome import identity_for as native_chrome_identity
    newest=native_chrome_identity(Path(__file__).resolve().parents[2],path)
    if newest is not None:return newest
    row=next((r for r in PINS if r['path']==path),None)
    if row is None:return None
    return dict(pinnedCommit=COMMIT,originalPath=path,
                path='desktop/upstream-slices/v037-player-shared-material/'+row['filename'],
                sha256Bytes=row['sha256Bytes'],gitBlobOid=row['gitBlob'],bytes=row['bytes'])

def generate_decoration(output):
    sources=fixed_sources()
    row=next(r for r in PINS if r['filename']=='AppTopBarIconDecoration.kt')
    target=output/'com/android/purebilibili/core/ui/components/AppTopBarIconDecoration.kt'
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(sources[row['path']].encode('utf-8'))
    return target
