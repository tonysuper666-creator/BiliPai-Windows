from pathlib import Path
import difflib
import hashlib
import json
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/stable-settings-storage-owner-parity'
supplement = main / 'desktop/.local/stable-settings-storage-owner-parity-rebase8c'

def wide(path):
    value = str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def read(path):
    return wide(path).read_bytes()

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def lf(raw):
    return raw.replace(b'\r\n', b'\n')

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=candidate)

def checked_packet(directory, manifest_name, expected_sha, count):
    raw = read(directory / manifest_name)
    assert sha(raw) == expected_sha
    data = json.loads(raw)
    assert data['frozen'] and len(data['raw']) == count
    for row in data['raw']:
        path = directory / row['path']
        assert path.resolve().is_relative_to(directory.resolve())
        content = read(path)
        assert len(content) == row['bytes'] and sha(content) == row['sha256Bytes'], row['path']
    return data

assert not (root / 'installation.json').exists()
assert not git('status', '--porcelain').strip()
head = git('rev-parse', 'HEAD').decode().strip()
assert head == '8c1970119ffeb5625c7bc7ed5d7a77c1558e52fa'
checked_packet(packet, 'frozen-handoff.json', '4e0082095254dd93d6e9302a6490d2e383c99e5ddf3b42a765e7f56baae7fc08', 97)
checked_packet(supplement, 'frozen-supplement.json', 'ab7a081f3ffb9c038c4bdb0ee1db35b88c624edee91dcaa721899565d0edf496', 17)
native_receipt_raw = read(supplement / 'verification-receipt.json')
assert sha(native_receipt_raw) == '595ff09d5b16ff9f1c21aa06b862b6662c1f94f50d885819806cc35be8985075'
native_receipt = json.loads(native_receipt_raw)
assert native_receipt['passed'] and native_receipt['realExistingSRTInput']
assert native_receipt['actualNativeAssertions'] == 20
assert not any(native_receipt[k] for k in ('RootMounted', 'accountVideo', 'systemReceiver', 'longPathSupported', 'detachTimeoutBoundaryPassed'))
contract = json.loads(read(supplement / 'rebase-contract.json'))
assert contract['baselineHead'] == head
assert contract['existingFamilies'] == 19 and contract['hunks'] == 66
assert len(contract['copyWhitelist']) == 23
assert contract['all19ForwardInverseVerified'] and contract['preserveRootCommentAndTypedRoutes']
changes = []
total_hunks = 0
for row in contract['copyWhitelist']:
    path = row['target']
    destination = candidate / path
    assert destination.resolve().is_relative_to(candidate.resolve())
    if row['new']:
        assert not wide(destination).exists(), path
        old = None
        raw = read(packet / row['prepared'])
        assert sha(raw) == row['afterSha256Bytes'], path
        assert sha(lf(raw)) == row['afterSha256LF'], path
    else:
        old = read(destination)
        assert sha(old) == row['beforeSha256Bytes'], path
        assert sha(lf(old)) == row['beforeSha256LF'], path
        original = lf(old).decode('utf-8')
        text = original
        patch = json.loads(read(packet / row['exactHunkFile']))
        assert patch['target'] == path
        hunks = patch['hunks']
        assert len(hunks) == row['hunks']
        for hunk in hunks:
            before, after = hunk['before'], hunk['after']
            assert sha(before.encode()) == hunk['beforeSha256LF']
            assert sha(after.encode()) == hunk['afterSha256LF']
            assert before and text.count(before) == 1, (path, hunk['id'], 'forward')
            text = text.replace(before, after, 1)
        assert sha(text.encode()) == row['newBaseExpectedAfterSha256LF'], path
        inverse = text
        for hunk in reversed(hunks):
            assert inverse.count(hunk['after']) == 1, (path, hunk['id'], 'inverse')
            inverse = inverse.replace(hunk['after'], hunk['before'], 1)
        assert inverse == original, path
        total_hunks += len(hunks)
        raw = text.replace('\n', '\r\n').encode() if b'\r\n' in old else text.encode()
    assert old != raw
    changes.append((path, old, raw))
assert total_hunks == 66

registry_path = 'desktop/upstream-sources.json'
registry_before = read(candidate / registry_path)
delta = json.loads(read(packet / 'registry-delta.json'))
assert sha(registry_before) == delta['currentInputSha256Bytes']
registry = json.loads(registry_before)
assert len(registry['sources']) == 1180 and len(registry['resources']) == 243
append_sources, union_sources, append_resources = 0, 0, 0
for row in delta['rows']:
    source = read(candidate / row['path'])
    assert sha(source) == row['sourceRawSha256Bytes'], row['path']
    assert sha(lf(source)) == row['sourceLFSha256'], row['path']
    group = registry[row['kind']]
    matches = [item for item in group if item['path'] == row['path']]
    if row['operation'] == 'feature-union':
        assert matches == [row['expectedExisting']], row['path']
        assert row['appendFeature'] not in matches[0]['features']
        matches[0]['features'].append(row['appendFeature'])
        union_sources += 1
    else:
        assert row['operation'] == 'append-identity' and not matches
        assert row['row']['sha256'] == row['sourceLFSha256']
        group.append(row['row'])
        if row['kind'] == 'sources':
            append_sources += 1
        else:
            append_resources += 1
assert (append_sources, union_sources, append_resources) == (3, 4, 1)
assert len(registry['sources']) == 1183 and len(registry['resources']) == 244
registry_after = (json.dumps(registry, indent=2, ensure_ascii=False) + '\n').encode()
if b'\r\n' in registry_before:
    registry_after = registry_after.replace(b'\n', b'\r\n')
changes.append((registry_path, registry_before, registry_after))

gradle_delta = json.loads(read(packet / 'gradle-delta.json'))
gradle_path = gradle_delta['target']
gradle_before = read(candidate / gradle_path)
assert sha(gradle_before) == gradle_delta['currentInputSha256Bytes']
append_raw = read(packet / gradle_delta['appendFile'])
assert sha(append_raw) == gradle_delta['appendSha256Bytes']
assert gradle_delta['newDependencies'] == 0
assert b'val extractOriginalStorageSettings by' not in gradle_before
if b'\r\n' in gradle_before:
    append_raw = lf(append_raw).replace(b'\n', b'\r\n')
changes.append((gradle_path, gradle_before, gradle_before + append_raw))
assert len(changes) == 25
assert len({path for path, _, _ in changes}) == 25
# All source pins, original-source identities, exact forward/inverse hunks and narrow
# registry/build deltas are validated before writing any product file.
receipt = dict(baseCommit=head, mainFrozenArtifacts=97, supplementFrozenArtifacts=17,
    exactHunks=66, existingFamilies=19, newManualSources=3, newProducerFiles=1,
    registryAppendSources=3, registryFeatureUnions=4, registryAppendResources=1,
    sourceCount=1183, resourceCount=244, rootCommentAndTypedSettingsPreserved=True,
    generatedOrBinaryProductCopies=False, newDependencies=0,
    normalCompileAccepted=False, rootRuntimeAccepted=False,
    networkAccepted=False, desktopPackageUpdated=False, sourceTargets=[])
diff = []
for path, old, raw in changes:
    diff.extend(difflib.unified_diff(lf(old or b'').decode().splitlines(True),
        lf(raw).decode().splitlines(True), fromfile=path, tofile=path))
    receipt['sourceTargets'].append(dict(path=path,
        beforeSha256Bytes=sha(old) if old is not None else None,
        afterSha256Bytes=sha(raw), afterSha256LF=sha(lf(raw))))
print(json.dumps(dict(preflightPassed=True, productTargets=25, exactHunks=66,
    sourceCount=1183, resourceCount=244, applied='--apply' in sys.argv)))
if '--apply' not in sys.argv:
    sys.exit(0)
for path, old, raw in changes:
    destination = candidate / path
    assert (read(destination) if wide(destination).exists() else None) == old
    if old is not None:
        backup = root / 'before' / path
        wide(backup.parent).mkdir(parents=True, exist_ok=True)
        wide(backup).write_bytes(old)
    wide(destination.parent).mkdir(parents=True, exist_ok=True)
    wide(destination).write_bytes(raw)
    assert read(destination) == raw
(root / 'source-diff.patch').write_text(''.join(diff), encoding='utf-8', newline='\n')
(root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
