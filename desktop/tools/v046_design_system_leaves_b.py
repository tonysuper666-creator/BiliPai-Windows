"""Whole v0.4.6 design-system leaf files, part B (roadmap slice fnd-design-system-leaf-completion).

Six complete files advance inside the producers that already own them. Nothing is re-implemented: the archive carries
the verbatim v0.4.6 bytes, each producer swaps its own predecessor for them behind a hash assertion, and the platform
bindings that already existed for the v0.2.5 files run unchanged on top.

  AppCard.kt, AppDesktopInteraction.kt       direct-mode v0.2.5 copies in the DIRECT list of
                                             extract-upstream-components.py; Gradle excludes the vendored copy and the
                                             component chain writes the whole v0.4.6 file
  AppPreferenceComponents.kt                 direct-mode v0.2.5 copy in the DIRECT list of
                                             extract-upstream-preferences.py; same treatment
  AppSelectionPreferenceComponents.kt        platform-adapter-reference in the ADAPTED list of
                                             extract-upstream-preferences.py; the layer replaces the original, the
                                             existing LocalConfiguration binding of adapt() runs on the v0.4.6 bytes
  AppTonalPillTabPolicy.kt                   emitted by emit_control_foundations of v037_shared_tabs_progress.py (its
                                             direct-mode exclude already exists); the retained-original override of
                                             that function returns the v0.4.6 file. v0.4.6 deleted
                                             resolveTonalPillTabVerticalInset together with AppTonalPillTabPolicyTest,
                                             so the one assertion of the emitted v0.3.7 test that calls it is removed
  DampedDragAnimation.kt (core/ui/animation) emitted by extract-upstream-shared-liquid-tabs.py; the native source layer
                                             returns the v0.4.6 file and the four existing bindings (SystemClock,
                                             awaitFrame) run on it

AdaptivePreferenceComponents.kt and AdaptiveDialogComponents.kt stay at their hash-pinned Windows adapters (see
manifest notCarried). The v0.4.6 AppPreferenceComponents only calls renderer parameters that the retained adapter
declares; the test checks it.
"""
from pathlib import Path
import hashlib
import json

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-design-system-leaves-b'
MANIFEST_SHA256 = 'da13689990bbe0ef322d864decc64a14c0b4a8cdfb4da5e66616ee34ced4e514'
BASE = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
TEST_BASE = 'design-system/src/test/java/com/android/purebilibili/core/ui/'
PACKAGES = {
    'AppPreferenceComponents.kt': 'com.android.purebilibili.core.ui.components',
    'AppSelectionPreferenceComponents.kt': 'com.android.purebilibili.core.ui.components',
    'AppCard.kt': 'com.android.purebilibili.core.ui.components',
    'AppDesktopInteraction.kt': 'com.android.purebilibili.core.ui.components',
    'AppTonalPillTabPolicy.kt': 'com.android.purebilibili.core.ui.renderer.material3',
    'DampedDragAnimation.kt': 'com.android.purebilibili.core.ui.animation',
}
MAIN_FILES = tuple(PACKAGES)
UPSTREAM_PATHS = {
    'AppPreferenceComponents.kt': BASE + 'components/AppPreferenceComponents.kt',
    'AppSelectionPreferenceComponents.kt': BASE + 'components/AppSelectionPreferenceComponents.kt',
    'AppCard.kt': BASE + 'components/AppCard.kt',
    'AppDesktopInteraction.kt': BASE + 'components/AppDesktopInteraction.kt',
    'AppTonalPillTabPolicy.kt': BASE + 'renderer/material3/AppTonalPillTabPolicy.kt',
    'DampedDragAnimation.kt': BASE + 'animation/DampedDragAnimation.kt',
}
EXPECTED = set(MAIN_FILES)
BY_PATH = {path: name for name, path in UPSTREAM_PATHS.items()}

# producer layers that hand a text predecessor to chain_source()
COMPONENT_FILES = ('AppCard.kt', 'AppDesktopInteraction.kt')
PREFERENCE_FILES = ('AppPreferenceComponents.kt', 'AppSelectionPreferenceComponents.kt')
CHAIN_FILES = COMPONENT_FILES + PREFERENCE_FILES
CHAIN_PATHS = frozenset(UPSTREAM_PATHS[name] for name in CHAIN_FILES)
TONAL_FILE = 'AppTonalPillTabPolicy.kt'
TONAL_PATH = UPSTREAM_PATHS[TONAL_FILE]
DAMPED_FILE = 'DampedDragAnimation.kt'
DAMPED_PATH = UPSTREAM_PATHS[DAMPED_FILE]
# direct-mode v0.2.5 copies that Gradle excludes from prepareUpstreamSources (AppTonalPillTabPolicy is already excluded)
EXCLUDED_NOW = ('AppPreferenceComponents.kt', 'AppCard.kt', 'AppDesktopInteraction.kt')
RELATIVE_MAIN = {name: PACKAGES[name].replace('.', '/') + '/' + name for name in MAIN_FILES}

# LF sha256 of what each producer hands this layer
PREDECESSOR_SHA256 = {
    'AppPreferenceComponents.kt': 'b5d249b50434e18986dd93cf049513499b07d12efa3563786120b8703ac29eb7',
    'AppSelectionPreferenceComponents.kt': '593fefb04321121309fa4e8ac5050a84ff541027bcf5620da956db5f37f10cd1',
    'AppCard.kt': '2f3a9aeb4eb7f2d45ad3d8a8ef1fffc57558e778ca5c25525be1b18fb69cd2b3',
    'AppDesktopInteraction.kt': '68d9e682eb65a25ff33e5bac5d1d6c79c29b951a7c276a2abbcc7b290d50a693',
    'AppTonalPillTabPolicy.kt': '326b3c206b9501e0bd7b80528ed95790e6f87c54aad1540d32eb8d1357c91653',
    'DampedDragAnimation.kt': '78f22a8379ee4e948306309a7d82b5b21e091a37e9da173ae72ff2baac4e27b4',
}
# LF sha256 of the direct-mode v0.2.5 copies this slice excludes (desktop/upstream-sources.json pins)
VENDORED_LF_SHA256 = {name: PREDECESSOR_SHA256[name] for name in EXCLUDED_NOW}

# The emitted v0.3.7 AppTonalPillTabPolicyTest asserts the one function v0.4.6 removed.
TONAL_TEST_PATH = TEST_BASE + 'renderer/material3/AppTonalPillTabPolicyTest.kt'
TONAL_TEST_SHA256 = '462faf0b86e5c0c9a99966b5a4ca8955018104ec02a107825e8b55d1e1a81e3e'
TONAL_TEST_STALE_LINE = '        assertEquals(4.dp, resolveTonalPillTabVerticalInset())\n'
TONAL_TEST_WINDOW_BEFORE = (
    '        assertEquals(12.dp, resolveTonalPillTabItemHorizontalPadding())\n'
    + TONAL_TEST_STALE_LINE +
    '        assertEquals(40.dp, resolveTonalPillTabDefaultHeight())\n')
TONAL_TEST_WINDOW_AFTER = (
    '        assertEquals(12.dp, resolveTonalPillTabItemHorizontalPadding())\n'
    '        assertEquals(40.dp, resolveTonalPillTabDefaultHeight())\n')

DECLARATIONS = {
    'AppPreferenceComponents.kt': (
        'fun AppPreferenceSectionTitle(', 'fun AppPreferenceGroup(', 'ProvideAppEntryIconColors {',
        'import com.android.purebilibili.core.ui.ProvideAppEntryIconColors\n',
        'fun AppSwitchPreference(', 'fun AppSliderPreference(', 'fun AppPreference(',
        'fun AppPreferenceDivider(', 'fun AppPreferenceGridItem(', 'fun AppSearchField(', 'fun AppTextField(',
    ),
    'AppSelectionPreferenceComponents.kt': (
        'import androidx.compose.ui.platform.LocalConfiguration\n', 'fun <T> AppSingleChoicePreference(',
        'fun <T> AppSingleChoiceDialog(', 'fun AppSliderDialogPreference(', 'fun AppSliderDialog(',
        'fun <T> shouldDispatchAppChoiceSelection(', 'fun resolveAppSliderDialogValue(',
    ),
    'AppCard.kt': ('fun AppCard(', 'val resolvedColors = colors ?: AppCardColors(', 'object AppCardDefaults'),
    'AppDesktopInteraction.kt': (
        'fun Modifier.appDesktopInteractionVisuals(', 'fun Modifier.appDesktopFocusableItemVisuals(',
    ),
    'AppTonalPillTabPolicy.kt': (
        'fun resolveTonalPillTabDefaultLabelFontSize(', 'fun resolveTonalPillTabCornerRadius(',
        'fun resolveTonalPillTabItemHorizontalPadding(', 'fun resolveTonalPillTabDefaultHeight(',
    ),
    'DampedDragAnimation.kt': (
        'import android.os.SystemClock\n', 'import kotlinx.coroutines.android.awaitFrame', 'awaitFrame()',
        'SystemClock.uptimeMillis()', 'LiquidIndicatorVelocity(scope)',
        'withTimeoutOrNull(LiquidIndicatorMotion.BloomWaitMillis)',
        'withTimeoutOrNull(LiquidIndicatorMotion.SettleWaitMillis)', 'fun shouldEngageHorizontalDrag(',
        'fun resolveDampedDragReleaseTargetIndex(', 'fun rememberDampedDragAnimationState(',
        'class DampedDragAnimationState internal constructor(',
    ),
}
# the v0.4.6 file must NOT declare this any more
ABSENT_TOKENS = {'AppTonalPillTabPolicy.kt': ('resolveTonalPillTabVerticalInset',)}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original design-system leaf b inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Design-system leaf b input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Design-system leaf b manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Design-system leaf b source identity changed')
    need([row['filename'] for row in manifest['files']] == list(MAIN_FILES),
         'Design-system leaf b source set changed')
    need({row['replacedBy']: row['vendoredLfSha256'] for row in manifest['replacedVendoredCopies']} ==
         VENDORED_LF_SHA256, 'Design-system leaf b replaced vendored pins changed')
    need({row['replacedBy']: row['predecessorSha256'] for row in manifest['producerPredecessors']} ==
         PREDECESSOR_SHA256, 'Design-system leaf b producer predecessor pins changed')
    need(manifest['staleTestAssertion']['testSha256'] == TONAL_TEST_SHA256 and
         manifest['staleTestAssertion']['removedLine'] == TONAL_TEST_STALE_LINE,
         'Design-system leaf b stale test pin changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous design-system leaf b source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Design-system leaf b upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Design-system leaf b bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Design-system leaf b Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and ('package ' + PACKAGES[file.name] + '\n') in text[:400],
             'Design-system leaf b text changed: ' + file.name)
        for token in DECLARATIONS[file.name]:
            need(text.count(token) == 1, 'Design-system leaf b declaration changed: ' + token)
        for token in ABSENT_TOKENS.get(file.name, ()):
            need(token not in text, 'Design-system leaf b declaration returned: ' + token)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Design-system leaf b license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original design-system leaf b inputs')


def _predecessor(name, previous):
    need(sha(previous.replace('\r\n', '\n')) == PREDECESSOR_SHA256[name], 'Design-system leaf b predecessor changed: ' + name)


def chain_source(repo, path, previous):
    """The component and preference producers' layer: the whole v0.4.6 file for a chain path, the predecessor otherwise.

    The predecessor must be exactly what the producer hands over today, otherwise an earlier layer moved under us.
    """
    if path not in CHAIN_PATHS:
        return previous
    name = BY_PATH[path]
    sources, _raws, _manifest = fixed(repo)
    _predecessor(name, previous)
    return sources[name]


def _row(repo, name):
    _sources, _raws, manifest = fixed(repo)
    return next(row for row in manifest['files'] if row['filename'] == name)


def retained_original_override(repo, path, raw, row, commit, manifest_sha):
    """Same contract as v040_liquid_value_controls.retained_original_override: the v0.4.6 policy replaces the v0.3.7 one."""
    if path != TONAL_PATH:
        return raw, row, commit, manifest_sha
    sources, raws, _manifest = fixed(repo)
    _predecessor(TONAL_FILE, raw.decode('utf8'))
    archived = _row(repo, TONAL_FILE)
    return raws[TONAL_FILE], dict(path=path, filename=TONAL_FILE, bytes=archived['bytes'], sha256Bytes=archived['sha256'],
                                  gitBlob=archived['gitBlob'], upstreamCommit=COMMIT), COMMIT, MANIFEST_SHA256


def tonal_test_source(repo, path, text):
    """The v0.3.7 AppTonalPillTabPolicyTest without the one assertion on the function v0.4.6 deleted."""
    if path != TONAL_TEST_PATH:
        return text
    fixed(repo)
    need(sha(text.replace('\r\n', '\n')) == TONAL_TEST_SHA256, 'Design-system leaf b tonal test predecessor changed')
    need(text.count(TONAL_TEST_WINDOW_BEFORE) == 1 and text.count(TONAL_TEST_STALE_LINE) == 1,
         'Design-system leaf b tonal test anchor changed')
    adapted = text.replace(TONAL_TEST_WINDOW_BEFORE, TONAL_TEST_WINDOW_AFTER, 1)
    need('resolveTonalPillTabVerticalInset' not in adapted and
         adapted.replace(TONAL_TEST_WINDOW_AFTER, TONAL_TEST_WINDOW_BEFORE, 1) == text,
         'Design-system leaf b tonal test inverse failed')
    return adapted


def shared_source(repo, path, previous):
    """Native source layer of the shared liquid tab producer: the whole v0.4.6 drag state, bindings applied afterwards."""
    if path != DAMPED_PATH:
        return previous
    sources, _raws, _manifest = fixed(repo)
    _predecessor(DAMPED_FILE, previous)
    return sources[DAMPED_FILE]


def shared_identity(repo, path):
    if path != DAMPED_PATH:
        return None
    row = _row(repo, DAMPED_FILE)
    return dict(pinnedCommit=COMMIT, originalPath=path, path=ARCHIVE + '/' + DAMPED_FILE,
                sha256Bytes=row['sha256'], gitBlobOid=row['gitBlob'], bytes=row['bytes'])
