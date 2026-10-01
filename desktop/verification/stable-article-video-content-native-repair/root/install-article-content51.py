from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'article-content-install51'
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

def verify_packet(folder, name, pin, key, count):
    raw = read(folder / name)
    assert sha(raw) == pin
    items = json.loads(raw)[key]
    assert len(items) == count
    for row in items:
        assert sha(read(folder / row['path'])) == row['sha256Bytes'], row['path']

article = MAIN / 'desktop/.local/stable-article-full-parity'
content = MAIN / 'desktop/.local/stable-video-player-page-parity'
verify_packet(article, 'frozen-handoff.json', '57c029afb0951a9fa23a7fbf3e2c968888e960186723f13ca9d5a30b1e805355', 'artifacts', 74)
verify_packet(content, 'frozen-content.json', 'bd8f8c1d9ed75154a4ac3ee2c593128be0f9849735beb859f7c5e721199d1484', 'rawArtifacts', 304)
whitelist_raw = read(article / 'install-whitelist.json')
assert sha(whitelist_raw) == '44f1fc71abbe2b4512f2ba4ddddb29549e842f788af5410304096b415b4380d7'
whitelist = json.loads(whitelist_raw)
pending = {}
for row in whitelist['copies']:
    data = read(article / row['source'])
    assert sha(data) == row['sha256Bytes']
    target = REPO / row['target']
    assert not wide(target).exists()
    pending[target] = data
delta = json.loads(read(article / 'tool-delta.json'))
target = REPO / delta['path']
before = lf(target).decode()
assert sha(before.encode()) == delta['baseLfSha256']
after = before
assert len(delta['hunks']) == 4
for hunk in delta['hunks']:
    assert after.count(hunk['before']) == 1, hunk['label']
    after = after.replace(hunk['before'], hunk['after'], 1)
assert sha(after.encode()) == delta['candidateLfSha256']
pending[target] = after.encode()

recipe_raw = read(content / 'CONTENT-INSTALL-RECIPE.json')
assert sha(recipe_raw) == '46a47383d7bd3ce587cce05f20ce81640c03a1a558700a51fb1152e486a20c46'
recipe = json.loads(recipe_raw)
for row in recipe['soleTools'] + recipe['manualPayloads']:
    data = lf(content / row['path'])
    assert sha(data) == row['sha256LF']
    if row in recipe['soleTools']:
        target = REPO / 'desktop/tools' / Path(row['path']).name
    else:
        target = REPO / 'desktop/src/main/kotlin' / Path(row['path']).relative_to('prepared/manual')
    assert not wide(target).exists()
    pending[target] = data

registry_path = REPO / 'desktop/upstream-sources.json'
registry = json.loads(read(registry_path))
assert len(registry['sources']) == 1007
added = 0

def union(row, features, proposed=None, must_exist=False):
    global added
    assert sha(lf(REPO / row['path'])) == row['sha256'], row['path']
    current = next((r for r in registry['sources'] if r['path'] == row['path']), None)
    if current:
        assert current['sha256'] == row['sha256']
        if proposed == 'direct':
            assert current['mode'] == 'direct', row['path']
        for feature in features:
            if feature not in current['features']:
                current['features'].append(feature)
    else:
        assert not must_exist, row['path']
        mode = 'reference-only' if proposed == 'reference' else proposed
        assert mode in ('direct', 'selected', 'reference-only', 'policy-extract', 'extracted')
        registry['sources'].append(dict(path=row['path'], sha256=row['sha256'], mode=mode, features=features))
        added += 1

article_delta = json.loads(read(article / 'registry-delta.json'))
for row in article_delta['new']:
    union(row, row['features'], row['mode'])
assert added == 5
for row in article_delta['featureUnions']:
    union(row, row['features'], must_exist=True)
for row in json.loads(read(content / recipe['registryDelta']))['sources']:
    union(row, [row['feature']], row['proposedMode'])
pending[registry_path] = (json.dumps(registry, ensure_ascii=False, indent=2) + '\n').encode()

gradle = REPO / 'desktop/build.gradle.kts'
data = lf(gradle).decode()
assert 'extractOriginalArticleDetail' not in data and 'extractOriginalVideoContentFull' not in data
assert 'com.mohamedrejeb.richeditor' not in data
article_snippet = lf(article / 'gradle-snippet.kts').decode()
content_snippet = '''dependencies {
    implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.0.0-rc14")
}
val extractOriginalVideoContentFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoDetailUnits, extractOriginalPlayerFullControls)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-content-full.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-content-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-content-full.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-content-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-content-full"))
}
val verifyOriginalVideoContentFull by tasks.registering(Exec::class) {
    dependsOn(extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-video-content-full.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-content-full").get().asFile.absolutePath,
        layout.buildDirectory.file("generated/original-video-content-full-verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-video-content-full.py", "tools/extract-upstream-video-content-full.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.dir(layout.buildDirectory.dir("generated/original-video-content-full"))
    outputs.file(layout.buildDirectory.file("generated/original-video-content-full-verification.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-content-full")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoContentFull, verifyOriginalVideoContentFull) }
'''
pending[gradle] = (data.rstrip('\n') + '\n\n' + article_snippet + '\n' + content_snippet).encode()

OUT.mkdir()
rows = []
for p, data in pending.items():
    relative = p.relative_to(REPO).as_posix()
    original = lf(p) if wide(p).exists() else None
    if original is not None:
        dest = wide(OUT / 'before' / relative)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(original)
    dest = wide(OUT / 'after' / relative)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    wide(p).parent.mkdir(parents=True, exist_ok=True)
    wide(p).write_bytes(data)
    rows.append(dict(path=relative, baseLfSha256=sha(original) if original is not None else None, installedLfSha256=sha(data.replace(b'\r\n', b'\n'))))
report = dict(applied=True, targets=rows, newSourceIdentities=added, sourceIdentityCount=len(registry['sources']),
    newDependency=recipe['dependency'], fullArticleRootMountPending=True, fullOrdinaryVideoMountPending=True,
    wholeCompilationPending=True, originalBusinessHunks=0)
(OUT / 'installed.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps(dict(applied=True, targets=len(rows), newIdentities=added, totalSourceIdentities=len(registry['sources']))))
