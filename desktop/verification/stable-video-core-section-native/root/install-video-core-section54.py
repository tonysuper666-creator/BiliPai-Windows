from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'video-core-section-install54'
assert not OUT.exists()
sha = lambda b: hashlib.sha256(b).hexdigest()

def wide(p):
    s = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) else prefix + s)

def read(p):
    return wide(p).read_bytes()

def lf(p):
    return read(p).replace(b'\r\n', b'\n')

def packet(folder, filename, pin, key, count):
    raw = read(folder / filename)
    assert sha(raw) == pin
    rows = json.loads(raw)[key]
    assert len(rows) == count
    for row in rows:
        data = read(folder / row['path'])
        assert len(data) == row['bytes'], row['path']
        assert sha(data) == row['sha256Bytes'], row['path']

core = MAIN / 'desktop/.local/stable-video-state-holder-parity'
section = MAIN / 'desktop/.local/stable-video-player-section-parity'
packet(core, 'frozen-handoff.json', 'b51df19b3c3bdc4507cc06ff7c3b7ffb1ef259152d503275c16f5ace43247377', 'artifacts', 192)
packet(section, 'frozen-section.json', 'ffd4a0c0f4071c5ec58c9eecd09dadd1b14ea5579c571bebfb7663d478a1dd6b', 'rawArtifacts', 412)

pending = {}
whitelist_raw = read(core / 'install-whitelist.json')
assert sha(whitelist_raw) == '13fc43311b14bb1e924af976d1ef36f9a24b42628517578700777bf542524c51'
for row in json.loads(whitelist_raw)['copyOnly']:
    data = read(core / row['source'])
    assert sha(data) == row['sha256Bytes']
    target = REPO / row['destination']
    assert not wide(target).exists()
    pending[target] = data

recipe_raw = read(section / 'SECTION-INSTALL-RECIPE.json')
assert sha(recipe_raw) == 'eba7224d55bdb0b5eb1cb0e2c2ad25494bd49a53a076ce7cc936f2e7f7606d28'
recipe = json.loads(recipe_raw)
for row in recipe['soleTools'] + recipe['manualPayloads']:
    data = read(section / row['path'])
    assert sha(data) == row['sha256Bytes']
    if row in recipe['soleTools']:
        target = REPO / 'desktop/tools' / Path(row['path']).name
    else:
        target = REPO / 'desktop/src/main/kotlin' / Path(row['path']).relative_to('prepared/manual')
    assert not wide(target).exists()
    pending[target] = data

for filename in recipe['existingSourceHunks']:
    delta = json.loads(read(section / filename))
    target = REPO / delta['path']
    before = lf(target).decode()
    expected_before = delta.get('baseLF', delta.get('baseSha256LF'))
    expected_after = delta.get('candidateLF', delta.get('candidateSha256LF'))
    assert sha(before.encode()) == expected_before, delta['path']
    after = before
    for hunk in delta['hunks']:
        assert after.count(hunk['before']) == 1, delta['path']
        after = after.replace(hunk['before'], hunk['after'], 1)
    assert sha(after.encode()) == expected_after, delta['path']
    pending[target] = after.encode()

registry_path = REPO / 'desktop/upstream-sources.json'
registry = json.loads(read(registry_path))
assert registry['upstreamCommit'] == '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert len(registry['sources']) == 1028
assert len({r['path'] for r in registry['sources']}) == 1028
added = 0

def union(row, digest_key, mode_key, must_exist):
    global added
    digest = row[digest_key]
    assert sha(lf(REPO / row['path'])) == digest, row['path']
    current = next((r for r in registry['sources'] if r['path'] == row['path']), None)
    assert (current is not None) == must_exist, row['path']
    if current:
        assert current['sha256'] == digest
        assert current['mode'] == row['existingMode'], row['path']
        if row[mode_key] == 'direct':
            assert current['mode'] == 'direct'
        assert row['feature'] not in current['features']
        current['features'].append(row['feature'])
    else:
        mode = row[mode_key]
        assert mode in ('direct', 'selected', 'policy-extract', 'extracted')
        registry['sources'].append(dict(path=row['path'], sha256=digest, mode=mode, features=[row['feature']]))
        added += 1

for row in json.loads(read(core / 'registry-merge-recipe.json')):
    union(row, 'sha256LF', 'requestedMode', row['existingIdentity'])
assert added == 14
for row in json.loads(read(section / recipe['registryDelta']))['sources']:
    union(row, 'sha256', 'proposedMode', row['existingMode'] is not None)
assert added == 27
assert len(registry['sources']) == 1055
pending[registry_path] = (json.dumps(registry, ensure_ascii=False, indent=2) + '\n').encode()

gradle = REPO / 'desktop/build.gradle.kts'
data = lf(gradle).decode()
assert 'extractOriginalVideoStateCore' not in data
assert 'extractOriginalVideoPlayerSectionFull' not in data
assert 'val extractOriginalPlayerFullControls' in data
core_snippet = lf(core / 'GRADLE-SNIPPET.txt').decode()
section_snippet = '''val extractOriginalVideoPlayerSectionFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoStateCore, extractOriginalPlayerFullControls, extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-player-section-full.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-player-section-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-player-section-full.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-player-section-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-player-section-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-player-section-full")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoPlayerSectionFull) }
'''
pending[gradle] = (data.rstrip('\n') + '\n\n' + core_snippet + '\n' + section_snippet).encode()

# All inputs and all proposed edits have been checked before any product write.
OUT.mkdir()
rows = []
for target, data in pending.items():
    relative = target.relative_to(REPO).as_posix()
    original = read(target) if wide(target).exists() else None
    if original is not None:
        dest = wide(OUT / 'before' / relative)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(original)
    dest = wide(OUT / 'after' / relative)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    wide(target).parent.mkdir(parents=True, exist_ok=True)
    wide(target).write_bytes(data)
    rows.append(dict(path=relative, baseSha256Bytes=sha(original) if original is not None else None,
        installedSha256Bytes=sha(data), installedSha256LF=sha(data.replace(b'\r\n', b'\n'))))
report = dict(applied=True, targets=rows, frozenCoreRawCount=192, frozenSectionRawCount=412,
    newSourceIdentities=added, sourceIdentityCount=len(registry['sources']), newDependencies=0,
    fullOrdinaryVideoRootMountAccepted=False, wholeCompilationPending=True, originalBusinessHunks=0)
(OUT / 'installed.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps(dict(applied=True, targets=len(rows), newSourceIdentities=added, totalSourceIdentities=len(registry['sources']))))
