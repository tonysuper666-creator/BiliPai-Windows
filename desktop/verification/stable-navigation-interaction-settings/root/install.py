from pathlib import Path
import hashlib
import json
import difflib
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
MAIN = ROOT.parents[2]
CANDIDATE = MAIN.parent / 'BiliPai-v023'
PACKET = MAIN / 'desktop/.local/settings-navigation-interaction-parity'
BASE = '6fd5bbd804f272650942f5a445385ba5428ffa9b'

def wide(path):
    value = str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def read(path):
    return wide(path).read_bytes()

def replace_once(value, old, new):
    assert value.count(old) == 1, old
    return value.replace(old, new, 1)

def safe_target(relative):
    path = CANDIDATE / relative
    assert path.resolve().is_relative_to(CANDIDATE.resolve())
    return path

assert not (ROOT / 'installation.json').exists()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=CANDIDATE, text=True).strip() == BASE
assert not subprocess.check_output(['git', '-c', 'core.longpaths=true', 'status', '--porcelain'], cwd=CANDIDATE)
manifest_bytes = read(PACKET / 'freeze-manifest.json')
assert digest(manifest_bytes) == '49c627989b2461d985bb28e8e596a580b94583f6288b04b62540c842a776289c'
manifest = json.loads(manifest_bytes)
for entry in manifest['files']:
    assert digest(read(PACKET / entry['path'])) == entry['sha256Bytes'], entry['path']
handoff_bytes = read(PACKET / 'frozen-handoff.json')
assert digest(handoff_bytes) == '9b4e9ceb8318d87c175d7e9947cd933987f843cb1d7db7a36178d6fa55f39f7f'
handoff = json.loads(handoff_bytes)
changes = []
for entry in handoff['targets']:
    prepared = read(Path(entry['preparedPath']))
    assert digest(prepared) == entry['sha256Bytes']
    if entry['kind'] != 'production-target':
        continue
    path = safe_target(entry['target'])
    old = read(path) if wide(path).exists() else None
    assert (digest(old) if old is not None else None) == entry['expectedBaseSha256Bytes'], entry['target']
    if old is not None:
        # Apply only the three text changes, retaining the existing checkout's CRLF convention.
        old_text = old.decode().replace('\r\n', '\n')
        new_text = prepared.decode().replace('\r\n', '\n')
        opcodes = [op for op in difflib.SequenceMatcher(None, old_text.splitlines(True), new_text.splitlines(True)).get_opcodes() if op[0] != 'equal']
        assert len(opcodes) == 3, opcodes
        value_lines = old_text.splitlines(True)
        for _, i, j, k, l in reversed(opcodes):
            assert value_lines[i:j] == old_text.splitlines(True)[i:j]
            value_lines[i:j] = new_text.splitlines(True)[k:l]
        value = ''.join(value_lines)
        assert value == new_text
        prepared = value.replace('\n', '\r\n').encode()
    changes.append((entry['target'], old, prepared))

relative = 'desktop/build.gradle.kts'
old = read(safe_target(relative))
text = old.decode().replace('\r\n', '\n')
task = '''val extractUpstreamNavigationInteraction by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-navigation-interaction.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/navigation-interaction").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-navigation-interaction.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "desktop-navigation-interaction-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/navigation-interaction"))
}

'''
text = replace_once(text, 'val extractUpstreamSettingsStorageEntries by tasks.registering(Exec::class) {', task + 'val extractUpstreamSettingsStorageEntries by tasks.registering(Exec::class) {')
text = replace_once(text, '    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-entries"))\n', '    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-entries"))\n    kotlin.srcDir(layout.buildDirectory.dir("generated/navigation-interaction"))\n')
text = replace_once(text, 'extractUpstreamSettingsPrivacy, extractUpstreamSettingsEntries, extractUpstreamSettingsStorageEntries,', 'extractUpstreamSettingsPrivacy, extractUpstreamSettingsEntries, extractUpstreamNavigationInteraction, extractUpstreamSettingsStorageEntries,')
changes.append((relative, old, text.replace('\n', '\r\n').encode()))

relative = 'desktop/upstream-sources.json'
old = read(safe_target(relative))
registry = json.loads(old)
assert registry['upstreamCommit'] == handoff['upstreamCommit']
original_count = len(registry['sources'])
inventory = json.loads(read(PACKET / 'source-inventory.json'))
for item in inventory:
    matches = [entry for entry in registry['sources'] if entry['path'] == item['path']]
    assert len(matches) <= 1
    if matches:
        entry = matches[0]
    else:
        assert item['path'].endswith(('BottomBarSettingsScreen.kt', 'AnimationSettingsScreen.kt'))
        entry = {key: item[key] for key in ('path', 'mode', 'sha256')}
        entry['features'] = []
        registry['sources'].append(entry)
    assert entry['sha256'] == item['sha256']
    assert digest(read(safe_target(item['path'])).replace(b'\r\n', b'\n')) == item['sha256']
    for feature in item['features']:
        assert feature not in entry['features']
        entry['features'].append(feature)
assert original_count == 1172 and len(registry['sources']) == 1174
changes.append((relative, old, (json.dumps(registry, ensure_ascii=False, indent=2) + '\n').replace('\n', '\r\n').encode()))

receipt = {'baseCommit': BASE, 'packetVerified': True, 'frozenFileCount': len(manifest['files']),
           'sourceCountBefore': original_count, 'sourceCountAfter': len(registry['sources']), 'installed': []}
for relative, old, new in changes:
    path = safe_target(relative)
    assert (read(path) if wide(path).exists() else None) == old
    if old is not None:
        backup = ROOT / 'before' / relative
        wide(backup.parent).mkdir(parents=True, exist_ok=True)
        wide(backup).write_bytes(old)
    wide(path.parent).mkdir(parents=True, exist_ok=True)
    wide(path).write_bytes(new)
    assert read(path) == new
    receipt['installed'].append({'path': relative, 'beforeSha256Bytes': digest(old) if old else None,
                                 'afterSha256Bytes': digest(new), 'afterSha256Lf': digest(new.replace(b'\r\n', b'\n'))})
(ROOT / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print(json.dumps(receipt, indent=2))
