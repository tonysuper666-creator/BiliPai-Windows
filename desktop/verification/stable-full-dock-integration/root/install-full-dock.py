from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LINK = REPO / 'desktop/.local/stable-linked-dock-parity'
FULL = REPO / 'desktop/.local/stable-frosted-audio-renderer-parity'

def wide(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def lf(b): return b.decode('utf-8').replace('\r\n', '\n').encode()
def pin(p, digest):
    b = read(p)
    assert sha(b) == digest, p
    return b
def write(p, b):
    p = wide(p); p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(b)

manifests = []
pending = {}
baselines = {}
for lane, digest in ((LINK, 'e5eb03ec2e44d0b20e6817bcd53eab30851ff60682f7467cb19ce2475e4a9602'),
                     (FULL, 'a35f99c0f41a474c08814fcddfc9dd99f01b003aab83b54b919d9059901efb73')):
    raw = pin(lane / 'frozen-handoff.json', digest)
    m = json.loads(raw)
    for row in m['evidence']:
        pin(row['path'], row['sha256Bytes'])
    for row in m['payload']:
        b = pin(row['source'], row['sha256Bytes'])
        target = row['target']
        assert target not in pending
        if wide(REPO / target).exists():
            assert target == 'desktop/third-party/miuix5157/upstream-provenance.json'
            old = json.loads(read(REPO / target)); new = json.loads(b)
            assert {k:v for k,v in old.items() if k != 'files'} == {k:v for k,v in new.items() if k != 'files'}
            assert new['files'][:len(old['files'])] == old['files']
            assert len(new['files']) == len(old['files']) + 18
            baselines[target] = read(REPO / target)
        pending[target] = b
    manifests.append(dict(lane=str(lane), sha256Bytes=digest, evidenceCount=len(m['evidence']), payloadCount=len(m['payload'])))

contract = json.loads(pin(FULL / 'existing-sole-producer-hunks.json', '61bbee0148bd11a237dd3ec738ea8176d22ace286d3f8471b09bbd81fc709f97'))
for h in contract['hunks']:
    target = h['target']
    b = pending.get(target)
    if b is None:
        b = read(REPO / target); baselines[target] = b
    text = lf(b).decode()
    assert sha(text.encode()) == h['baseSHA256LF'], target
    assert text.count(h['before']) == 1, target
    updated = text.replace(h['before'], h['after'], 1).encode()
    assert sha(updated) == h['resultSHA256LF'], target
    pending[target] = updated

fork = json.loads(read(LINK / 'miuix-fork-hunk.json'))
target = fork['path']; b = read(REPO / target); baselines[target] = b
assert sha(lf(b)) == fork['baseSha256LF']
text = lf(b).decode()
for h in fork['hunks']:
    assert text.count(h['old']) == 1
    text = text.replace(h['old'], h['new'], 1)
pending[target] = text.encode()

target = 'desktop/upstream-sources.json'; b = read(REPO / target); baselines[target] = b
registry = json.loads(b)
assert len(registry['sources']) == 748 and len(registry['resources']) == 210
by = {r['path']:r for r in registry['sources']}
resources = {r['path']:r for r in registry['resources']}
changes = []
for lane in (LINK, FULL):
    recipe = json.loads(read(lane / 'registry-recipe.json'))
    assert recipe['commit'] == registry['upstreamCommit']
    for incoming in recipe['appendRows'] + recipe['featureMerge']:
        path = incoming['path']
        assert sha(lf(read(REPO / path))) == incoming['sha256'], path
        if path in by:
            row = by[path]
            assert row['sha256'] == incoming['sha256']
            if 'preserveMode' in incoming: assert row['mode'] == incoming['preserveMode'], path
            op = 'merge existing feature preserving mode'
        else:
            assert 'mode' in incoming, path
            row = dict(path=path, sha256=incoming['sha256'], mode=incoming['mode'], features=[])
            by[path] = row; registry['sources'].append(row); op = 'append source identity'
        for feature in incoming.get('features', incoming.get('appendFeatures', [])):
            if feature not in row['features']: row['features'].append(feature)
        changes.append(dict(path=path, operation=op, mode=row['mode']))
    for incoming in recipe.get('originalVectorResources', []):
        path = incoming['path']; assert sha(lf(read(REPO / path))) == incoming['sha256'], path
        if path in resources:
            row = resources[path]; assert row['sha256'] == incoming['sha256']
        else:
            assert incoming['action'] == 'append'
            row = dict(path=path, sha256=incoming['sha256'], features=[])
            resources[path] = row; registry['resources'].append(row)
        assert set(incoming.get('preserveExistingFeatures', [])).issubset(row['features'])
        for feature in incoming['features']:
            if feature not in row['features']: row['features'].append(feature)
assert len(registry['sources']) == 765 and len(registry['resources']) == 213
pending[target] = (json.dumps(registry, ensure_ascii=False, indent=2) + '\n').encode()

target = 'desktop/build.gradle.kts'; b = read(REPO / target); baselines[target] = b
text = lf(b).decode()
for lane, name, generated in ((LINK, 'extractLinkedDock', 'linked-dock'), (FULL, 'extractFrostedAudioRenderer', 'frosted-audio-renderer')):
    assert ('val ' + name + ' by') not in text
    assert ('kotlin.srcDir(layout.buildDirectory.dir("generated/' + generated + '"))') not in text
    fragment = lf(read(lane / 'gradle-install-fragment.kt.txt')).decode()
    first = fragment.index('val ' + name + ' by')
    last = fragment.index('\n//', first)
    marker = 'val extractOriginalDanmakuListMenu by tasks.registering(Exec::class) {'
    assert text.count(marker) == 1
    text = text.replace(marker, fragment[first:last] + '\n\n' + marker, 1)
    marker = '    kotlin.srcDir(layout.buildDirectory.dir("generated/original-danmaku-list-menu/com"))'
    assert text.count(marker) == 1
    text = text.replace(marker, marker + '\n    kotlin.srcDir(layout.buildDirectory.dir("generated/' + generated + '"))', 1)
pending[target] = text.encode()

# Old dependency bytes and relocated ordered graphs were frozen before the fork changes.
relocation = MAIN / 'desktop/.local/stable-miuix5157-original-runtime'
pin(relocation / 'relocation-ledger.json', '68b9a804eabd84eedaf40c8b8e2487daad060616a4bac754c1f2f7d3be4fafec')
pin(relocation / 'miuix5157-jvm-0.9.4-5157b503-windows-source1.jar', '78e22c70dd152058f2f7570f59906607e253231f1db346494820bd7e9db7b215')
assert not wide(HERE / 'full-dock-install.json').exists()
for target, b in baselines.items(): write(HERE / 'full-dock-install-baseline' / target, b)
for target, b in pending.items(): write(REPO / target, b)
report = dict(passed=True, manifests=manifests, exactExistingProducerHunks=contract['hunks'],
              sourceRegistryCount=len(registry['sources']), resourceCount=len(registry['resources']),
              registryOperations=changes, installedFiles=[dict(path=p,sha256Bytes=sha(b)) for p,b in pending.items()],
              preservedOriginalTrees=True, fullDockRootMounted=False, wholeClassesAccepted=False,
              realNativePlaybackAccepted=False, desktopExeReplaced=False,
              oldRuntimeDependencyPreservedByOneSameByteRelocation=True)
write(HERE / 'full-dock-install.json', (json.dumps(report, indent=2) + '\n').encode())
print(json.dumps(dict(installed=True, sources=len(registry['sources']), resources=len(registry['resources']), files=len(pending))))
