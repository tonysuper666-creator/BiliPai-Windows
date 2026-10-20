"""Whole v0.4.6 wallpaper foundation: transform policy, playback coordinator, selection and its composition local.

Four pure files are emitted verbatim by the home refresh wrapper (extract-upstream-home-core-refresh.py) into its main
output, the two original suites that exercise them into its original test root. None of the declared names exists in
the vendored v0.2.5 tree, so nothing is excluded and no earlier layer is rewritten:

  WallpaperTransformPolicy.kt        WallpaperTransform, sanitizeWallpaperTransform, the crop geometry shared by the
                                     image layout and the preview gesture, the visible source region the palette
                                     samples, and applyWallpaperGesture (design-system, no Android types)
  WallpaperPlaybackCoordinator.kt    main-thread leases for decorative motion and LocalWallpaperPlaybackCoordinator
  HomeWallpaperSelection.kt          the URI and both device transforms as one persisted selection
  HomeWallpaperPresentation.kt       LocalHomeWallpaperSelection

HomeWallpaperAdjustmentSheet.kt (the Android sheet, 232 lines) is carried as the reference for the Windows-owned sheet
and is never emitted: it needs the v0.4.6 HomeWallpaperBackdrop with its media load states, the request based
WallpaperPaletteStore and the internal WallpaperDeviceTabRow, none of which the Windows tree has. The Windows sheet
keeps its public parameter list exactly (sheet_signature below is how a test proves it).

Known probable red case, emitted as it is: WallpaperTransformPolicyTest.landscapeImageFillsPortraitViewportAndCanReachBothEdges
asserts assertEquals(0f, left.left) with no tolerance. In Float arithmetic the policy gives -0.0f for offsetX = -1f
(-(overflow) * 0f / 2f), and kotlin.test compares two boxed Floats with equals, which tells -0.0f from 0.0f. Neither the
suite nor the policy is edited here (hard rule 5; playbook 6.2 treats a changed literal as a case to flag). The Linux
compile run confirms or refutes it; only Root may then pin the one-literal adaptation assertEquals(0f, left.left, 0.01f)
with these exact literals. test_v046_wallpaper_foundation_source.py records both and emulates the arithmetic.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-wallpaper-foundation'
MANIFEST_SHA256 = '560fd33ab3022454de8c02422df0f338b991efc0d3408994a9ec59bfed98a754'
MAIN_FILES = (
    'HomeWallpaperPresentation.kt',
    'HomeWallpaperSelection.kt',
    'WallpaperPlaybackCoordinator.kt',
    'WallpaperTransformPolicy.kt',
)
TEST_FILES = (
    'WallpaperPlaybackCoordinatorTest.kt',
    'WallpaperTransformPolicyTest.kt',
)
REFERENCE_FILES = ('HomeWallpaperAdjustmentSheet.kt',)
EXPECTED = set(MAIN_FILES) | set(TEST_FILES) | set(REFERENCE_FILES)
WALLPAPER = 'com.android.purebilibili.core.ui.wallpaper'
PACKAGE = {
    'HomeWallpaperPresentation.kt': WALLPAPER,
    'HomeWallpaperSelection.kt': 'com.android.purebilibili.core.store.home',
    'WallpaperPlaybackCoordinator.kt': WALLPAPER,
    'WallpaperTransformPolicy.kt': WALLPAPER,
    'WallpaperPlaybackCoordinatorTest.kt': WALLPAPER,
    'WallpaperTransformPolicyTest.kt': WALLPAPER,
    'HomeWallpaperAdjustmentSheet.kt': 'com.android.purebilibili.feature.profile',
}
RELATIVE = {name: PACKAGE[name].replace('.', '/') + '/' + name for name in PACKAGE}
APP_MAIN = 'app/src/main/java/com/android/purebilibili/'
UPSTREAM_PATHS = {
    'HomeWallpaperPresentation.kt': APP_MAIN + 'core/ui/wallpaper/HomeWallpaperPresentation.kt',
    'HomeWallpaperSelection.kt': APP_MAIN + 'core/store/home/HomeWallpaperSelection.kt',
    'WallpaperPlaybackCoordinator.kt': APP_MAIN + 'core/ui/wallpaper/WallpaperPlaybackCoordinator.kt',
    'WallpaperTransformPolicy.kt':
        'design-system/src/main/java/com/android/purebilibili/core/ui/wallpaper/WallpaperTransformPolicy.kt',
    'WallpaperPlaybackCoordinatorTest.kt':
        'app/src/test/java/com/android/purebilibili/core/ui/wallpaper/WallpaperPlaybackCoordinatorTest.kt',
    'WallpaperTransformPolicyTest.kt':
        'design-system/src/test/java/com/android/purebilibili/core/ui/wallpaper/WallpaperTransformPolicyTest.kt',
    'HomeWallpaperAdjustmentSheet.kt': APP_MAIN + 'feature/profile/HomeWallpaperAdjustmentSheet.kt',
}
DECLARATIONS = {
    'HomeWallpaperPresentation.kt': ('internal val LocalHomeWallpaperSelection = compositionLocalOf',),
    'HomeWallpaperSelection.kt': ('data class HomeWallpaperSelection(',),
    'WallpaperPlaybackCoordinator.kt': (
        'internal class WallpaperPlaybackCoordinator {',
        'internal val LocalWallpaperPlaybackCoordinator = staticCompositionLocalOf',
    ),
    'WallpaperTransformPolicy.kt': (
        'data class WallpaperTransform(',
        'fun sanitizeWallpaperTransform(',
        'data class WallpaperCropGeometry(',
        'data class WallpaperSourceRegion(',
        'fun resolveWallpaperVisibleSourceRegion(',
        'fun resolveWallpaperCropGeometry(',
        'fun applyWallpaperGesture(',
    ),
    'HomeWallpaperAdjustmentSheet.kt': ('internal fun HomeWallpaperAdjustmentSheet(',),
}
TEST_COUNTS = {
    'WallpaperPlaybackCoordinatorTest.kt': 3,
    'WallpaperTransformPolicyTest.kt': 5,
}
SHEET_NAME = 'HomeWallpaperAdjustmentSheet'
PROOF_NAME = 'v046-wallpaper-foundation-proof.json'
TESTS_PROOF_NAME = 'v046-wallpaper-foundation-tests-proof.json'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original wallpaper foundation inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Wallpaper foundation input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Wallpaper foundation manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Wallpaper foundation source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Wallpaper foundation source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous wallpaper foundation source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Wallpaper foundation upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Wallpaper foundation bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Wallpaper foundation Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and
             ('package ' + PACKAGE[file.name] + '\n') in text[:400],
             'Wallpaper foundation text changed: ' + file.name)
        for token in DECLARATIONS.get(file.name, ()):
            need(text.count(token) == 1, 'Wallpaper foundation declaration changed: ' + token)
        if file.name in TEST_COUNTS:
            need(text.count('@Test') == TEST_COUNTS[file.name], 'Wallpaper foundation test count changed: ' + file.name)
            need('org.junit' not in text, 'Wallpaper foundation suite acquired a JUnit 4 import: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Wallpaper foundation license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original wallpaper foundation inputs')


def sheet_signature(text):
    """[(name, type, default or None)] of the public parameters of HomeWallpaperAdjustmentSheet in text.

    Used on the Android original (the carried reference) and on the Windows-owned sheet: the two lists must be equal.
    The scan balances parentheses, braces and angle brackets (an arrow is not a bracket), so a function type with its own
    commas stays one parameter; it fails closed when the declaration is missing, duplicated or unbalanced.
    """
    head = 'fun ' + SHEET_NAME + '('
    need(text.count(head) == 1, 'HomeWallpaperAdjustmentSheet declaration missing or duplicated')
    start = text.index(head) + len(head)
    depth, index, pieces, begin = 1, start, [], start
    while index < len(text):
        char = text[index]
        if char == '-' and text[index + 1:index + 2] == '>':
            index += 2
            continue
        if char in '(<{[':
            depth += 1
        elif char in ')>}]':
            depth -= 1
            if depth == 0:
                break
        elif char == ',' and depth == 1:
            pieces.append(text[begin:index])
            begin = index + 1
        index += 1
    need(depth == 0, 'HomeWallpaperAdjustmentSheet parameter list is unbalanced')
    pieces.append(text[begin:index])
    parameters = []
    for piece in pieces:
        piece = ' '.join(piece.split())
        if not piece:
            continue
        need(':' in piece, 'HomeWallpaperAdjustmentSheet parameter without a type: ' + piece)
        name, rest = piece.split(':', 1)
        type_text, equals, default = rest.partition(' = ')
        parameters.append((name.strip(), type_text.strip(), default.strip() if equals else None))
    return parameters


def _target(root, relative):
    base = Path(root).resolve()
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Wallpaper foundation target escapes the output root: ' + relative)
    return target


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Wallpaper foundation target is not a regular file: ' + relative)


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
        need(readback == leaf, 'Wallpaper foundation readback differs: ' + relative)
        written.append({'path': relative, 'sha256': sha(readback)})
    return written


def _proof_bytes(receipt):
    return (json.dumps(receipt, ensure_ascii=False, indent=2) + '\n').encode('utf8')


def main_receipt(raws):
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                verbatimFiles={name: sha(raws[name]) for name in MAIN_FILES},
                referenceOnlyFiles={name: sha(raws[name]) for name in REFERENCE_FILES},
                vendoredDirectCopiesExcluded=False, adaptedFiles={})


def tests_receipt(raws):
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, testCounts=dict(TEST_COUNTS),
                verbatimFiles={name: sha(raws[name]) for name in TEST_FILES}, adaptedFiles={},
                jvmTestsExecuted=False)


def emit(repo, output):
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(repo)
    rows = [(RELATIVE[name], raws[name]) for name in MAIN_FILES] + [(PROOF_NAME, _proof_bytes(main_receipt(raws)))]
    return {'files': _write_all(output, rows)}


def emit_tests(repo, output):
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(repo)
    rows = [(RELATIVE[name], raws[name]) for name in TEST_FILES] + [(TESTS_PROOF_NAME, _proof_bytes(tests_receipt(raws)))]
    return {'files': _write_all(output, rows)}
