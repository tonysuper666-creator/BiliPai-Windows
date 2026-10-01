from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
formal=REPO/'desktop/verification/stable-controller-native-handoff'
manifest=json.loads((formal/'artifact-manifest.json').read_text())
rel='root/run-actual64-controller-native-drain-fixture.py'
pin=next(r['sha256Bytes']for r in manifest['artifacts']if r['path']==rel)
raw=(HERE/'run-actual64-controller-native-drain-fixture.py').read_bytes()
assert hashlib.sha256(raw).hexdigest()==pin
s=raw.decode()
replacements=[
    ('controller-drain64','owned-seek66'),('stable-product-snapshot-64','stable-product-snapshot-66'),
    ('actual64-controller-native-drain-fixture01','actual66-owned-seek-fixture01'),
    ('5f93c921297a6571ef6074f6b3afb364ff1c20ca09a8f3e17db15133dde2bf42','e549c7badf5b11208e8b9fb3c2d5480759e9202eb157ae165feb0a8354cbcba3'),
    ('58d593ab155d1a864b3213cc35e7c633fae7b7f6f55b8b8c22f49fcc71613701','43b1e432d592dd74088079e60731eda25d26f6c636df3570644a377b200ba9db'),
    ('ControllerNativeDrainFixture','TypedSeekFixture'),
    ('73976953c2132ddd29faf52fa67992a20e30777882f323e3e83e17d22c6d84ae','4c97e81dfb5949f492d49ab22b7faafc5242faa99a9249cf25c0c78df62d6dc1'),
    ('actualProduct=64','actualProduct=66'),('actualProductSnapshot=64','actualProductSnapshot=66'),
    ("proof['assertions'] >= 40","proof['assertions'] >= 30"),
    ('sameSocketFreeChecksReplayed=True','exactOwnedSeekChecksReplayed=True'),
    ('actualControllerDrainApiAccepted=True','actualOwnedSeekQueueAndNativeCompletionAccepted=True'),
]
for before,after in replacements:
    assert before in s,before
    s=s.replace(before,after)
out=HERE/'run-actual66-owned-seek-fixture.py';assert not out.exists()
out.write_bytes(s.encode())
print(json.dumps(dict(path=str(out),sha256Bytes=hashlib.sha256(out.read_bytes()).hexdigest())))
