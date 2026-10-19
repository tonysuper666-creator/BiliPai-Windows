"""Whole v0.4.6 core-data progress and card-stats policies, plus the original test."""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-core-data-policies'
MANIFEST_SHA256 = '381c7a21706c7e0558ce8538563fcca502cd7aaa45cfc9289301fb7a08a2daa4'
PACKAGE_DIR = 'com/android/purebilibili/data/model'
MAIN_FILES = (
    'VideoProgressDisplayPolicy.kt',
    'WatchLaterProgressPolicy.kt',
    'VideoCardStatsTextPolicy.kt',
)
TEST_FILES = ('VideoProgressDisplayPolicyTest.kt',)
EXPECTED = set(MAIN_FILES) | set(TEST_FILES)
UPSTREAM_PATHS = {
    'VideoProgressDisplayPolicy.kt': 'core-data/src/main/java/com/android/purebilibili/data/model/VideoProgressDisplayPolicy.kt',
    'WatchLaterProgressPolicy.kt': 'core-data/src/main/java/com/android/purebilibili/data/model/WatchLaterProgressPolicy.kt',
    'VideoCardStatsTextPolicy.kt': 'core-data/src/main/java/com/android/purebilibili/data/model/VideoCardStatsTextPolicy.kt',
    'VideoProgressDisplayPolicyTest.kt': 'core-data/src/test/java/com/android/purebilibili/data/model/VideoProgressDisplayPolicyTest.kt',
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original core-data policy inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Core-data policy input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Core-data policy manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Core-data policy source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Core-data policy source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous core-data policy source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Core-data policy upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Core-data policy bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Core-data policy Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.startswith('package com.android.purebilibili.data.model\n'),
             'Core-data policy text changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    test = sources['VideoProgressDisplayPolicyTest.kt']
    need(test.count('\n    @Test\n') == 4, 'Core-data policy test case count changed')
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Core-data policy license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original core-data policy inputs')


def _target(root, name):
    base = Path(root).resolve()
    relative = PACKAGE_DIR + '/' + name
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Core-data policy target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Core-data policy target is not a regular file: ' + relative)


def emit(repo, output, tests_output):
    validate_outputs(repo, output, tests_output)
    _sources, raws, _manifest = fixed(repo)
    planned = [(output, name) for name in MAIN_FILES] + [(tests_output, name) for name in TEST_FILES]
    targets = []
    for base, name in planned:
        target, relative = _target(base, name)
        _require_regular_or_absent(target, relative)
        targets.append((target, relative, raws[name]))
    receipt = []
    for target, relative, leaf in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(leaf)
        written = target.read_bytes()
        need(written == leaf, 'Core-data policy readback differs: ' + relative)
        receipt.append({'path': relative, 'sha256': sha(written)})
    return {'files': receipt}
