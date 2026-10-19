"""Whole v0.4.6 design-system leaf files, part A (roadmap slice fnd-design-system-leaf-completion).

Five complete files and one original test, emitted verbatim by the existing component producer
(extract-upstream-components.py). Nothing is adapted.

  MediaContrastPalette.kt, AppSemanticVisualPolicy.kt   direct-mode v0.2.5 copies; Gradle excludes them and this
                                                        module writes the whole v0.4.6 file
  AppEntryIconPalette.kt                                new file, no earlier copy exists
  AppPopupSurface.kt                                    direct-mode v0.2.5 copy that the component producer lists
                                                        in its DIRECT chain: the chain advances it to v0.4.6 and
                                                        Gradle excludes the vendored copy
  AppChromeSizeTokens.kt                                the component chain already emits the v0.4.0 copy of
                                                        v040-chrome-actions (its direct-mode exclude exists); the
                                                        chain advances it to v0.4.6
  AppEntryIconPaletteTest.kt                            original design-system test, written into the producer's
                                                        test root

The v0.4.6 AppChromeSizeTokensTest.kt is byte-identical to the v0.4.0 copy that v040-chrome-actions already
emits, so it is not carried again.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-design-system-leaves-a'
MANIFEST_SHA256 = '161b36110ed6a55fc2b31368c23c0923066ca4705e4ff2a5b559daba1bcad698'
PACKAGE = 'com.android.purebilibili.core.ui'
BASE = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
MAIN_FILES = (
    'MediaContrastPalette.kt',
    'AppPopupSurface.kt',
    'AppSemanticVisualPolicy.kt',
    'AppEntryIconPalette.kt',
    'AppChromeSizeTokens.kt',
)
# advanced inside the component producer's chain (both are in its DIRECT list)
CHAIN_FILES = ('AppPopupSurface.kt', 'AppChromeSizeTokens.kt')
CHAIN_PATHS = frozenset(BASE + name for name in CHAIN_FILES)
# written whole by emit()
VERBATIM_FILES = ('MediaContrastPalette.kt', 'AppSemanticVisualPolicy.kt', 'AppEntryIconPalette.kt')
TEST_FILES = ('AppEntryIconPaletteTest.kt',)
EXPECTED = set(MAIN_FILES) | set(TEST_FILES)
RELATIVE = {name: PACKAGE.replace('.', '/') + '/' + name for name in MAIN_FILES + TEST_FILES}
UPSTREAM_PATHS = {name: BASE + name for name in MAIN_FILES}
UPSTREAM_PATHS['AppEntryIconPaletteTest.kt'] = (
    'design-system/src/test/java/com/android/purebilibili/core/ui/AppEntryIconPaletteTest.kt')
# LF sha256 of what the component chain hands this layer for each chain file
CHAIN_PREDECESSOR_SHA256 = {
    # the vendored v0.2.5 file; no earlier layer touches it
    'AppPopupSurface.kt': 'fdddaad8b821026c6e8909e609a67799f0618511457a9ce56c9291079a7d2939',
    # the v0.4.0 copy of desktop/upstream-slices/v040-chrome-actions
    'AppChromeSizeTokens.kt': '89c7d9a7f651741d5322391c8b03c005310fc4a7c5589eadaefb88853e072657',
}
# LF sha256 of the direct-mode v0.2.5 copies this slice excludes from prepareUpstreamSources
# (desktop/upstream-sources.json pins; AppChromeSizeTokens is already excluded by v040-chrome-actions)
VENDORED_LF_SHA256 = {
    'MediaContrastPalette.kt': '8cbefefe563834983daf639250644b6fe43ab6822d6c773f52a822097915dee4',
    'AppPopupSurface.kt': 'fdddaad8b821026c6e8909e609a67799f0618511457a9ce56c9291079a7d2939',
    'AppSemanticVisualPolicy.kt': '6383d0beb96863fa52534897e4463409ecbc194d8603db72d955607339be1bbf',
}
DECLARATIONS = {
    'MediaContrastPalette.kt': (
        'object MediaContrastPalette {',
        'val SecondaryForeground = Foreground.copy(alpha = 0.72f)',
        'val Track = Foreground.copy(alpha = 0.24f)',
        'val Warning = Color(0xFFFFCC80)',
        'val Error = Color(0xFFFFB4AB)',
    ),
    'AppPopupSurface.kt': (
        'enum class AppPopupSurfaceType {',
        'interface AppPopupSurfaceRenderer {',
        'val LocalAppPopupSurfaceRenderer =',
        'fun AppPopupSurface(',
        'AppContentColors.primaryText()',
    ),
    'AppSemanticVisualPolicy.kt': (
        'enum class AppSemanticIconFamily {',
        'enum class AppIconStyle {',
        'fun resolveAppIconStyle(',
        'fun resolveAppIconStylePreference(',
        'val LocalAppIconStyle =',
        'fun rememberResolvedAppIconStyle()',
        'enum class AppSemanticAccentRole {',
        'data class AppSemanticAccentPalette(',
        'data class AppSemanticVisualPolicy(',
        'fun resolveAppSemanticAccentPalette(',
        'fun resolveAppSemanticVisualPolicy(',
        'fun rememberAppSemanticVisualPolicy()',
        'fun resolveAppChromeLiquidGlassEnabled(',
        'fun rememberAppChromeLiquidGlassEnabled(',
    ),
    'AppEntryIconPalette.kt': (
        'val AppEntryIconPalette: List<Color> = listOf(',
        'fun resolveAppEntryIconColor(',
        'class AppEntryIconColorCursor {',
        'val LocalAppEntryIconColorCursor =',
        'fun ProvideAppEntryIconColors(',
    ),
    'AppChromeSizeTokens.kt': (
        'object AppChromeSizeTokens {',
        'const val LiquidSearchFieldHeightDp = 40',
        'data class CompactCapsuleChromeSpec(',
        'fun resolveCompactCapsuleChromeSpec(',
    ),
}
TEST_COUNTS = {
    'AppEntryIconPaletteTest.kt': 2,
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original design-system leaf inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Design-system leaf input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Design-system leaf manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Design-system leaf source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Design-system leaf source set changed')
    need({row['replacedBy']: row['vendoredLfSha256'] for row in manifest['replacedVendoredCopies']} ==
         VENDORED_LF_SHA256, 'Design-system leaf replaced vendored pins changed')
    need({row['replacedBy']: row['predecessorSha256'] for row in manifest['componentChainPredecessors']} ==
         CHAIN_PREDECESSOR_SHA256, 'Design-system leaf chain predecessor pins changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous design-system leaf source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Design-system leaf upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Design-system leaf bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Design-system leaf Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and ('package ' + PACKAGE + '\n') in text[:400],
             'Design-system leaf text changed: ' + file.name)
        for token in DECLARATIONS.get(file.name, ()):
            need(text.count(token) == 1, 'Design-system leaf declaration changed: ' + token)
        if file.name in TEST_COUNTS:
            need(text.count('@Test') == TEST_COUNTS[file.name], 'Design-system leaf original test count changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Design-system leaf license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original design-system leaf inputs')


def chain_source(repo, path, previous):
    """The component producer's layer: the whole v0.4.6 file for a chain path, the predecessor for any other path.

    The predecessor must be exactly what the chain hands over today, otherwise an earlier layer moved under us.
    """
    if path not in CHAIN_PATHS:
        return previous
    name = Path(path).name
    sources, _raws, _manifest = fixed(repo)
    need(sha(previous.replace('\r\n', '\n')) == CHAIN_PREDECESSOR_SHA256[name],
         'Design-system leaf predecessor changed: ' + name)
    return sources[name]


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Design-system leaf target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Design-system leaf target is not a regular file: ' + relative)


def _write(rows, receipt):
    for target, relative, leaf in rows:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(leaf)
        written = target.read_bytes()
        need(written == leaf, 'Design-system leaf readback differs: ' + relative)
        receipt.append({'path': relative, 'sha256': sha(written)})


def emit(repo, output, test_output=None):
    """Write the three verbatim main files and, when a test root is given, the original test."""
    validate_outputs(repo, output, test_output)
    _sources, raws, _manifest = fixed(repo)
    main_rows, test_rows = [], []
    for name in VERBATIM_FILES:
        target, relative = _target(output, name)
        _require_regular_or_absent(target, relative)
        main_rows.append((target, relative, raws[name]))
    if test_output is not None:
        for name in TEST_FILES:
            target, relative = _target(test_output, name)
            _require_regular_or_absent(target, relative)
            test_rows.append((target, relative, raws[name]))
    receipt = {'files': [], 'tests': []}
    _write(main_rows, receipt['files'])
    _write(test_rows, receipt['tests'])
    return receipt
