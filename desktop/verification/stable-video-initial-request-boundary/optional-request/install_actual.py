import hashlib
import json
from pathlib import Path

packet = Path(__file__).resolve().parent
candidate = packet.parents[3] / 'BiliPai-v023'
assert candidate.is_dir(), candidate
receipt = packet / 'installation-01.json'
assert not receipt.exists(), 'Do not overwrite an installation receipt'
digest = lambda data: hashlib.sha256(data).hexdigest()
handoff_bytes = (packet / 'frozen-handoff.json').read_bytes()
assert digest(handoff_bytes) == 'c64183644c7841f9a3d69fee44ff9691816c1d2cf3cf0b82926e04491b02f185'
handoff = json.loads(handoff_bytes)
assert handoff['copyWhitelist'] == []
for artifact in handoff['artifacts']:
    data = (packet / artifact['path']).read_bytes()
    assert len(data) == artifact['bytes']
    assert digest(data) == artifact['sha256Bytes'], artifact['path']
hunks = json.loads((packet / handoff['exactHunks']).read_text('utf-8'))
targets = {hunk['target'] for hunk in hunks}
assert len(targets) == 1 and len(hunks) == 2
target_name = next(iter(targets))
target = (candidate / target_name).resolve()
assert target.is_relative_to(candidate.resolve())
original = target.read_bytes()
text = original.decode('utf-8').replace('\r\n', '\n')
assert digest(text.encode()) == handoff['beforeWholeSHA256LF']
before = text
for hunk in hunks:
    assert digest(text.encode()) == hunk['baselineWholeSHA256LF']
    assert text.count(hunk['before']) == hunk['count'] == 1
    assert digest(hunk['before'].encode()) == hunk['beforeAnchorSHA256LF']
    assert digest(hunk['after'].encode()) == hunk['afterAnchorSHA256LF']
    text = text.replace(hunk['before'], hunk['after'], 1)
    assert digest(text.encode()) == hunk['afterWholeSHA256LF']
assert digest(text.encode()) == handoff['afterWholeSHA256LF']
reverse = text
for hunk in reversed(hunks):
    assert reverse.count(hunk['after']) == 1
    reverse = reverse.replace(hunk['after'], hunk['before'], 1)
assert reverse == before
old_path = packet / 'installed-before-01.raw'
assert not old_path.exists()
old_path.write_bytes(original)
target.write_bytes(text.encode('utf-8'))
assert digest(target.read_bytes()) == handoff['afterWholeSHA256LF']
result = {
    'status': 'Installed exact optional-request delta; runtime acceptance pending',
    'target': target_name,
    'handoffSHA256': digest(handoff_bytes),
    'beforeSHA256Bytes': digest(original),
    'beforeSHA256LF': digest(before.encode()),
    'afterSHA256Bytes': digest(target.read_bytes()),
    'hunks': len(hunks),
    'reverseRestoresBaseline': True,
    'productionOverrides': 0,
}
receipt.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result))
