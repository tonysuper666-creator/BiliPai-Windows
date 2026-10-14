"""Complete original gesture feedback, policy and resource palette on Windows."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

COMMIT = '8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
ARCHIVE = Path('desktop/upstream-slices/v037-gesture-feedback')
MANIFEST_SHA = '0c1ca58b5cd24d738ebb480b3d5737b60f2fb8dc43cce34413b57e08ac69846f'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/gesture/GestureLevelOverlay.kt', 'filename': 'GestureLevelOverlay.kt', 'bytes': 27647, 'sha256Bytes': '3d8e87b8571f4493eb2a9832ce221feb482890278fcde3c35bac7349a840cb05', 'gitBlob': '86c5d9b3bd4495a75d18c64790db5eff93545ee9'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/gesture/GestureLevelOverlayPolicy.kt', 'filename': 'GestureLevelOverlayPolicy.kt', 'bytes': 8791, 'sha256Bytes': '00a229d13620593a2d6468f0ecfc973bec5ccf86d0da2f0227b2e08f3d2024d2', 'gitBlob': '8e909382d847a21d5b756ccdaaac579b2d457650'}, {'path': 'app/src/main/res/values/colors.xml', 'filename': 'colors.xml', 'bytes': 860, 'sha256Bytes': '4c571973edde45f7d5a0422399ad909f67d9317c91393f0f7605dd0a2fc93391', 'gitBlob': '57128db92256ba0b6e76492f93af3453ad82bfce'}]
SOURCE_PATHS = (
    'app/src/main/java/com/android/purebilibili/feature/video/ui/gesture/GestureLevelOverlay.kt',
    'app/src/main/java/com/android/purebilibili/feature/video/ui/gesture/GestureLevelOverlayPolicy.kt',
)


def checked(repo):
    root = Path(repo) / ARCHIVE
    data = (root / 'manifest.json').read_bytes()
    assert hashlib.sha256(data).hexdigest() == MANIFEST_SHA
    manifest = json.loads(data)
    assert manifest['fixedUpstreamCommit'] == COMMIT and manifest['files'] == PINS
    result = {}
    for row in PINS:
        raw = (root / row['filename']).read_bytes()
        assert len(raw) == row['bytes'] and hashlib.sha256(raw).hexdigest() == row['sha256Bytes'], 'Fixed gesture feedback bytes changed'
        assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == row['gitBlob']
        result[row['path']] = (raw.decode('utf-8'), row)
    return result


def original(repo, path):
    text, row = checked(repo)[path]
    return text, dict(row, text=text, sha256LF=hashlib.sha256(text.encode()).hexdigest(),
                     fixedCommit=COMMIT, localPath=(ARCHIVE / row['filename']).as_posix())


def resource_delta(repo, body):
    source, _ = checked(repo)['app/src/main/res/values/colors.xml']
    colors = [item.text for item in ET.fromstring(source).findall('color')
              if item.attrib.get('name') == 'player_brightness_accent']
    assert len(colors) == 1 and colors[0].startswith('#') and len(colors[0]) == 9
    value = int(colors[0][1:], 16)
    before = body
    assert body.count('import androidx.compose.ui.res.colorResource\n') == 1
    assert body.count('import com.android.purebilibili.R\n') == 1
    assert body.count('colorResource(R.color.player_brightness_accent)') == 2
    body = body.replace('import androidx.compose.ui.res.colorResource\n', '').replace('import com.android.purebilibili.R\n', '')
    body = body.replace('colorResource(R.color.player_brightness_accent)', 'Color(0x' + format(value, '08X') + ')')
    from v029_comment_search import whole_proof
    proof = whole_proof(before, body)
    proof.update(fixedCommit=COMMIT, originalBrightnessArgb=value, actualBackdropInputRetained=True,
                 originalGestureFeedbackBodiesRetained=True, sourceQualified=False, pixelsValidated=False)
    return body, proof
