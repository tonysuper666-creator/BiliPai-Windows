"""Whole v0.4.6 theme token files and their original tests, emitted verbatim.

Six main files replace their direct-mode v0.2.5 copies (Gradle excludes those copies) and
five original design-system tests are emitted into the producer test root. Nothing is adapted.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-theme-tokens'
MANIFEST_SHA256 = '845317a46a61c54e843c02fa1dafa28d8bcb2a752b3fdfe80e0201f085e43563'
PACKAGE = 'com.android.purebilibili.core.theme'
MAIN_FILES = (
    'Type.kt',
    'AppTextStyles.kt',
    'AdaptiveAccentColorPolicy.kt',
    'AndroidNativeVariantThemePolicy.kt',
    'AppDisplayPolicy.kt',
    'Color.kt',
)
TEST_FILES = (
    'Md3TypographyPolicyTest.kt',
    'AdaptiveAccentColorPolicyTest.kt',
    'AndroidNativeVariantThemePolicyTest.kt',
    'AppDisplayPolicyTest.kt',
    'AppFontWeightPolicyTest.kt',
)
EXPECTED = set(MAIN_FILES) | set(TEST_FILES)
RELATIVE = {name: PACKAGE.replace('.', '/') + '/' + name for name in MAIN_FILES + TEST_FILES}
_DS_MAIN = 'design-system/src/main/java/com/android/purebilibili/core/theme/'
_DT_MAIN = 'design-tokens/src/main/java/com/android/purebilibili/core/theme/'
_DS_TEST = 'design-system/src/test/java/com/android/purebilibili/core/theme/'
UPSTREAM_PATHS = {
    'Type.kt': _DS_MAIN + 'Type.kt',
    'AppTextStyles.kt': _DT_MAIN + 'AppTextStyles.kt',
    'AdaptiveAccentColorPolicy.kt': _DS_MAIN + 'AdaptiveAccentColorPolicy.kt',
    'AndroidNativeVariantThemePolicy.kt': _DS_MAIN + 'AndroidNativeVariantThemePolicy.kt',
    'AppDisplayPolicy.kt': _DS_MAIN + 'AppDisplayPolicy.kt',
    'Color.kt': _DT_MAIN + 'Color.kt',
    'Md3TypographyPolicyTest.kt': _DS_TEST + 'Md3TypographyPolicyTest.kt',
    'AdaptiveAccentColorPolicyTest.kt': _DS_TEST + 'AdaptiveAccentColorPolicyTest.kt',
    'AndroidNativeVariantThemePolicyTest.kt': _DS_TEST + 'AndroidNativeVariantThemePolicyTest.kt',
    'AppDisplayPolicyTest.kt': _DS_TEST + 'AppDisplayPolicyTest.kt',
    'AppFontWeightPolicyTest.kt': _DS_TEST + 'AppFontWeightPolicyTest.kt',
}
# LF sha256 of the direct-mode v0.2.5 copies this slice replaces (desktop/upstream-sources.json pins).
VENDORED_LF_SHA256 = {
    'Type.kt': 'e9caf249fe8bc4b10983fe025be96e14acda2b91977f77783ae3420058c27ff8',
    'AppTextStyles.kt': '534e266f11e1d31e3f6ee343ec80bce1a011a7aa6242d7537b9f2e8ba519af8b',
    'AdaptiveAccentColorPolicy.kt': '99b41df93d0cae5dec1256b2e1ec31ff327709802d292f898c481a243b3d6f05',
    'AndroidNativeVariantThemePolicy.kt': '41f500549ac5fcf8d3ec194df73580920e23cadb264ffd3618456ab1103e80a4',
    'AppDisplayPolicy.kt': 'a774e9a8b34a563da1cd6a5e0cf3fbdf3f57b11f2a7160f6e56049b2be5a5de6',
    'Color.kt': '57232489067aac602f2621833fb87b709e9aa21e07a2852d3083badbcf235097',
}
DECLARATIONS = {
    'Type.kt': (
        'val BiliTypography = Typography(',
        'val Md3Typography = Typography()',
        'val BiliMiuixTypography = Typography(',
    ),
    'AppTextStyles.kt': (
        'object AppTextStyles {',
    ),
    'AdaptiveAccentColorPolicy.kt': (
        'data class AdaptiveAccentColors(',
        'internal fun resolveAdaptiveAccentColors(',
        'fun resolveAdaptivePrimaryAccentColors(',
        'fun resolveAdaptiveTertiaryAccentColors(',
        'fun resolveFilledSelectionAccentColors(',
    ),
    'AndroidNativeVariantThemePolicy.kt': (
        'fun resolveMaterialTypography(',
        'fun resolveAndroidNativeChromeTokens(',
        'fun resolveMaterialShapes(',
        'fun resolveMaterialMotionScheme(',
        '): Typography = Md3Typography',
    ),
    'AppDisplayPolicy.kt': (
        'fun Typography.toMiuixTextStyles(): TextStyles = defaultTextStyles(',
        'fun Typography.scaled(multiplier: Float): Typography {',
        'fun Typography.withFontWeight(fontWeight: FontWeight?): Typography {',
        'fun Typography.withFontFamily(fontFamily: FontFamily?): Typography {',
        'enum class AppFontSizePreset(',
        'fun buildDisplayMetricsSnapshot(',
    ),
    'Color.kt': (
        'val BiliPink = Color(0xFFFA7298)',
        'val ThemeColors = listOf(',
        'val DefaultBottomBarColorIndices = mapOf(',
    ),
}
TEST_COUNTS = {
    'Md3TypographyPolicyTest.kt': 2,
    'AdaptiveAccentColorPolicyTest.kt': 6,
    'AndroidNativeVariantThemePolicyTest.kt': 10,
    'AppDisplayPolicyTest.kt': 10,
    'AppFontWeightPolicyTest.kt': 5,
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original theme token inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Theme token input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Theme token manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Theme token source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Theme token source set changed')
    need({row['replacedBy']: row['vendoredLfSha256'] for row in manifest['replacedVendoredCopies']} ==
         VENDORED_LF_SHA256, 'Theme token replaced vendored pins changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous theme token source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Theme token upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Theme token bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Theme token Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and ('package ' + PACKAGE + '\n') in text[:400],
             'Theme token text changed: ' + file.name)
        for token in DECLARATIONS.get(file.name, ()):
            need(text.count(token) == 1, 'Theme token declaration changed: ' + token)
        if file.name in TEST_COUNTS:
            need(text.count('@Test') == TEST_COUNTS[file.name], 'Theme token original test count changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Theme token license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original theme token inputs')


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Theme token target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Theme token target is not a regular file: ' + relative)


def _write(rows, receipt):
    for target, relative, leaf in rows:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(leaf)
        written = target.read_bytes()
        need(written == leaf, 'Theme token readback differs: ' + relative)
        receipt.append({'path': relative, 'sha256': sha(written)})


def emit(repo, output, test_output=None):
    validate_outputs(repo, output, test_output)
    _sources, raws, _manifest = fixed(repo)
    main_rows, test_rows = [], []
    for name in MAIN_FILES:
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
