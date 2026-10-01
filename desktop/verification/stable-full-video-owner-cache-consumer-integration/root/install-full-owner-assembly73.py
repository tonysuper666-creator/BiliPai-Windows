from pathlib import Path
import ast, hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-video-full-owner-assembly-parity'
CACHE = MAIN / 'desktop/.local/stable-original-video-byte-cache-consumers-parity'
OUT = HERE / 'full-owner-assembly-install73'
assert not OUT.exists()

def wide(p):
    value = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)

def read(p): return wide(p).read_bytes()
def sha(value): return hashlib.sha256(value).hexdigest()
def lf(value): return value.replace(b'\r\n', b'\n')
def dump(p, value):
    target = wide(p)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(value)

head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
assert head == '08d31bd020c515fc1e48775809766082fead6117'
preceding_names = ('public-sponsor-execution-install73', 'video-root-effects-install73',
                   'original-cache-consumers-install73')
latest = {}
for name in preceding_names:
    installed = json.loads(read(HERE / name / 'installed.json'))
    for row in installed['changedFiles']:
        latest[row['path']] = row['afterSha256Bytes']
    for row in installed.get('targets', []):
        latest[row['path']] = row['sha256Bytes']
for name, digest in latest.items():
    assert sha(read(REPO / name)) == digest, name
modified = set(subprocess.check_output(['git', 'diff', '--name-only'], cwd=REPO, text=True).splitlines())
expected_tracked = {name for name in latest if subprocess.run(
    ['git', 'cat-file', '-e', 'HEAD:' + name], cwd=REPO, capture_output=True).returncode == 0}
assert modified == expected_tracked, (modified, expected_tracked)

raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == '6f2c8dbdd7bf2e0bf9f9c1ecd9cd55ce5fcda1fd0afecb3693efc19ac8161df6'
packet = json.loads(raw)
assert len(packet['rows']) == packet['artifacts'] == 90
for row in packet['rows']:
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['size'], row['path']
whitelist = json.loads(read(LANE / 'install-whitelist.json'))
assert whitelist['copyWhitelist'] == packet['copyWhitelist']
payloads = []
for row in whitelist['copyWhitelist']:
    value = read(LANE / row['source'])
    assert row['newManualOnly'] and not wide(REPO / row['target']).exists()
    assert sha(value) == row['sha256Bytes'] and sha(lf(value)) == row['sha256LF']
    payloads.append((row['target'], value))
assert len(payloads) == 4

def apply(value, hunks):
    positions = []
    for hunk in hunks:
        old, new = hunk['before'].encode(), hunk['after'].encode()
        assert sha(old) == hunk['beforeSha256LF'] and sha(new) == hunk['afterSha256LF']
        assert value.count(old) == 1
        at = value.index(old)
        positions.append((at, old, new))
        value = value[:at] + new + value[at + len(old):]
    return value, positions

def reverse(value, positions):
    for at, old, new in reversed(positions):
        assert value[at:at + len(new)] == new
        value = value[:at] + old + value[at + len(new):]
    return value

cache_packet = read(CACHE / 'frozen-handoff.json')
assert sha(cache_packet) == packet['prospectiveCacheConsumerManifestSHA256']
cache_hunks = json.loads(read(CACHE / 'exact-hunks.json'))['hunks']
assembly_hunks = json.loads(read(LANE / 'exact-hunks.json'))
assert sha(read(LANE / 'exact-hunks.json')) == packet['exactHunksSHA256Bytes']
assert len(assembly_hunks) == 3 and sum(len(row['hunks']) for row in assembly_hunks) == 5
originals, changes, merges = {}, {}, []
for row in assembly_hunks:
    name = row['target']
    base = lf(subprocess.check_output(['git', 'show', 'HEAD:' + name], cwd=REPO))
    assert sha(base) == row['baseSha256LF'], name
    assembly_first, base_positions = apply(base, row['hunks'])
    assert sha(assembly_first) == row['desiredSha256LF'], name
    assert reverse(assembly_first, base_positions) == base
    before = read(REPO / name)
    merged, positions = apply(lf(before), row['hunks'])
    assert reverse(merged, positions) == lf(before), name
    sibling_hunks = [item for item in cache_hunks if item['path'] == name]
    cached_base, unused = apply(base, sibling_hunks)
    assert cached_base == lf(before), name
    cache_after_assembly, unused = apply(assembly_first, sibling_hunks)
    byte_commutes = cache_after_assembly == merged
    if not byte_commutes:
        # Both recipes insert independent functions before the same generate
        # anchor. The required Cache -> Assembly order is authoritative. Verify
        # the alternative changes ONLY top-level function placement/content.
        assert name == 'desktop/tools/extract-upstream-video-full-owner.py'
        def module_parts(value):
            module = ast.parse(value.decode())
            functions, others = {}, []
            for node in module.body:
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    assert node.name not in functions
                    functions[node.name] = ast.dump(node, include_attributes=False)
                else:
                    others.append(ast.dump(node, include_attributes=False))
            return functions, others
        assert module_parts(cache_after_assembly) == module_parts(merged)
    originals[name], changes[name] = before, merged
    merges.append(dict(path=name, exactHunks=len(row['hunks']), siblingCacheHunks=len(sibling_hunks),
        beforeSha256Bytes=sha(before), afterSha256Bytes=sha(merged),
        beforeSha256LF=sha(lf(before)), afterSha256LF=sha(merged),
        exactIndexedInverse=True, assemblyAndCacheByteCommute=byte_commutes,
        alternateOrderSameTopLevelDefinitions=True, requiredCacheThenAssemblyOrderPreserved=True))

registry_name = 'desktop/upstream-sources.json'
registry_before = read(REPO / registry_name)
registry = json.loads(registry_before)
assert len(registry['sources']) == 1169 and len(registry['resources']) == 213
recipe = json.loads(read(LANE / 'registry-merge.json'))
assert recipe['newOriginalIdentities'] == recipe['newResources'] == recipe['newDependencies'] == 0
assert not recipe['newGradleTask'] and len(recipe['rows']) == 2
unions = []
for item in recipe['rows']:
    identity = item['originalIdentity']
    rows = [row for row in registry['sources'] if row['path'] == identity['path']]
    assert len(rows) == 1
    row = rows[0]
    assert item['preserveExistingModeAndSHA'] and not item['newIdentity']
    assert row['mode'] == identity['mode'] and row['sha256'] == identity['sha256']
    assert set(identity['features']).issubset(row['features'])
    old = list(row['features'])
    for feature in item['featureUnion']:
        if feature not in row['features']:
            row['features'].append(feature)
    unions.append(dict(path=row['path'], sha256=row['sha256'], mode=row['mode'],
        beforeFeatures=old, afterFeatures=row['features']))
originals[registry_name] = registry_before
changes[registry_name] = (json.dumps(registry, indent=2, ensure_ascii=False) + '\n').encode()

# All immutable byte pins, exact snippets, inverses and both merge orders are
# checked before installation. No prepared whole existing file is copied.
targets, changed = [], []
for name, value in payloads:
    dump(OUT / 'after' / name, value)
    dump(REPO / name, value)
    targets.append(dict(path=name, sha256Bytes=sha(value)))
for name, value in changes.items():
    dump(OUT / 'before' / name, originals[name])
    dump(OUT / 'after' / name, value)
    dump(REPO / name, value)
    changed.append(dict(path=name, beforeSha256Bytes=sha(originals[name]), afterSha256Bytes=sha(value)))
report = dict(phase=73, baseCommit=head, preparedManifestSha256Bytes=sha(raw),
    preparedRawArtifacts=90, payloadCount=4, existingFamilyCount=3, exactHunks=5,
    targets=targets, changedFiles=changed, exactRebasedFamilies=merges,
    existingIdentityFeatureUnions=unions, sharedWholeFilesReplaced=False,
    newIdentityCount=0, newResourceCount=0, newDependencyCount=0,
    originalActionFiveAndWatchLaterMembershipInstalled=True,
    sameRepositoryStoreEntryLockOrderPreserved=True,
    originalActualLoadStateReadOnlyProjectionInstalled=True,
    installedWholeOwnerConstructed=False, fullRootMounted=False, desktopExeReplaced=False)
dump(OUT / 'installed.json', (json.dumps(report, indent=2) + '\n').encode())
print(json.dumps(dict(payloadCount=4, existingFamilies=3, exactHunks=5,
    overlappingCacheFamilies=sum(row['siblingCacheHunks'] > 0 for row in merges),
    identityFeatureUnions=len(unions))))
