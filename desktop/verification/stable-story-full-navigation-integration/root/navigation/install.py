from pathlib import Path
import difflib
import hashlib
import json
import subprocess

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/settings-navigation-full-parity'

def wide(path):
    return Path('\\\\?\\' + str(path.absolute()))

def read(path):
    return wide(path).read_bytes()

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def lf(raw):
    return raw.replace(b'\r\n', b'\n')

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=candidate)

assert not (root / 'installation.json').exists()
handoff_raw = read(packet / 'frozen-handoff.json')
assert sha(handoff_raw) == '10b9c892b0578a8985a4a4b5d40d8ee98af7efe0713bb09481ffe91064e1fa2f'
handoff = json.loads(handoff_raw)
manifest_raw = read(packet / handoff['freezeManifest'])
assert sha(manifest_raw) == handoff['freezeManifestSha256Bytes']
manifest = json.loads(manifest_raw)
for entry in manifest['files']:
    raw = read(packet / entry['path'])
    assert len(raw) == entry['sizeBytes'] and sha(raw) == entry['sha256Bytes'], entry['path']
changes = []
for entry in handoff['ownedFiles']:
    path = entry['target']
    actual = read(candidate / path) if wide(candidate / path).exists() else None
    if entry['operation'] == 'add-unique':
        assert actual is None, path
    else:
        assert entry['operation'] == 'replace-owned-exact-base'
        assert sha(actual) == entry['baseSha256Bytes'] and sha(lf(actual)) == entry['baseLFNormalizedSha256'], path
    new = read(packet / entry['prepared'])
    assert sha(new) == entry['preparedSha256Bytes'], path
    changes.append((path, actual, new))
assert len(changes) == 9
registry_path = 'desktop/upstream-sources.json'
old = read(candidate / registry_path)
registry = json.loads(old)
counts_before = {'sources': len(registry['sources']), 'resources': len(registry['resources'])}
assert counts_before['sources'] == 1175
added = {}
for key, filename, classification_file, expected_new in [
        ('sources', handoff['sourceIdentityUnion'], handoff['sourceIdentityClassification'], 5),
        ('resources', handoff['resourceIdentityUnion'], handoff['resourceIdentityClassification'], 30)]:
    entries = json.loads(read(packet / filename))
    classifications = {entry['path']: entry for entry in json.loads(read(packet / classification_file))}
    new_paths = []
    for entry in entries:
        assert sha(lf(read(candidate / entry['path']))) == entry['sha256'], entry['path']
        matches = [row for row in registry[key] if row['path'] == entry['path']]
        expected = classifications[entry['path']]
        if expected['registryAction'].startswith('add-unique-'):
            assert not matches, entry['path']
            row = dict(entry)
            registry[key].append(row)
            new_paths.append(entry['path'])
        else:
            assert expected['registryAction'] == 'append-feature-to-existing'
            assert len(matches) == 1 and matches[0]['sha256'] == entry['sha256'], entry['path']
            row = matches[0]
            if key == 'sources':
                assert row['mode'] == expected['existingMode'], entry['path']
        for feature in entry['features']:
            if feature not in row['features']:
                row['features'].append(feature)
    assert len(new_paths) == expected_new
    added[key] = new_paths
story_path = 'app/src/main/java/com/android/purebilibili/feature/story/StoryScreen.kt'
assert len([row for row in registry['sources'] if row['path'] == story_path]) == 1
new = (json.dumps(registry, ensure_ascii=False, indent=2) + '\n').encode()
if b'\r\n' in old:
    new = new.replace(b'\n', b'\r\n')
changes.append((registry_path, old, new))
gradle_path = 'desktop/build.gradle.kts'
old = read(candidate / gradle_path)
assert lf(old) == git('show', 'HEAD:' + gradle_path)
text = lf(old).decode('utf-8')
anchor = 'val extractUpstreamSettingsStorageEntries by tasks.registering(Exec::class) {'
assert text.count(anchor) == 1 and 'val extractUpstreamFullNavigation by' not in text
block = '''val extractUpstreamFullNavigation by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamNavigationInteraction)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-full-navigation.py",
        "--repo", repositoryRoot.absolutePath, "--policy-only",
        "--output", layout.buildDirectory.dir("generated/full-navigation-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-full-navigation.py", "tools/extract-upstream-navigation-interaction.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files((sources + originalResources).filter {
        "desktop-full-navigation-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>())
    }.map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/full-navigation-settings"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/full-navigation-settings")) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamFullNavigation) }

'''
new_text = text.replace(anchor, block + anchor)
assert new_text.replace(block + anchor, anchor) == text
new = new_text.encode()
if b'\r\n' in old:
    new = new.replace(b'\n', b'\r\n')
changes.append((gradle_path, old, new))
receipt = {'baseCommit': git('rev-parse', 'HEAD').decode().strip(),
           'preparedHandoffSha256Bytes': sha(handoff_raw),
           'preparedManifestSha256Bytes': sha(manifest_raw),
           'verifiedPreparedRawCount': len(manifest['files']),
           'countsBefore': counts_before,
           'countsAfter': {'sources': len(registry['sources']), 'resources': len(registry['resources'])},
           'newIdentities': added, 'storyRegistryIdentityPreserved': True,
           'producerPolicyOnly': True, 'newGenerationTaskCount': 1,
           'generatedOrBinaryCopied': False, 'compileAccepted': False,
           'rootNativeEffectsAccepted': False, 'desktopPackageUpdated': False,
           'sourceTargets': []}
diffs = []
# All exact owned bases, current registry and generation anchors pass before writes.
for path, old, new in changes:
    actual = read(candidate / path) if wide(candidate / path).exists() else None
    assert actual == old, path
    if old is not None:
        backup = root / 'before' / path
        wide(backup.parent).mkdir(parents=True, exist_ok=True)
        wide(backup).write_bytes(old)
    destination = candidate / path
    assert destination.resolve().is_relative_to(candidate.resolve())
    wide(destination.parent).mkdir(parents=True, exist_ok=True)
    wide(destination).write_bytes(new)
    assert read(destination) == new
    diffs.extend(difflib.unified_diff(lf(old or b'').decode('utf-8').splitlines(True),
                 lf(new).decode('utf-8').splitlines(True), fromfile=path, tofile=path))
    receipt['sourceTargets'].append({'path': path,
        'beforeSha256Bytes': sha(old) if old is not None else None,
        'afterSha256Bytes': sha(new), 'afterSha256LF': sha(lf(new))})
(root / 'source-diff.patch').write_text(''.join(diffs), encoding='utf-8', newline='\n')
(root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'sourceTargets': len(changes), 'countsBefore': counts_before,
                  'countsAfter': receipt['countsAfter'], 'newGenerationTasks': 1,
                  'generatedOrBinaryCopied': False}, indent=2))
