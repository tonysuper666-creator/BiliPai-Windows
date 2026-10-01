from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'fullscreen-music-audio-install65'
assert not OUT.exists(), 'Installation is one-shot; never replay a mutation'

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92) * 2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) else prefix + s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def lf(b): return b.replace(b'\r\n', b'\n')
def write(p, b):
    p = wide(p); p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(b)
def encoded(d): return (json.dumps(d, ensure_ascii=False, indent=2) + '\n').encode()

lanes = [
    ('fullscreen', MAIN / 'desktop/.local/stable-video-fullscreen-pager-parity',
     '35d8c490ccce7c8b19ef86d2de59eda4c8fd65a9499da1e9e854f452f312dc8c', 546),
    ('music', MAIN / 'desktop/.local/stable-video-tablet-audio-parity/frozen-music-slice',
     'f95b38ff95b90e9dcc38d0a174d24c386438d9f0d23d9ec1086f4a7989e136bc', 131),
    ('audio', MAIN / 'desktop/.local/stable-video-tablet-audio-parity/frozen-audio-slice',
     'f5eebbb9ddff4fb0a16826de253ff1c90d93c930eca1b207a640ccc1307bbb05', 46),
]
pins = {}
for name, lane, pin, count in lanes:
    raw = read(lane / 'frozen-handoff.json'); assert sha(raw) == pin, name
    d = json.loads(raw); rows = d.get('artifacts', d.get('rawArtifacts'))
    assert len(rows) == count, name
    pins[name] = {}
    for row in rows:
        p = Path(row['path']); p = p if p.is_absolute() else lane / p
        b = read(p)
        assert sha(b) == row['sha256Bytes'] and len(b) == row.get('bytes', row.get('size')), str(p)
        pins[name][str(p)] = row['sha256Bytes']

fullscreen, music, audio = [x[1] for x in lanes]
registry_path = REPO / 'desktop/upstream-sources.json'
before_registry = read(registry_path); registry = json.loads(before_registry)
assert registry['upstreamCommit'] == '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert len(registry['sources']) == 1064
by_path = {r['path']: r for r in registry['sources']}
assert len(by_path) == 1064
recipe = json.loads(read(fullscreen / 'registry-merge-recipe.json'))
assert recipe['newIdentities'] == 28 and recipe['existingFeatureUnions'] == 5
rows = recipe['additions'] + [r['after'] for r in recipe['merges']]
rows += json.loads(read(music / 'registry-feature-merge.json'))
rows += json.loads(read(audio / 'registry-feature-merge.json'))
new, merged = [], []
for row in rows:
    assert sha(lf(read(REPO / row['path']))) == row['sha256'], row['path']
    prior = by_path.get(row['path'])
    if prior:
        assert prior['sha256'] == row['sha256'], row['path']
        mode = prior['mode']
        prior['features'] = sorted(set(prior['features']) | set(row['features']))
        assert mode == prior['mode']
        merged.append(row['path'])
    else:
        clean = {k: row[k] for k in ('path', 'sha256', 'mode', 'features')}
        registry['sources'].append(clean); by_path[clean['path']] = clean
        new.append(row['path'])
assert len(new) == 51 and len(merged) == 6
assert len(registry['sources']) == 1115

payloads = []
whitelist = json.loads(read(fullscreen / 'install-whitelist.json'))
for row in whitelist['files']:
    p = fullscreen / row['path']; b = read(p)
    assert sha(b) == row['sha256Bytes'] == pins['fullscreen'][str(p)]
    if '/tools/' in row['path']:
        target = 'desktop/tools/' + p.name
    else:
        target = 'desktop/src/main/kotlin/' + row['path'].split('/manual/')[1]
    payloads.append((target, b))
for name, lane, targets in [
    ('music', music, ['desktop/tools/extract-upstream-music-player-full.py',
        'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMusicUiPlatform.kt',
        'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopMusicRaster.kt']),
    ('audio', audio, ['desktop/tools/extract-upstream-video-audio-full.py',
        'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalAudioModePlatform.kt']),
]:
    for target in targets:
        p = lane / 'prepared' / target; b = read(p)
        assert sha(b) == pins[name][str(p)], target
        payloads.append((target, b))
assert len(payloads) == 8
for target, _ in payloads: assert not wide(REPO / target).exists(), target

hunk = json.loads(read(fullscreen / whitelist['exactLocalHunks'][0]['path']))
core_path = REPO / hunk['path']; before_core = read(core_path); core = lf(before_core).decode()
assert sha(core.encode()) == hunk['baseSHA256LF']
assert len(hunk['hunks']) == 1
for edit in hunk['hunks']:
    assert core.count(edit['before']) == 1
    core = core.replace(edit['before'], edit['after'])
after_core = core.encode(); assert sha(after_core) == hunk['candidateSHA256LF']

gradle_path = REPO / 'desktop/build.gradle.kts'; before_gradle = read(gradle_path)
gradle = lf(before_gradle).decode()
for task in ('extractOriginalVideoFullscreenPager', 'extractOriginalMusicPlayerFull', 'extractOriginalVideoAudioFull'):
    assert task not in gradle
gradle += '\n\n' + lf(read(fullscreen / 'root-build-proposal.kts')).decode()
gradle += '''

val extractOriginalMusicPlayerFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-music-player-full.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/music-player-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-music-player-full.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-video-player-section-full.py", sourceManifest)
    inputs.files(sources.filter { "stable-music-player-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/music-player-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/music-player-full/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMusicPlayerFull) }

val extractOriginalVideoAudioFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalMusicPlayerFull, extractOriginalVideoFullscreenPager,
        extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-audio-full.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/video-audio-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-audio-full.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-audio-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/video-audio-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/video-audio-full/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoAudioFull) }
'''

OUT.mkdir()
for relative, before, after in [
    ('desktop/upstream-sources.json', before_registry, encoded(registry)),
    ('desktop/build.gradle.kts', before_gradle, gradle.encode()),
    (hunk['path'], before_core, after_core),
]:
    write(OUT / 'before' / relative, before)
    write(REPO / relative, after); write(OUT / 'after' / relative, after)
for relative, b in payloads:
    write(REPO / relative, b); write(OUT / 'after' / relative, b)
report = dict(applied=True, payloadCount=8, rawPacketArtifactsVerified=723,
    targets=[dict(path=p, sha256Bytes=sha(b)) for p,b in payloads],
    coreSingleAnchorAppend=True, wholeCoreReplacement=False, generatedReferencesInstalled=False,
    sourceIdentityCount=1115, newSourceIdentities=new, mergedSourceIdentities=merged,
    dependenciesAdded=0, directSourcesCopiedOnlyByRegistrySync=True,
    wholeOrdinaryVideoMounted=False, audioMusicRootMounted=False, wholeCompilationPending=True)
write(OUT / 'installed.json', encoded(report))
print(json.dumps(report))
