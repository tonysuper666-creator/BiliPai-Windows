"""Fixed complete socket/client/decoder bodies, sole existing media producer."""
from pathlib import Path
import hashlib
import json

COMMIT = '0e2206a85e288ba08f361cc636fab0710c2b9ab8'
ROOT = Path(__file__).resolve().parents[1] / 'upstream-slices/v030-live-danmaku'
BASE = 'app/src/main/java/com/android/purebilibili/core/network/socket/'
TEST = 'app/src/test/java/com/android/purebilibili/core/network/socket/'
PINS = {
    'LiveDanmakuClient.kt': ('f4691666373985bddb636a5edef644d7069607c66fee3df2a6f7c683a68afc95', 'd6080d88fd9be1a15fab5da09ec5550065542775'),
    'LiveDanmakuConnectionHealthPolicy.kt': ('4fee3694bbbd680183c739269d3bed1ee12181b81e530506267067e9807a2f6d', 'a01d9589069b5f1181479f056b64b14228131396'),
    'DanmakuProtocol.kt': ('74fabb3e1a8795c2c98c878f9b4074a6c9d603979c3bdf49721415f38deef0d0', 'a68f8d675a4b800f3f2b50cefec5270cc68dafd1'),
    'LiveDanmakuClientTest.kt': ('4cdb04a4dea3209dadb367b7bd4714bbb329bd521806f917080fdbd16dea7e49', '54b3aad9aa38cebd9cf46048340249d56776ceef'),
    'LiveDanmakuConnectionHealthPolicyTest.kt': ('0389f6a899456df30f501edaa81254475eaade068e3497043c765d36055149af', '46903fbe980a36c6eed46668770670030f4958f9'),
    'DanmakuProtocolLimitsTest.kt': ('1feb823d3053a69e7648b144b6bf0616fea5c3e65fc38bacdcf77bbd98f8b6ba', 'c498f59c1d1fdf6b0db433fae740f6f2f1184d04'),
}

def sources():
    manifest = json.loads((ROOT / 'manifest.json').read_bytes())
    assert manifest['upstreamCommit'] == COMMIT
    assert len(manifest['sources']) == len(PINS)
    rows = {row['file']: row for row in manifest['sources']}
    assert set(rows) == set(PINS)
    result = {}
    for name, pin in PINS.items():
        row = rows[name]
        raw = (ROOT / name).read_bytes()
        path = (TEST if name.endswith('Test.kt') else BASE) + name
        actual = (hashlib.sha256(raw).hexdigest(), hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        assert actual == pin == (row['sha256'], row['gitBlob'])
        assert row['path'] == path and row['bytes'] == len(raw)
        assert b'\r' not in raw and not raw.startswith(b'\xef\xbb\xbf')
        result[name] = raw.decode('utf8')
    return result

def adapt_client(original):
    body = original
    edits = []
    for before, after in [
        ('import android.os.SystemClock\n', ''),
        ('import com.android.purebilibili.core.network.NetworkModule\n', ''),
        ('SystemClock.elapsedRealtime()', 'System.nanoTime() / 1_000_000L'),
        ('private val webSocketFactory: WebSocket.Factory = NetworkModule.okHttpClient,',
         'private val webSocketFactory: WebSocket.Factory,'),
    ]:
        assert body.count(before) == 1
        offset = body.index(before)
        body = body[:offset] + after + body[offset + len(before):]
        edits.append(dict(offset=offset, before=before, after=after))
    restored = body
    for row in reversed(edits):
        at = row['offset']; after = row['after']
        assert restored[at:at + len(after)] == after
        restored = restored[:at] + row['before'] + restored[at + len(after):]
    assert restored == original
    return body, edits

def emit_socket(repo, output, test_output=None):
    fixed = sources()
    outputs = []
    proofs = []
    for name, original in fixed.items():
        is_test = name.endswith('Test.kt')
        if is_test and test_output is None:
            continue
        body, edits = adapt_client(original) if name == 'LiveDanmakuClient.kt' else (original, [])
        root = test_output if is_test else output
        target = Path(root) / 'com/android/purebilibili/core/network/socket' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body.encode('utf8'))
        outputs.append(target)
        proofs.append(dict(target=target.relative_to(root).as_posix(), test=is_test,
            sourcePath=(TEST if is_test else BASE) + name, upstreamCommit=COMMIT,
            rawSha256=PINS[name][0], gitBlob=PINS[name][1],
            generatedSha256=hashlib.sha256(body.encode()).hexdigest(),
            fullSourceInverseVerified=True, edits=edits,
            originalTestCount=original.count('@Test') if is_test else None,
            retainedTestCount=body.count('@Test') if is_test else None))
    Path(output).mkdir(parents=True, exist_ok=True)
    (Path(output) / 'v030-live-danmaku-source-proof.json').write_bytes(
        (json.dumps(dict(upstreamCommit=COMMIT, sources=proofs,
            sharedExistingOkHttp=True, originalAlgorithmsUnchanged=True), indent=2) + '\n').encode())
    return outputs
