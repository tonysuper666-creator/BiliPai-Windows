from pathlib import Path
import difflib
import hashlib
import json
import subprocess

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/stable-original-comment-detail-root-parity'
routes = main / 'desktop/.local/stable-settings-native-route-delta'

def wide(path):
    value = str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def read(path):
    return wide(path).read_bytes()
def sha(raw):
    return hashlib.sha256(raw).hexdigest()
def lf(raw):
    return raw.replace(b'\r\n', b'\n')
def git(*args):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=candidate)
def apply(text, hunks):
    original = text
    for hunk in hunks:
        assert sha(hunk['before'].encode()) == hunk['beforeSha256LF'], hunk['name']
        assert sha(hunk['after'].encode()) == hunk['afterSha256LF'], hunk['name']
        assert text.count(hunk['before']) == 1, hunk['name']
        text = text.replace(hunk['before'], hunk['after'])
    inverse = text
    for hunk in reversed(hunks):
        assert inverse.count(hunk['after']) == 1, hunk['name']
        inverse = inverse.replace(hunk['after'], hunk['before'])
    assert inverse == original
    return text

assert not (root / 'installation.json').exists()
assert not git('status', '--porcelain').strip()
head = git('rev-parse', 'HEAD').decode().strip()
assert head == 'a5ba7265ffcc0157cd9cdab768e0a211fa9e4478'
frozen_raw = read(packet / 'frozen-handoff.json')
assert sha(frozen_raw) == '805ca6ce53ae427a24ce1d38adce7e6d5930b22d86d5912e50d8b319b41b5bcc'
frozen = json.loads(frozen_raw)
assert frozen['candidateHead'] == head and frozen['rawArtifactCount'] == len(frozen['rawArtifacts']) == 123
for row in frozen['rawArtifacts']:
    raw = read(packet / row['path'])
    assert len(raw) == row['size'] and sha(raw) == row['sha256Bytes'], row['path']
contract_raw = read(packet / 'install-contract.json')
assert sha(contract_raw) == 'cd86ff9d40e2cd068cfc69003730c2b77ddf0b2cd734dbc053aa7b41d21bdf53'
contract = json.loads(contract_raw)
assert contract['candidateHead'] == head
assert not any(contract[field] for field in ['registryEdits', 'gradleEdits', 'newDependencies', 'newResources'])
hunk_raw = read(packet / contract['exactHunks'])
assert sha(hunk_raw) == 'd1121a8c79e35471df935ed01f0a6bcd2f6ecaba0a631a0a3bbd95faf8a260c0'
hunks = json.loads(hunk_raw)
families = json.loads(read(packet / contract['targets']))
assert len(hunks) == 16 and len(families) == 6
assert {h['path'] for h in hunks} == {f['path'] for f in families}
before = {}
outputs = {}
for family in families:
    path = family['path']
    before[path] = read(candidate / path)
    text = lf(before[path]).decode('utf-8')
    assert sha(text.encode()) == family['beforeSha256LF'], path
    text = apply(text, [h for h in hunks if h['path'] == path])
    assert sha(text.encode()) == family['afterSha256LF'], path
    outputs[path] = text
assert len(contract['copyWhitelist']) == 2
for row in contract['copyWhitelist']:
    path = row['target']
    assert not wide(candidate / path).exists()
    raw = read(packet / row['source'])
    assert sha(raw) == row['sha256Bytes']
    before[path] = None
    outputs[path] = lf(raw).decode('utf-8')

route_raw = read(routes / 'prepared-route-delta.json')
assert sha(route_raw) == '9a4a5d44ab013da8afea43f437299cb32c0a2abce142a73210d77a0eef583887'
route_delta = json.loads(route_raw)
shell_path = route_delta['hunk']['path']
assert route_delta['baseCommit'] == head
assert sha(lf(before[shell_path])) == route_delta['baseFamilySha256LF']
comment_shell = outputs[shell_path]
outputs[shell_path] = apply(comment_shell, [route_delta['hunk']])
assert apply(lf(before[shell_path]).decode(), [route_delta['hunk']]).count(route_delta['hunk']['after']) == 1
# The direct settings hunk is disjoint from all frozen comment hunks.
alternate = apply(lf(before[shell_path]).decode(), [route_delta['hunk']])
alternate = apply(alternate, [h for h in hunks if h['path'] == shell_path])
assert alternate == outputs[shell_path]

changes = []
for path, text in outputs.items():
    old = before[path]
    raw = text.replace('\n', '\r\n').encode() if old is not None and b'\r\n' in old else text.encode()
    assert old != raw
    changes.append((path, old, raw))
# Preflight of all preserved bytes and inverse deltas precedes the first product write.
receipt = dict(baseCommit=head, frozenManifestSha256Bytes=sha(frozen_raw), verifiedArtifacts=123,
               exactCommentHunks=16, settingsHunks=1, newManualFiles=2,
               sourceCount=1180, resourceCount=243, generatedOrBinaryProductCopies=False,
               normalCompileAccepted=False, rootRuntimeAccepted=False,
               networkAccepted=False, desktopPackageUpdated=False,
               sourceTargets=[])
diff = []
for path, old, raw in changes:
    destination = candidate / path
    assert destination.resolve().is_relative_to(candidate.resolve())
    assert (read(destination) if wide(destination).exists() else None) == old
    if old is not None:
        backup = root / 'before' / path
        wide(backup.parent).mkdir(parents=True, exist_ok=True)
        wide(backup).write_bytes(old)
    wide(destination.parent).mkdir(parents=True, exist_ok=True)
    wide(destination).write_bytes(raw)
    assert read(destination) == raw
    diff.extend(difflib.unified_diff(lf(old or b'').decode().splitlines(True),
                                   lf(raw).decode().splitlines(True), fromfile=path, tofile=path))
    receipt['sourceTargets'].append(dict(path=path,
        beforeSha256Bytes=sha(old) if old is not None else None,
        afterSha256Bytes=sha(raw), afterSha256LF=sha(lf(raw))))
(root / 'source-diff.patch').write_text(''.join(diff), encoding='utf-8', newline='\n')
(root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(productTargets=len(changes), commentHunks=16, settingsHunks=1,
                     normalCompileAccepted=False, rootRuntimeAccepted=False)))
