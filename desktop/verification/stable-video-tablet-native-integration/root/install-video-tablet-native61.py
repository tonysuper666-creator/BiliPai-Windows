from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'video-tablet-native-install61'
assert not OUT.exists(), 'Install receipt already exists; never replay a mutation'

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92) * 2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) else prefix + s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def write(p, b):
    p = wide(p); p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(b)
def encoded(obj): return (json.dumps(obj, ensure_ascii=False, indent=2) + '\n').encode()

lanes = [
    ('comment', MAIN / 'desktop/.local/stable-video-comment-url-parity',
     '02fcdbad3d2a9f58c342d2173e142dfcb1bb1b181a94658c143e1efee8480173', 15),
    ('tablet', MAIN / 'desktop/.local/stable-video-tablet-audio-parity/frozen-tablet-slice-02',
     'bb3f26f6960555378a02ae1bd9b2bb7688ae4d6b3d93483e8c14c36668064bbd', 61),
    ('native', MAIN / 'desktop/.local/stable-video-native-owner-cancellation-parity',
     '8ef0b3d2e03ac1b862b7b1ab6ee1a1274b374aee34ee8ed299c8ed9e418832df', 15),
]
for name, lane, pin, count in lanes:
    raw = read(lane / 'frozen-handoff.json'); assert sha(raw) == pin, name
    data = json.loads(raw); rows = data.get('files', data.get('rawArtifacts'))
    assert len(rows) == count, name
    for row in rows:
        path = Path(row['path']); path = path if path.is_absolute() else lane / path
        b = read(path)
        assert sha(b) == row['sha256Bytes'] and len(b) == row.get('bytes', row.get('size')), str(path)

registry_path = REPO / 'desktop/upstream-sources.json'
registry_before = read(registry_path); registry = json.loads(registry_before)
assert registry['upstreamCommit'] == '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert len(registry['sources']) == 1057
by_path = {r['path']: r for r in registry['sources']}
assert len(by_path) == 1057
comment, tablet, native = [item[1] for item in lanes]
comment_delta = json.loads(read(comment / 'registry-delta.json'))
if isinstance(comment_delta, dict): comment_delta = comment_delta['rows']
tablet_delta = json.loads(read(tablet / 'registry-feature-merge.json'))
new_rows, merged_rows = [], []
for row in comment_delta + tablet_delta:
    raw = read(REPO / row['path']).replace(b'\r\n', b'\n')
    assert sha(raw) == row['sha256'], row['path']
    prior = by_path.get(row['path'])
    if prior:
        assert prior['sha256'] == row['sha256'], row['path']
        old_mode = prior['mode']
        prior['features'] = sorted(set(prior['features']) | set(row['features']))
        assert prior['mode'] == old_mode
        merged_rows.append(row['path'])
    else:
        clean = {k: row[k] for k in ('path', 'sha256', 'features', 'mode')}
        registry['sources'].append(clean); by_path[clean['path']] = clean
        new_rows.append(row['path'])
assert len(new_rows) == 7 and len(merged_rows) == 3
assert len(registry['sources']) == 1064

payloads = []
for lane in (comment, native):
    recipe = json.loads(read(lane / 'install-whitelist.json'))
    assert len(recipe['files']) == 1 and not recipe['sharedPatches']
    for r in recipe['files']:
        b = read(lane / r['source']); assert sha(b) == r['sha256Bytes']
        payloads.append((r['target'], b))
tablet_manifest = json.loads(read(tablet / 'frozen-handoff.json'))
tablet_pins = {str(Path(r['path'])): r['sha256Bytes'] for r in tablet_manifest['rawArtifacts']}
for rel in ('desktop/tools/extract-upstream-video-tablet-full.py',
            'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalTabletAudioPlatform.kt'):
    source = tablet / 'prepared' / rel
    b = read(source); assert sha(b) == tablet_pins[str(source)], rel
    payloads.append((rel, b))
assert len(payloads) == 4
for rel, _ in payloads: assert not wide(REPO / rel).exists(), rel

gradle_path = REPO / 'desktop/build.gradle.kts'; gradle_before = read(gradle_path)
gradle = gradle_before.decode().replace('\r\n', '\n')
assert 'prepareUpstreamVideoCommentUrl' not in gradle and 'extractOriginalVideoTabletFull' not in gradle
gradle += '''

val prepareUpstreamVideoCommentUrl by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-comment-url.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/upstream-video-comment-url").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-comment-url.py",
        File(repositoryRoot, "app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailSessionPolicy.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/upstream-video-comment-url"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/upstream-video-comment-url/com")) }
tasks.named("compileKotlin") { dependsOn(prepareUpstreamVideoCommentUrl) }

val extractOriginalVideoTabletFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, prepareUpstreamVideoCommentUrl, extractOriginalVideoStateCore,
        extractOriginalVideoPlayerSectionFull, extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-tablet-full.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/video-tablet-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-tablet-full.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-video-player-section-full.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-tablet-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/video-tablet-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/video-tablet-full/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoTabletFull) }
'''

OUT.mkdir()
write(OUT / 'before/desktop/upstream-sources.json', registry_before)
write(OUT / 'before/desktop/build.gradle.kts', gradle_before)
for rel, b in payloads:
    write(REPO / rel, b); write(OUT / 'after' / rel, b)
registry_after = encoded(registry); gradle_after = gradle.encode()
write(registry_path, registry_after); write(OUT / 'after/desktop/upstream-sources.json', registry_after)
write(gradle_path, gradle_after); write(OUT / 'after/desktop/build.gradle.kts', gradle_after)
report = dict(applied=True, installedPayloadCount=4,
    targets=[dict(path=rel, installedSha256Bytes=sha(b)) for rel,b in payloads],
    newSourceIdentities=new_rows, mergedSourceIdentities=merged_rows,
    sourceIdentityCount=1064, newDependencies=0, generatedReferencesInstalled=False,
    nativeRequestAdmissionUsesActualAck60=True, currentNativeAuthorityRetired=False,
    wholeOrdinaryVideoMounted=False, wholeCompilationPending=True)
write(OUT / 'installed.json', encoded(report))
print(json.dumps(report))
