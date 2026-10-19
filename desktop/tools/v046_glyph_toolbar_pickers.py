"""Whole v0.4.6 glyph text style, number/duration pickers, floating toolbar and window action menu.

The vendored v0.2.5 direct-mode copies of AppPrimitiveThemeDefaults and AppWindowActionMenu are excluded in
prepareUpstreamSources. The dynamic-detail-container producer, which already owns the v0.4.6 AppSheetComponents,
emits the complete v0.4.6 files instead, together with AppNumberPicker, AppDurationPicker and AppFloatingToolbar,
which the pinned tree never carried. AppFloatingToolbar is imported by eight carried favorite, watch-later and
tab-role archives, so it is the definer the symbol gate was waiting for.

AppPrimitiveThemeDefaults.kt alone is adapted: v0.4.6 appIconGlyphTextStyle copies the shared text style with
PlatformTextStyle(includeFontPadding = false). Desktop PlatformTextStyle has no includeFontPadding constructor and the
Skia JVM text renderer adds no font padding, so the Windows copy returns the shared style itself. Every other byte of
every file is the v0.4.6 blob.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-glyph-toolbar-pickers'
MANIFEST_SHA256 = 'cb68980d5a0c7c3d60ff6d00eb714c06551f4d180ac7b245013ea2d0084251eb'
MAIN_FILES = (
    'AppPrimitiveThemeDefaults.kt',
    'AppDurationPicker.kt',
    'AppFloatingToolbar.kt',
    'AppNumberPicker.kt',
    'AppWindowActionMenu.kt',
)
EXPECTED = set(MAIN_FILES)
UI = 'com.android.purebilibili.core.ui'
COMPONENTS = UI + '.components'
PACKAGE = {
    'AppDurationPicker.kt': COMPONENTS,
    'AppFloatingToolbar.kt': COMPONENTS,
    'AppNumberPicker.kt': COMPONENTS,
    'AppPrimitiveThemeDefaults.kt': UI,
    'AppWindowActionMenu.kt': COMPONENTS,
}
RELATIVE = {name: PACKAGE[name].replace('.', '/') + '/' + name for name in MAIN_FILES}
DS_MAIN = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
UPSTREAM_PATHS = {
    'AppDurationPicker.kt': DS_MAIN + 'components/AppDurationPicker.kt',
    'AppFloatingToolbar.kt': DS_MAIN + 'components/AppFloatingToolbar.kt',
    'AppNumberPicker.kt': DS_MAIN + 'components/AppNumberPicker.kt',
    'AppPrimitiveThemeDefaults.kt': DS_MAIN + 'AppPrimitiveThemeDefaults.kt',
    'AppWindowActionMenu.kt': DS_MAIN + 'components/AppWindowActionMenu.kt',
}
DECLARATIONS = {
    'AppDurationPicker.kt': ('fun AppDurationPicker(',),
    'AppFloatingToolbar.kt': ('fun AppFloatingToolbar(',),
    'AppNumberPicker.kt': ('fun AppNumberPicker(',),
    'AppPrimitiveThemeDefaults.kt': (
        'internal fun currentAppTextStyle(',
        'fun appIconGlyphTextStyle(',
        'internal fun currentAppContentColor(',
    ),
    'AppWindowActionMenu.kt': (
        'data class AppWindowAction(',
        'fun AppWindowActionMenu(',
        'private fun AppWindowAction.toDropdownItem(',
        'WindowCascadingListPopup(',
    ),
}
PROOF_NAME = 'v046-glyph-toolbar-pickers-proof.json'
ADAPTED = 'AppPrimitiveThemeDefaults.kt'
# Desktop PlatformTextStyle has no includeFontPadding constructor (ui-text-desktop 1.12.1 declares only the span and
# paragraph style constructors) and the Skia JVM text renderer adds no font padding to switch off.
THEME_IMPORT = 'import androidx.compose.ui.text.PlatformTextStyle\n'
THEME_BODY = (
    'fun appIconGlyphTextStyle(): TextStyle = currentAppTextStyle().copy(\n'
    '    platformStyle = PlatformTextStyle(includeFontPadding = false),\n'
    ')\n'
)
THEME_BODY_WINDOWS = (
    'fun appIconGlyphTextStyle(): TextStyle =\n'
    '    // The Skia JVM text renderer has no Android font padding to switch off, so the shared style is the glyph style.\n'
    '    currentAppTextStyle()\n'
)


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original glyph toolbar picker inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Glyph toolbar picker input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256,
         'Glyph toolbar picker manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Glyph toolbar picker source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Glyph toolbar picker source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous glyph toolbar picker source')
        need(row['path'] == UPSTREAM_PATHS[file.name] and row['path'].endswith(file.name),
             'Glyph toolbar picker upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Glyph toolbar picker bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Glyph toolbar picker Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and
             ('package ' + PACKAGE[file.name] + '\n') in text[:400],
             'Glyph toolbar picker text changed: ' + file.name)
        for token in DECLARATIONS[file.name]:
            need(text.count(token) == 1, 'Glyph toolbar picker declaration changed: ' + token)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Glyph toolbar picker license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original glyph toolbar picker inputs')


def restore(value, proof):
    need(sha(value) == proof['afterSHA256'], 'Glyph toolbar picker output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Glyph toolbar picker inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Glyph toolbar picker original inverse failed')
    return result


def windows_theme_defaults(original):
    need(original.count(THEME_IMPORT) == 1, 'Theme defaults font-padding import changed')
    need(original.count(THEME_BODY) == 1, 'Theme defaults glyph style body changed')
    need(original.count('PlatformTextStyle') == 2 and original.count('includeFontPadding') == 1,
         'Theme defaults font-padding use changed')
    adapted = original.replace(THEME_IMPORT, '', 1).replace(THEME_BODY, THEME_BODY_WINDOWS, 1)
    need('PlatformTextStyle' not in adapted and 'includeFontPadding' not in adapted,
         'Theme defaults font-padding adaptation incomplete')
    need(adapted.count('fun appIconGlyphTextStyle(): TextStyle =') == 1 and
         adapted.count('currentAppTextStyle()') == 2, 'Theme defaults glyph style adaptation changed')
    proof = inverse_proof(original, adapted)
    need(restore(adapted, proof) == original, 'Theme defaults original inverse failed')
    return adapted, proof


def main_bodies(repo):
    sources, _raws, _manifest = fixed(repo)
    bodies, adapted_proofs = {}, {}
    for name in MAIN_FILES:
        if name == ADAPTED:
            text, proof = windows_theme_defaults(sources[name])
            adapted_proofs[name] = dict(originalToWindows=proof)
        else:
            text = sources[name]
        bodies[name] = text.encode('utf8')
    receipt = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                   verbatimFiles={name: sha(bodies[name]) for name in MAIN_FILES if name not in adapted_proofs},
                   adaptedFiles=adapted_proofs, vendoredDirectCopiesExcluded=True, jvmTestsExecuted=False)
    return bodies, receipt


def _target(root, relative):
    base = Path(root).resolve()
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Glyph toolbar picker target escapes the output root: ' + relative)
    return target


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Glyph toolbar picker target is not a regular file: ' + relative)


def _write_all(output, rows):
    targets = []
    for relative, leaf in rows:
        target = _target(output, relative)
        _require_regular_or_absent(target, relative)
        targets.append((target, relative, leaf))
    written = []
    for target, relative, leaf in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(leaf)
        readback = target.read_bytes()
        need(readback == leaf, 'Glyph toolbar picker readback differs: ' + relative)
        written.append({'path': relative, 'sha256': sha(readback)})
    return written


def _proof_bytes(receipt):
    return (json.dumps(receipt, ensure_ascii=False, indent=2) + '\n').encode('utf8')


def emit(repo, output):
    validate_outputs(repo, output)
    bodies, receipt = main_bodies(repo)
    rows = [(RELATIVE[name], bodies[name]) for name in MAIN_FILES] + [(PROOF_NAME, _proof_bytes(receipt))]
    return {'files': _write_all(output, rows)}
