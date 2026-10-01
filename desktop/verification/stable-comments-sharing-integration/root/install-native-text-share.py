import hashlib
import json
from pathlib import Path

MAIN = Path(__file__).resolve().parents[3]
CANDIDATE = MAIN.parent / 'BiliPai-v023'
AUDIT = Path(__file__).resolve().parent

def sha(data):
    return hashlib.sha256(data).hexdigest()

def frozen(lane, digest, count):
    root = MAIN / 'desktop/.local' / lane
    file = root / 'frozen-handoff.json'
    assert sha(file.read_bytes()) == digest, file
    manifest = json.loads(file.read_text(encoding='utf-8'))
    assert manifest['artifactCount'] == count
    for row in manifest['artifacts']:
        path = root / row['path']
        data = path.read_bytes()
        assert len(data) == row['bytes'] and sha(data) == row['sha256Bytes'], path
    return root

bindings = frozen('stable-native-text-share-bindings-parity',
    'a8b32dffc0a8fae9cbd46e827c173207829a6be16d8b1d584edd90760341eaec', 42)
watcher = frozen('stable-native-text-share-owner-next',
    'cb74c090dee94e43b3e3c47ea2b1ce6cd62b6cd4866836d6b0be7baa6a72b97f', 27)
pending = []
records = []

def apply_hunks(path, hunks, base=None, desired=None):
    full = CANDIDATE / path
    original_bytes = full.read_bytes()
    original = full.read_text(encoding='utf-8').replace('\r\n', '\n')
    if base:
        assert sha(original.encode()) == base, path
    result = original
    for hunk in hunks:
        assert result.count(hunk['before']) == 1, (path, hunk['before'])
        result = result.replace(hunk['before'], hunk['after'], 1)
    data = result.encode('utf-8')
    if desired:
        assert sha(data) == desired, path
    pending.append((full, data))
    records.append({'path': path, 'baseSha256Bytes': sha(original_bytes),
        'baseSha256LF': sha(original.encode()), 'installedSha256LF': sha(data), 'hunks': hunks})

for row in json.loads((bindings / 'patch-plan.json').read_text(encoding='utf-8'))['files']:
    if row['install']:
        apply_hunks(row['path'], row['hunks'], row['baseSha256LF'], row['candidateSha256LF'])

rel = 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopTextShareBindings.kt'
assert not (CANDIDATE / rel).exists()
data = (bindings / 'prepared' / rel).read_bytes()
pending.append((CANDIDATE / rel, data))
records.append({'path': rel, 'installedSha256Bytes': sha(data), 'newSource': True})

row = json.loads((watcher / 'patch-plan.json').read_text(encoding='utf-8'))
apply_hunks(row['path'], [row['hunk']], row['baseSha256LF'], row['candidateSha256LF'])

apply_hunks('desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt', [
    {'before': '    val downloadNotification = remember(downloads, hostWindow) {',
     'after': '    val rootTextShareBindings = remember(nativeTextShare) {\n'
              '        DesktopTextShareBindings { title, text, owned ->\n'
              '            nativeTextShare.share(title, text) { owned() && !isClosing() }\n'
              '        }\n'
              '    }\n'
              '    val downloadNotification = remember(downloads, hostWindow) {'},
    {'before': '        LocalDesktopDynamicSaveParent provides hostWindow,',
     'after': '        LocalDesktopDynamicSaveParent provides hostWindow,\n'
              '        LocalDesktopTextShareBindings provides rootTextShareBindings,'}
])

# Validate every handoff/source base before making a shared checkout mutation.
for full, data in pending:
    full.parent.mkdir(parents=True, exist_ok=True)
    full.write_bytes(data)
report = {'frozenBindingsArtifactsVerified': 42, 'frozenWatcherArtifactsVerified': 27,
    'installedSources': records, 'reviewOnlyBgmHunksApplied': False,
    'newNativeSessionAuthority': False, 'actualNativePaneAccepted': False}
(AUDIT / 'native-text-share-install.json').write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
print(json.dumps({'installedSources': len(records), 'frozenArtifactsVerified': 69}))
