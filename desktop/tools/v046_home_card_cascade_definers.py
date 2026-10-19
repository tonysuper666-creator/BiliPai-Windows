"""Whole v0.4.6 HomeOverlayPillButton and AppPressShiftContent, emitted verbatim."""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-home-card-cascade-definers'
MANIFEST_SHA256 = '6c17cf0908b28d3ce46ffd67bbc01c3e2b70de89843dafb92bed8bf518a42310'
MAIN_FILES = (
    'HomeOverlayPillButton.kt',
    'AppPressShiftContent.kt',
)
EXPECTED = set(MAIN_FILES)
PACKAGE = {
    'HomeOverlayPillButton.kt': 'com.android.purebilibili.feature.home.components',
    'AppPressShiftContent.kt': 'com.android.purebilibili.core.ui.components',
}
RELATIVE = {name: PACKAGE[name].replace('.', '/') + '/' + name for name in MAIN_FILES}
UPSTREAM_PATHS = {
    'HomeOverlayPillButton.kt': 'app/src/main/java/com/android/purebilibili/feature/home/components/HomeOverlayPillButton.kt',
    'AppPressShiftContent.kt': 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPressShiftContent.kt',
}
DECLARATIONS = {
    'HomeOverlayPillButton.kt': ('fun HomeOverlayPillButton(',),
    'AppPressShiftContent.kt': ('fun AppPressShiftContent(', 'fun rememberPressShiftPressedState('),
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original home card cascade inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Home card cascade input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Home card cascade manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Home card cascade source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Home card cascade source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous home card cascade source')
        need(row['path'] == UPSTREAM_PATHS[file.name] and row['path'].endswith(RELATIVE[file.name]),
             'Home card cascade upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Home card cascade bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Home card cascade Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        package_line = 'package ' + PACKAGE[file.name] + '\n'
        need(text.encode('utf8') == leaf and text.endswith('\n') and package_line in text[:400],
             'Home card cascade text changed: ' + file.name)
        for token in DECLARATIONS[file.name]:
            need(text.count(token) == 1, 'Home card cascade declaration changed: ' + token)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Home card cascade license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original home card cascade inputs')


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Home card cascade target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Home card cascade target is not a regular file: ' + relative)


def emit(repo, output):
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(repo)
    targets = []
    for name in MAIN_FILES:
        target, relative = _target(output, name)
        _require_regular_or_absent(target, relative)
        targets.append((target, relative, raws[name]))
    receipt = []
    for target, relative, leaf in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(leaf)
        written = target.read_bytes()
        need(written == leaf, 'Home card cascade readback differs: ' + relative)
        receipt.append({'path': relative, 'sha256': sha(written)})
    return {'files': receipt}
