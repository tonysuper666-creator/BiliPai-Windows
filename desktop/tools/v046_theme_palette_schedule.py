"""Whole v0.4.6 palette preview widgets and scheduled dark theme files, emitted by the appearance producer.

Five main files and one original test come from the archive. DarkThemeSchedule.kt, ThemeStylePalettePreview.kt and
DarkThemeSchedulePreference.kt are emitted verbatim. ThemeHomePreview.kt and ThemeTimeWheelPicker.kt change only their
Android resource and clock bindings through counted hunks that are pinned to the archive and recorded with their
inverse, so the untouched original can be recovered from the output.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-theme-palette-schedule'
MANIFEST_SHA256 = '6e4434c9be002a5d0cb80a31b8f93fb3004587e10266303e493440ae0123d2c5'
PROOF_NAME = 'v046-theme-palette-schedule-proof.json'
SETTINGS = 'com.android.purebilibili.feature.settings'
SCREEN = SETTINGS + '.screen'
MAIN_FILES = (
    'DarkThemeSchedule.kt',
    'ThemeStylePalettePreview.kt',
    'ThemeHomePreview.kt',
    'DarkThemeSchedulePreference.kt',
    'ThemeTimeWheelPicker.kt',
)
TEST_FILES = (
    'DarkThemeScheduleTest.kt',
)
EXPECTED = set(MAIN_FILES) | set(TEST_FILES)
PACKAGES = {
    'DarkThemeSchedule.kt': SETTINGS,
    'ThemeStylePalettePreview.kt': SETTINGS,
    'ThemeHomePreview.kt': SETTINGS,
    'DarkThemeSchedulePreference.kt': SCREEN,
    'ThemeTimeWheelPicker.kt': SCREEN,
    'DarkThemeScheduleTest.kt': SETTINGS,
}
RELATIVE = {name: PACKAGES[name].replace('.', '/') + '/' + name for name in MAIN_FILES + TEST_FILES}
_APP_MAIN = 'app/src/main/java/com/android/purebilibili/feature/settings/'
UPSTREAM_PATHS = {
    'DarkThemeSchedule.kt': _APP_MAIN + 'DarkThemeSchedule.kt',
    'ThemeStylePalettePreview.kt': _APP_MAIN + 'ThemeStylePalettePreview.kt',
    'ThemeHomePreview.kt': _APP_MAIN + 'ThemeHomePreview.kt',
    'DarkThemeSchedulePreference.kt': _APP_MAIN + 'screen/DarkThemeSchedulePreference.kt',
    'ThemeTimeWheelPicker.kt': _APP_MAIN + 'screen/ThemeTimeWheelPicker.kt',
    'DarkThemeScheduleTest.kt': 'app/src/test/java/com/android/purebilibili/feature/settings/DarkThemeScheduleTest.kt',
}
# Declarations each original must still declare exactly once (a changed upstream file fails closed).
DECLARATIONS = {
    'DarkThemeSchedule.kt': (
        'data class DarkThemeSchedule(',
        'internal fun isScheduledDarkTheme(',
        'internal fun formatThemeScheduleTime(',
        'internal fun normalizeDarkThemeSchedule(',
    ),
    'ThemeStylePalettePreview.kt': (
        'internal fun ThemeStyleSwatch(',
    ),
    'ThemeHomePreview.kt': (
        'internal fun ThemeHomePreview(',
        'private fun ThemePreviewTopBar(',
        'private fun ThemePreviewCard(',
        'private fun ThemePreviewBottomBar(',
    ),
    'DarkThemeSchedulePreference.kt': (
        'internal fun DarkThemeSchedulePreference(',
    ),
    'ThemeTimeWheelPicker.kt': (
        'internal fun closestTimeWheelIndex(',
        'internal fun ThemeTimeWheelPicker(',
        'private fun ThemeTimeWheelColumn(',
        'private const val WHEEL_CYCLES = 201',
    ),
}
TEST_COUNTS = {
    'DarkThemeScheduleTest.kt': 4,
}
# Counted hunks: (before, after) pairs, each `before` occurs exactly once in the original and each `after` nowhere in it.
ADAPTATIONS = {
    'ThemeHomePreview.kt': (
        ('import com.android.purebilibili.R\n',
         'import com.bilipai.desktop.settings.DesktopSettingsSymbols\n'),
        ('rememberMaterialSymbol(R.drawable.ms_search_24)',
         'rememberMaterialSymbol(DesktopSettingsSymbols.ms_search_24)'),
        ('rememberMaterialSymbol(R.drawable.ms_person_24)',
         'rememberMaterialSymbol(DesktopSettingsSymbols.ms_person_24)'),
    ),
    'ThemeTimeWheelPicker.kt': (
        ('import android.os.SystemClock\n',
         'import com.bilipai.desktop.appearance.DesktopMonotonicClock\n'),
        ('val now = SystemClock.uptimeMillis()',
         'val now = DesktopMonotonicClock.elapsedRealtime()'),
    ),
}
# sha256 of the emitted adapted bodies; an unreviewed change to the hunks or the archive fails closed.
ADAPTED_SHA256 = {
    'ThemeHomePreview.kt': 'd44d8c1c410a51c193050c8e721965474e775a9027ef53d1dc6f5f9eaa874579',
    'ThemeTimeWheelPicker.kt': 'ee81408aea5311e1d224b625ded7e92f4ceb9a119d3d8da135fb512bd103fb2c',
}
# Android names that must not survive in an adapted body.
FORBIDDEN_AFTER = {
    'ThemeHomePreview.kt': ('R.drawable.', 'import com.android.purebilibili.R'),
    'ThemeTimeWheelPicker.kt': ('SystemClock', 'import android.'),
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing palette schedule inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Palette schedule input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Palette schedule manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Palette schedule source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Palette schedule source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous palette schedule source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Palette schedule upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Palette schedule bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Palette schedule Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and ('package ' + PACKAGES[file.name] + '\n') in text[:400],
             'Palette schedule text changed: ' + file.name)
        for token in DECLARATIONS.get(file.name, ()):
            need(text.count(token) == 1, 'Palette schedule declaration changed: ' + token)
        if file.name in TEST_COUNTS:
            need(text.count('@Test') == TEST_COUNTS[file.name], 'Palette schedule original test count changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Palette schedule license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps palette schedule inputs')


def adapt(name, original):
    """Apply the counted hunks of one file; returns (adapted text, [{'before', 'after'}])."""
    hunks = ADAPTATIONS.get(name, ())
    text = original
    for before, after in hunks:
        need(original.count(before) == 1, 'Palette schedule hunk anchor changed: ' + before.strip())
        need(after not in original, 'Palette schedule hunk replacement already present: ' + after.strip())
        need(text.count(before) == 1, 'Palette schedule hunk anchors overlap: ' + before.strip())
        text = text.replace(before, after, 1)
    for token in FORBIDDEN_AFTER.get(name, ()):
        need(token not in text, 'Palette schedule Android binding survived: ' + token)
    return text, [{'before': before, 'after': after} for before, after in hunks]


def restore(adapted, hunks):
    """Peel the counted hunks off an adapted body again; the result is the archived original."""
    text = adapted
    for hunk in reversed(hunks):
        need(text.count(hunk['after']) == 1, 'Palette schedule inverse anchor is not unique: ' + hunk['after'].strip())
        text = text.replace(hunk['after'], hunk['before'], 1)
    return text


def bodies(repo):
    """{name: (emitted bytes, proof row)} for every main and test file."""
    sources, raws, _manifest = fixed(repo)
    result = {}
    for name in MAIN_FILES + TEST_FILES:
        original = sources[name]
        if name in ADAPTATIONS:
            adapted, hunks = adapt(name, original)
            need(restore(adapted, hunks) == original, 'Palette schedule inverse failed: ' + name)
            body = adapted.encode('utf8')
            mode = 'whole-original-with-counted-hunks'
            need(sha(body) == ADAPTED_SHA256[name], 'Palette schedule adapted body changed: ' + name)
        else:
            hunks = []
            body = raws[name]
            mode = 'whole-original'
        result[name] = (body, {
            'path': RELATIVE[name],
            'upstreamPath': UPSTREAM_PATHS[name],
            'mode': mode,
            'originalSHA256': sha(raws[name]),
            'outputSHA256': sha(body),
            'hunks': hunks,
        })
    return result


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Palette schedule target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Palette schedule target is not a regular file: ' + relative)


def _write(rows, receipt):
    for target, relative, body, proof in rows:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(body)
        written = target.read_bytes()
        need(written == body, 'Palette schedule readback differs: ' + relative)
        receipt.append(dict(proof, sha256=sha(written)))


def emit(repo, output, test_output=None):
    validate_outputs(repo, output, test_output)
    emitted = bodies(repo)
    main_rows, test_rows = [], []
    for name in MAIN_FILES:
        target, relative = _target(output, name)
        _require_regular_or_absent(target, relative)
        main_rows.append((target, relative) + emitted[name])
    if test_output is not None:
        for name in TEST_FILES:
            target, relative = _target(test_output, name)
            _require_regular_or_absent(target, relative)
            test_rows.append((target, relative) + emitted[name])
    receipt = {'files': [], 'tests': []}
    _write(main_rows, receipt['files'])
    _write(test_rows, receipt['tests'])
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, jvmTestsExecuted=False, **receipt)
    proof_path = Path(output).resolve() / PROOF_NAME
    proof_path.parent.mkdir(parents=True, exist_ok=True)
    _require_regular_or_absent(proof_path, PROOF_NAME)
    proof_path.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')
    return receipt
