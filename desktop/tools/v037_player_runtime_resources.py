"""Original player input observer and bitmap resource on the same Windows ports."""
from pathlib import Path
import hashlib
import json

COMMIT = '8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
ARCHIVE = Path('desktop/upstream-slices/v037-player-runtime-resources')
MANIFEST_SHA = '49872b947de5b8a63ae3958f4c367683cc50d2b0cde354021f1d44d07d9718c7'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoInputDiagnostics.kt', 'filename': 'VideoInputDiagnostics.kt', 'bytes': 4092, 'sha256Bytes': '3575dad6c3ac52e3aa784cacba1d0ddda679fd27b7021ddf603ce1b5117eac34', 'gitBlob': '403890fea22f4ed0721d0a5878a6854664592a21'}, {'path': 'app/src/main/res/drawable-nodpi/ic_pili_tv_play.png', 'filename': 'ic_pili_tv_play.png', 'bytes': 2367, 'sha256Bytes': '17e9e663fc2cf3c8f8d663a8d1836360dc12600fa4fb36e839849311b7af4551', 'gitBlob': 'b5d0299c5861828310407147445d868a3cb1efd7'}]


def fixed_bytes(repo, filename):
    root = Path(repo) / ARCHIVE
    data = (root / 'manifest.json').read_bytes()
    assert hashlib.sha256(data).hexdigest() == MANIFEST_SHA
    manifest = json.loads(data)
    assert manifest['fixedUpstreamCommit'] == COMMIT and manifest['files'] == PINS
    row = next(item for item in PINS if item['filename'] == filename)
    raw = (root / filename).read_bytes()
    assert len(raw) == row['bytes'] and hashlib.sha256(raw).hexdigest() == row['sha256Bytes'], 'Fixed runtime resource bytes changed'
    assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == row['gitBlob']
    return raw, row


def emit(repo, output, outputs):
    from v029_comment_search import whole_proof
    raw, row = fixed_bytes(repo, 'VideoInputDiagnostics.kt')
    fixed_bytes(repo, 'ic_pili_tv_play.png')
    original = raw.decode('utf-8')
    assert original.count('import android.os.SystemClock\n') == 1
    assert original.count('SystemClock.elapsedRealtime()') == 1
    adapted = original.replace('import android.os.SystemClock\n', '').replace(
        'SystemClock.elapsedRealtime()', '(System.nanoTime() / 1_000_000L)')
    relative = 'com/android/purebilibili/feature/video/screen/VideoInputDiagnostics.kt'
    path = Path(output) / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(adapted.encode())
    assert not any(item['path'] == relative for item in outputs)
    outputs.append(dict(path=relative, sha256LF=hashlib.sha256(adapted.encode()).hexdigest(),
                        mode='whole-original-input-observer-Windows-monotonic-clock', generated=True,
                        source=dict(row, fixedCommit=COMMIT)))
    proof = whole_proof(original, adapted)
    proof.update(fixedCommit=COMMIT, originalSource=row, originalPointerPassesAndNonConsumptionRetained=True,
                 originalSizeLogThrottleRetained=True, sameOwnedDiagnosticConsent=True, sourceQualified=False)
    (Path(output) / 'v037-video-input-diagnostics-platform-proof.json').write_bytes(
        (json.dumps(proof, ensure_ascii=False, indent=2) + '\n').encode())
