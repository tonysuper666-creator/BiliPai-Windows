from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
previous = HERE / 'run-actual66-owned-seek-fixture02.py'
raw = previous.read_bytes()
assert hashlib.sha256(raw).hexdigest() == '653aebc6cd3f3fd3b235f58318cbcb20d52346200569fc6b624a907a9ccce24b'
source = HERE / 'native-dispatch67/NativeDispatchFixture.kt'
source_pin = hashlib.sha256(source.read_bytes()).hexdigest()
body = raw.decode().replace('\r\n', '\n')
edits = [
    ("LANE = HERE / 'owned-seek66-02'", "LANE = HERE / 'native-dispatch67'"),
    ("SNAPSHOT = MAIN / 'desktop/.local/stable-product-snapshot-66'", "SNAPSHOT = MAIN / 'desktop/.local/stable-product-snapshot-67'"),
    ("OUT = HERE / 'actual66-owned-seek-fixture02'", "OUT = HERE / 'actual67-native-dispatch-fixture01'"),
    ('e549c7badf5b11208e8b9fb3c2d5480759e9202eb157ae165feb0a8354cbcba3', 'db10acafc95ee255a5c921f74d4646ace585998d67644cde53b53aac7cf54ef6'),
    ('43b1e432d592dd74088079e60731eda25d26f6c636df3570644a377b200ba9db', '25f8c144a4a8aeb5230beb25a927f294567abf019c6249173aec9967195e4807'),
    ("source_pin = 'b352e61ac2399c7379d3f2cac4823c964008a9500a8cb6d8b80668bd2f965b16'", "source_pin = '" + source_pin + "'"),
    ('return dict(actualProduct=66,', 'return dict(actualProduct=67,'),
    ('actualProductSnapshot=66,', 'actualProductSnapshot=67,'),
    ('exactOwnedSeekChecksReplayed=True,', 'actualCapturedDispatchChecksAccepted=result.returncode == 0, sponsorHistoryWritten=False,'),
    ('TypedSeekFixture', 'NativeDispatchFixture'),
]
for before, after in edits:
    expected = 4 if before == 'TypedSeekFixture' else 1
    assert body.count(before) == expected, (before, body.count(before))
    body = body.replace(before, after, expected)
target = HERE / 'run-actual67-native-dispatch-fixture01.py'
assert not target.exists()
target.write_bytes(body.encode())
(HERE / 'native-dispatch67/runner-creation.json').write_bytes((json.dumps(dict(
    parentRunnerPath=str(previous), parentRunnerSha256Bytes=hashlib.sha256(raw).hexdigest(),
    sourcePath=str(source), sourceSha256Bytes=source_pin,
    newRunnerPath=str(target), newRunnerSha256Bytes=hashlib.sha256(target.read_bytes()).hexdigest(),
    exactReplacements=[dict(before=a, after=b) for a,b in edits]), indent=2)+'\n').encode())
print(json.dumps(dict(sourceSha256Bytes=source_pin, runnerSha256Bytes=hashlib.sha256(target.read_bytes()).hexdigest())))
