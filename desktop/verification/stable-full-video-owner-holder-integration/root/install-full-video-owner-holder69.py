from pathlib import Path
import hashlib, json, os, re

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'full-video-owner-holder-install69'
assert not OUT.exists(), 'Installation is one-shot; never replay a mutation'

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92) * 2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) or os.name != 'nt' else prefix + s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def lf(b): return b.replace(b'\r\n', b'\n')
def write(p, b):
    p = wide(p); p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(b)
def encoded(d): return (json.dumps(d, ensure_ascii=False, indent=2) + '\n').encode()

lanes = [
    ('owner', MAIN / 'desktop/.local/stable-video-full-owner-parity/install-packet-13',
     '51f079a6a39bbe00cf0c19cc40259b26b7696d7e4a5250aa740f3e62fdba5ff4', 363),
    ('holder', MAIN / 'desktop/.local/stable-video-holder-ui-parity',
     'e0764d9788d8993fb46b2ff86b6eeaa4e469da65d414610465835e9da75b46f9', 337),
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
        assert sha(b) == row['sha256Bytes'], str(p)
        expected_size = next(row[k] for k in ('bytes', 'sizeBytes', 'size') if k in row)
        assert len(b) == expected_size, str(p)
        assert str(p) not in pins[name], str(p)
        pins[name][str(p)] = row['sha256Bytes']

owner, holder = [x[1] for x in lanes]
registry_path = REPO / 'desktop/upstream-sources.json'
before_registry = read(registry_path); registry = json.loads(before_registry)
assert registry['upstreamCommit'] == '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert len(registry['sources']) == 1115
by_path = {r['path']: r for r in registry['sources']}
assert len(by_path) == 1115
owner_delta = json.loads(read(owner / 'source-registry-merge.json'))
holder_delta = json.loads(read(holder / 'install-packet/registry-delta.json'))
assert len(owner_delta['rows']) == 16 and owner_delta['newIdentities'] == 13
assert len(holder_delta['identities']) == 52
new, merged, lane_counts = [], [], {}
for name, rows in [('owner', owner_delta['rows']), ('holder', holder_delta['identities'])]:
    added, unions = 0, 0
    for row in rows:
        assert sha(lf(read(REPO / row['path']))) == row['sha256'], row['path']
        prior = by_path.get(row['path'])
        if prior:
            assert prior['sha256'] == row['sha256'], row['path']
            if row['mode'] == 'direct': assert prior['mode'] == 'direct', row['path']
            mode = prior['mode']
            prior['features'] = sorted(set(prior['features']) | set(row['features']))
            assert prior['mode'] == mode
            unions += 1; merged.append(dict(lane=name, path=row['path']))
        else:
            clean = {k: row[k] for k in ('path', 'sha256', 'mode', 'features')}
            if clean['mode'] == 'selected': clean['mode'] = 'policy-extract'
            assert clean['mode'] in ('direct', 'policy-extract', 'extracted')
            registry['sources'].append(clean); by_path[clean['path']] = clean
            added += 1; new.append(dict(lane=name, path=row['path']))
    lane_counts[name] = dict(new=added, existingFeatureUnions=unions)
assert lane_counts['owner'] == dict(new=13, existingFeatureUnions=3)
assert len(registry['sources']) == 1115 + len(new)

payloads = []
for row in json.loads(read(owner / 'install-whitelist.json')):
    p = owner / row['source']; b = read(p)
    assert sha(lf(b)) == row['sha256LF'] and sha(b) == pins['owner'][str(p)]
    payloads.append((row['target'], b))
holder_whitelist = json.loads(read(holder / 'install-packet/install-whitelist.json'))
for row in holder_whitelist['files']:
    p = holder / 'install-packet' / row['path']; b = read(p)
    assert sha(b) == row['sha256Bytes'] == pins['holder'][str(p)], row['path']
    if row['path'].startswith('tools/'):
        target = 'desktop/' + row['path']
    elif row['path'].startswith('manual/'):
        target = 'desktop/src/main/kotlin/' + row['path'][len('manual/'):]
    else:
        assert row['path'] in ('registry-delta.json', 'local-hunks/context-remove.json')
        continue
    payloads.append((target, b))
assert len(payloads) == 10 and len({p for p, _ in payloads}) == 10
for target, _ in payloads: assert not wide(REPO / target).exists(), target

edits = []
for hunk in json.loads(read(owner / 'exact-hunks.json')):
    before = read(REPO / hunk['path']); content = lf(before).decode()
    assert sha(content.encode()) == hunk['baseSha256LF'], hunk['path']
    if 'patch' in hunk:
        patch_lines = lf(read(owner / hunk['patch'])).decode().splitlines(keepends=True)
        chunks = []; chunk = None
        for line in patch_lines:
            if line.startswith('@@'):
                chunk = []; chunks.append(chunk)
            elif chunk is not None:
                assert line[0] in (' ', '+', '-'), repr(line)
                chunk.append(line)
        assert len(chunks) == 2
        for chunk in chunks:
            old = ''.join(line[1:] for line in chunk if line[0] in (' ', '-'))
            new_text = ''.join(line[1:] for line in chunk if line[0] in (' ', '+'))
            assert content.count(old) == 1, hunk['path']
            content = content.replace(old, new_text, 1)
    else:
        anchor = hunk['anchor']; assert content.count(anchor) == 1, hunk['path']
        if 'replacement' in hunk: replacement = hunk['replacement']
        elif 'after' in hunk: replacement = anchor + hunk['after']
        else: replacement = hunk['before'] + anchor
        content = content.replace(anchor, replacement, 1)
    assert sha(content.encode()) == hunk['desiredSha256LF'], hunk['path']
    if hunk['path'].endswith('DesktopOriginalPlayerSettingsContext.kt'):
        child = json.loads(read(holder / 'install-packet/local-hunks/context-remove.json'))
        assert sha(content.encode()) == child['baseSHA256LF']
        assert len(child['hunks']) == 3
        for edit in child['hunks']:
            assert content.count(edit['before']) == 1, edit['before']
            content = content.replace(edit['before'], edit['after'], 1)
        assert sha(content.encode()) == child['candidateSHA256LF']
    edits.append((hunk['path'], before, content.encode()))
assert len(edits) == 4

gradle_path = REPO / 'desktop/build.gradle.kts'; before_gradle = read(gradle_path)
gradle = lf(before_gradle).decode()
for task in ('extractOriginalVideoFullOwner', 'extractOriginalVideoDetailHolderFull'):
    assert task not in gradle
gradle += '''

val extractOriginalVideoFullOwner by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoStateCore, extractOriginalPlayerFullControls,
        extractOriginalVideoFullscreenPager)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-full-owner.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-full-owner").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-full-owner.py", sourceManifest)
    inputs.files(sources.filter { "stable-original-video-full-owner" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-full-owner"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-full-owner")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoFullOwner) }
// DIRECT10 are copied once by prepareUpstreamSources; production omits --standalone.

val extractOriginalVideoDetailHolderFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoFullOwner, extractOriginalVideoFullscreenPager,
        extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-detail-holder.py",
        repositoryRoot.absolutePath,
        layout.buildDirectory.dir("generated/original-video-detail-holder-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-detail-holder.py", sourceManifest)
    inputs.files(sources.filter { "original-video-detail-holder-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-detail-holder-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-detail-holder-full")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoDetailHolderFull) }
// DIRECT21 are copied once by prepareUpstreamSources; production omits --standalone.
'''

edits += [('desktop/upstream-sources.json', before_registry, encoded(registry)),
          ('desktop/build.gradle.kts', before_gradle, gradle.encode())]
OUT.mkdir()
for relative, before, after in edits:
    write(OUT / 'before' / relative, before)
    write(REPO / relative, after); write(OUT / 'after' / relative, after)
for relative, b in payloads:
    write(REPO / relative, b); write(OUT / 'after' / relative, b)
report = dict(applied=True, payloadCount=10, rawPacketArtifactsVerified=700,
    frozenPackets=[dict(lane=n, path=str(p/'frozen-handoff.json'), sha256Bytes=h, artifacts=c) for n,p,h,c in lanes],
    targets=[dict(path=p, sha256Bytes=sha(b), sha256LF=sha(lf(b))) for p,b in payloads],
    existingFamilyExactHunks=4, contextChildHunks=3, wholeFamilyReplacement=False,
    changedFiles=[dict(path=p, beforeSha256Bytes=sha(b), afterSha256Bytes=sha(a)) for p,b,a in edits],
    generatedReferencesInstalled=False, sourceIdentityCount=len(registry['sources']),
    newSourceIdentities=new, mergedSourceIdentities=merged, laneCounts=lane_counts,
    dependenciesAdded=0, directSourcesCopiedOnlyByRegistrySync=True,
    wholeOrdinaryVideoMounted=False, uniqueFullOwnerConstructed=False, wholeCompilationPending=True,
    pluginHistoryCdnFinalWriteAdmissionPending=True, byteCacheNativeCarrierPending=True,
    rootFacadeSwitchPending=True, accountVipRecapturePending=True, exeAcceptancePending=True)
write(OUT / 'installed.json', encoded(report))
print(json.dumps({k: report[k] for k in ('applied','payloadCount','rawPacketArtifactsVerified','sourceIdentityCount','laneCounts','wholeOrdinaryVideoMounted','wholeCompilationPending')}))
