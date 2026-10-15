"""Complete fixed v041 dynamic comment state and original row identity consumer."""
from pathlib import Path
import hashlib,json
COMMIT='d18fb20e31adb11bbec9fae6dfce7d705d556217'
MANIFEST_SHA256='a6813ad26dd341def7b7d72b0006bd763cd4c2fea3128cdc4f56de18389ce64c'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt', 'archiveFile': 'DynamicViewModel.kt', 'bytes': 118402, 'sha256': '9b53a2d7ea025dab4d758808d536b90e13be433bcfc47e084cc1cc8de33c1f98', 'gitBlob': 'a01d4e21ebc15c1b148070890c9787afa2f1a3a9', 'referenceOnly': False}, {'path': 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt', 'archiveFile': 'DynamicCommentSheet.kt', 'bytes': 79980, 'sha256': '257078db07bce00bf80f8b691d80ffbf49d830b3df791c28d79ce9f675aeb1c4', 'gitBlob': '0cf709b58fd88252c74e382bb37485aa5a32d699', 'referenceOnly': False}]
SLICE='desktop/upstream-slices/v041-dynamic-comment-identity'
def originals():
    archive=Path(__file__).resolve().parents[1]/'upstream-slices/v041-dynamic-comment-identity'
    raw=(archive/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA256:raise ValueError('Fixed dynamic comment identity manifest changed')
    manifest=json.loads(raw)
    if manifest.get('commit')!=COMMIT or manifest.get('files')!=PINS:raise ValueError('Fixed dynamic comment identity closure changed')
    result={}
    for row in PINS:
        raw=(archive/row['archiveFile']).read_bytes()
        if (len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256']
                or hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=row['gitBlob']):
            raise ValueError('Fixed dynamic comment identity original changed')
        result[row['path']]=raw.decode('utf-8')
    return result
def dynamic_view_model():
    return originals()['app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt']
def dynamic_comment_sheet():
    return originals()['app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt']
