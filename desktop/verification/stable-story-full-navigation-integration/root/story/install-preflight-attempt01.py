from pathlib import Path
import difflib
import hashlib
import json
import subprocess

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
story = main / 'desktop/.local/stable-original-story-pager-root-parity'
review = main / 'desktop/.local/stable-original-story-review-delta'
image = main / 'desktop/.local/image-preview-share-root'

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

assert not (root / 'installation.json').exists()
assert not git('status', '--porcelain').strip()
base_commit = git('rev-parse', 'HEAD').decode().strip()
manifests = []
for lane, expected in [(story, '60a8bd713b5138ee126c962dc02db07713cdd1e42d6d6eb6d1d0c6fa2b18013f'),
                       (review, 'af1d0cf86d8f3a68e9e346d9809dd78152eaa7f4fdda2bd8c92840fe61832df7')]:
    raw = read(lane / 'frozen-handoff.json')
    assert sha(raw) == expected
    manifest = json.loads(raw)
    for entry in manifest['artifacts']:
        artifact = read(lane / entry['path'])
        assert len(artifact) == entry['bytes'] and sha(artifact) == entry['sha256Bytes'], entry['path']
    manifests.append({'path': str(lane), 'manifestSha256Bytes': expected,
                      'verifiedArtifactCount': len(manifest['artifacts'])})
contract = json.loads(read(story / 'install-contract.json'))
delta = json.loads(read(review / 'install-contract.json'))
assert delta['copyWhitelist'] == [] and delta['requiresFrozenStoryManifestSha256'] == manifests[0]['manifestSha256Bytes']
before = {}
canonical = {}
root_stack = 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalRootStack.kt'
image_before = lf(read(image / 'before' / root_stack)).decode('utf-8')
image_after = lf(read(candidate / root_stack)).decode('utf-8')
assert sha(image_before.encode()) == 'c5d982f9442dace7e93fac4792b248d65dcd3f3d6c3e5051e9fae3401e4b2381'
assert sha(image_after.encode()) == '33b5f9b17a78b3c97612bfd1c29c3f523d1d794be900606d1a5843ac6d1dbab3'
old_lines = image_before.splitlines(True)
new_lines = image_after.splitlines(True)
edits = [op for op in difflib.SequenceMatcher(a=old_lines, b=new_lines, autojunk=False).get_opcodes() if op[0] != 'equal']
assert len(edits) == 1 and edits[0][0] == 'insert', edits
_, a1, a2, b1, b2 = edits[0]
assert a1 == a2 and b2 == b1 + 1 and 'LocalDesktopImageShare provides' in new_lines[b1]
image_anchor_before = ''.join(old_lines[a1-2:a1+2])
image_anchor_after = ''.join(old_lines[a1-2:a1] + new_lines[b1:b2] + old_lines[a1:a1+2])
assert image_before.count(image_anchor_before) == 1
assert image_before.replace(image_anchor_before, image_anchor_after) == image_after

def apply_hunks(text, hunks):
    original = text
    for hunk in hunks:
        assert sha(hunk['before'].encode()) == hunk['beforeSha256LF'], hunk['name']
        assert sha(hunk['after'].encode()) == hunk['afterSha256LF'], hunk['name']
        assert text.count(hunk['before']) == 1, hunk['name']
        text = text.replace(hunk['before'], hunk['after'])
    inverse = text
    for hunk in reversed(hunks):
        assert inverse.count(hunk['after']) == 1, hunk['name']
        inverse = inverse.replace(hunk['after'], hunk['before'])
    assert inverse == original
    return text

story_hunks = json.loads(read(story / contract['exactHunks']))
assert len(story_hunks) == 24
for family in contract['familyPins']:
    path = family['path']
    before[path] = read(candidate / path)
    text = image_before if path == root_stack else lf(before[path]).decode('utf-8')
    assert sha(text.encode()) == family['beforeSha256LF'], path
    text = apply_hunks(text, [h for h in story_hunks if h['path'] == path])
    assert sha(text.encode()) == family['afterSha256LF'], path
    canonical[path] = text
assert {h['path'] for h in story_hunks} == set(canonical)
for entry in contract['copyWhitelist']:
    path = entry['target']
    assert not wide(candidate / path).exists(), path
    raw = read(story / entry['source'])
    assert sha(raw) == entry['sha256Bytes'] and sha(lf(raw)) == entry['sha256LF']
    before[path] = None
    canonical[path] = lf(raw).decode('utf-8')
review_hunks = json.loads(read(review / delta['exactHunks']))
assert len(review_hunks) == delta['hunkCount'] == 5
for family in delta['productionTargets']:
    path = family['path']
    if path not in canonical:
        before[path] = read(candidate / path)
        canonical[path] = lf(before[path]).decode('utf-8')
    assert sha(canonical[path].encode()) == family['beforeSha256LF'], path
    canonical[path] = apply_hunks(canonical[path], [h for h in review_hunks if h['path'] == path])
    assert sha(canonical[path].encode()) == family['afterSha256LF'], path
assert {h['path'] for h in review_hunks} == {f['path'] for f in delta['productionTargets']}
# Merge the single already-integrated image local only after validating the pure
# Story family. Its inverse must return the verified Story-only output.
story_only_stack = canonical[root_stack]
assert story_only_stack.count(image_anchor_before) == 1
canonical[root_stack] = story_only_stack.replace(image_anchor_before, image_anchor_after)
assert canonical[root_stack].replace(image_anchor_after, image_anchor_before) == story_only_stack
registry_path = 'desktop/upstream-sources.json'
before[registry_path] = read(candidate / registry_path)
registry = json.loads(before[registry_path])
assert len(registry['sources']) == 1174
registry_delta = json.loads(read(story / contract['registryDelta']))
added = []
for entry in registry_delta:
    matches = [row for row in registry['sources'] if row['path'] == entry['path']]
    assert sha(lf(read(candidate / entry['path']))) == entry['sha256'], entry['path']
    if entry['existing']:
        assert len(matches) == 1 and matches[0]['sha256'] == entry['sha256']
        row = matches[0]
    else:
        assert not matches
        row = {key: entry[key] for key in ('path', 'sha256', 'features', 'mode')}
        registry['sources'].append(row)
        added.append(entry['path'])
    for feature in entry['features']:
        if feature not in row['features']:
            row['features'].append(feature)
assert len(added) == contract['expectedNewIdentityCount'] == 1
assert len(registry['sources']) == 1175
canonical[registry_path] = json.dumps(registry, ensure_ascii=False, indent=2) + '\n'
changes = []
for path, text in canonical.items():
    old = before[path]
    raw = text.replace('\n', '\r\n').encode() if old is not None and b'\r\n' in old else text.encode()
    assert old != raw
    changes.append((path, old, raw))
# All contracts and merged families pass before any product write.
diffs = []
receipt = {'baseCommit': base_commit, 'frozenManifests': manifests,
           'storyHunkCount': 24, 'reviewHunkCount': 5,
           'manualNewFilesCopied': 2, 'generatedOrBinaryCopied': False,
           'imageRootStackHunkPreserved': True,
           'sourceCountBefore': 1174, 'sourceCountAfter': 1175,
           'newOriginalIdentities': added,
           'nativeBaselineReplacementFixPending': True,
           'compileAccepted': False, 'rootRuntimeAccepted': False,
           'networkAccepted': False, 'sourceTargets': []}
for path, old, raw in changes:
    actual = read(candidate / path) if wide(candidate / path).exists() else None
    assert actual == old
    if old is not None:
        backup = root / 'before' / path
        wide(backup.parent).mkdir(parents=True, exist_ok=True)
        wide(backup).write_bytes(old)
    destination = candidate / path
    assert destination.resolve().is_relative_to(candidate.resolve())
    wide(destination.parent).mkdir(parents=True, exist_ok=True)
    wide(destination).write_bytes(raw)
    assert read(destination) == raw
    diffs.extend(difflib.unified_diff(lf(old or b'').decode('utf-8').splitlines(True),
                 lf(raw).decode('utf-8').splitlines(True), fromfile=path, tofile=path))
    receipt['sourceTargets'].append({'path': path,
        'beforeSha256Bytes': sha(old) if old is not None else None,
        'afterSha256Bytes': sha(raw), 'afterSha256LF': sha(lf(raw))})
(root / 'source-diff.patch').write_text(''.join(diffs), encoding='utf-8', newline='\n')
(root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'sourceTargetCount': len(changes), 'sourceCount': 1175,
                  'exactHunksApplied': 29, 'imageRootStackHunkPreserved': True,
                  'nativeBaselineFixPending': True, 'runtimeAcceptanceClaimed': False}, indent=2))
