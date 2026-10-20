"""Whole v0.4.6 control-center drawer motion for the vendored player overlay (roadmap slice plr-control-center-motion).

Upstream 0.3.8 (134b4f9c, landscape drawer width sync) and 0.4.2 (6b789650, control-center motion and the danmaku panel
Host/Appearance split). The vendored overlay is what Bangumi, the offline player and the retained ordinary-video section draw;
the Windows desktop leaf is not touched. Nothing is re-implemented: the archive carries the verbatim v0.4.6 bytes
(d6bf8b1655ae910999860bf13e65c8fcea5b345b), the existing producer swaps its own v0.3.7 predecessor for them behind a hash
assertion, and every Windows binding that already existed runs unchanged on top.

  LandscapeSidePanel.kt      the whole v0.4.6 file replaces the v0.3.7 file of v037-player-closure in the read() chain of
                             extract-upstream-video-player-full-controls.py. With enableControlCenterMotion left false (the
                             default, and what the comment drawers pass) it keeps the v0.3.7 drag limits and tween timings;
                             it adds the shared spring clock, the landscapeControlCenterGroup modifier and the measure-time
                             landscapeEndDrawerPadding modifier.
  ControlCenterMotionSpec.kt, VideoControlCenterMotion.kt, DanmakuSettingsPanelAppearance.kt
                             new in v0.4.2; emitted verbatim by the same producer.
  DanmakuSettingsPanelHost.kt
                             new in v0.4.2; emitted by the same producer with ONE adaptation: the Dialog import names the
                             owned Windows player dialog (the same alias the Windows DanmakuSettingsPanel already uses), so
                             the host would stay above the native mpv canvas. NOTHING calls the host: the Windows
                             DanmakuSettingsPanel keeps its own dialog body (adopting the host would change the danmaku
                             settings presentation of the desktop leaf).
  VideoPlayerOverlay.kt      thirteen counted, verbatim v0.4.6 hunks on the end drawer (drawer width as a measure-time
                             provider, shared close progress, the drawer panel opts into the motion, four motion groups).
  VideoSettingsPanel.kt      three counted hunks and one counted rule: the glass sheet opens fully with a gesture header and
                             every LazyColumn item becomes a motion group. Non-glass keeps the original handle and items.
  DanmakuControlCenterMotionPolicyTest.kt, VideoControlCenterMotionTest.kt
                             emitted original tests (see ORIGINAL_TEST_EDITS for the three recomputed literals).

The hunks are COUNTED, not hash pinned: the producer text they run on is the output of many Windows adaptations, so each anchor
must occur exactly once and every `after` is a verbatim slice of the v0.4.6 blob (checked by the source test).
"""
from pathlib import Path
import hashlib
import json
import re

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-control-center-motion'
MANIFEST_SHA256 = '2a1114a91b8471e07fced3697f13116628bac6e211e0679a96ba7153ef3aff33'
PROOF_DIRECTORY = 'v046-control-center-motion-proofs'
APP = 'app/src/main/java/com/android/purebilibili/'
COMPONENTS = APP + 'feature/video/ui/components/'
OVERLAY = APP + 'feature/video/ui/overlay/'
TEST = 'app/src/test/java/com/android/purebilibili/feature/video/ui/components/'
COMPONENTS_PACKAGE = 'com.android.purebilibili.feature.video.ui.components'

SPEC_FILE = 'ControlCenterMotionSpec.kt'
MOTION_FILE = 'VideoControlCenterMotion.kt'
HOST_FILE = 'DanmakuSettingsPanelHost.kt'
APPEARANCE_FILE = 'DanmakuSettingsPanelAppearance.kt'
PANEL_FILE = 'LandscapeSidePanel.kt'
DANMAKU_TEST_FILE = 'DanmakuControlCenterMotionPolicyTest.kt'
MOTION_TEST_FILE = 'VideoControlCenterMotionTest.kt'
ARCHIVE_FILES = (SPEC_FILE, MOTION_FILE, HOST_FILE, APPEARANCE_FILE, PANEL_FILE, DANMAKU_TEST_FILE, MOTION_TEST_FILE)
EXPECTED = set(ARCHIVE_FILES)
UPSTREAM_PATHS = {
    name: (TEST if name.endswith('Test.kt') else COMPONENTS) + name for name in ARCHIVE_FILES
}
BY_PATH = {path: name for name, path in UPSTREAM_PATHS.items()}
# emitted file of each main leaf, relative to the producer output (and to com/android/purebilibili/ for emit())
RELATIVE = {name: UPSTREAM_PATHS[name][len('app/src/main/java/'):] for name in ARCHIVE_FILES if '/src/main/' in UPSTREAM_PATHS[name]}
EMIT_REL = {name: 'feature/video/ui/components/' + name for name in (SPEC_FILE, MOTION_FILE, HOST_FILE, APPEARANCE_FILE)}
TEST_RELATIVE = {name: UPSTREAM_PATHS[name][len('app/src/test/java/'):] for name in (DANMAKU_TEST_FILE, MOTION_TEST_FILE)}
TEST_COUNTS = {DANMAKU_TEST_FILE: 3, MOTION_TEST_FILE: 6}

# LF sha256 of what the read() chain hands this layer for LandscapeSidePanel.kt: the v0.3.7 file of v037-player-closure
PANEL_PREDECESSOR_SHA256 = 'f7865f21408545767fbeeed0930deb28d8df70da9bff07239d74de24f89b8a04'

DECLARATIONS = {
    SPEC_FILE: ('internal object ControlCenterMotionSpec {', 'const val MaxOverExpansion = 0.25f'),
    MOTION_FILE: ('internal class VideoControlCenterMotion {', 'internal fun shouldCloseVideoControlCenter(',
                  'internal fun rememberVideoControlCenterMotion(', 'internal fun VideoControlCenterHandle(',
                  'internal class VideoControlCenterGroups(',
                  '        progress = (progress + delta).coerceIn(0f, 1.5f)\n'),
    HOST_FILE: ('internal fun useDanmakuControlCenterMotion(', 'internal fun DanmakuSettingsPanelHost(',
                'private class DanmakuPanelDismissAction {', 'import androidx.compose.ui.window.Dialog\n',
                'enableControlCenterMotion = true,'),
    APPEARANCE_FILE: ('internal fun rememberDanmakuPanelColors(', 'internal fun DanmakuSyncButton('),
    PANEL_FILE: ('internal fun LandscapeSidePanel(', 'enableControlCenterMotion: Boolean = false,',
                 'internal fun Modifier.landscapeControlCenterGroup(index: Int): Modifier {',
                 'internal fun Modifier.landscapeEndDrawerPadding(widthProvider: () -> Dp): Modifier ='),
    DANMAKU_TEST_FILE: ('class DanmakuControlCenterMotionPolicyTest {',),
    MOTION_TEST_FILE: ('class VideoControlCenterMotionTest {',),
}

# DanmakuSettingsPanelHost.kt: the only adaptation. The Windows DanmakuSettingsPanel aliases the same owned dialog.
HOST_EDITS = (
    ('import androidx.compose.ui.window.Dialog\n',
     'import com.bilipai.desktop.ui.DesktopWindowsDanmakuDialog as Dialog\n',
     'the host dialog is the owned Windows player dialog that stays above the native mpv canvas'),
)

# VideoControlCenterMotionTest.kt: two upstream cases contradict upstream's own VideoControlCenterMotion at d6bf8b16
# (dragBy clamps to 1.5f; the cases were written for ControlCenterMotionSpec.dragProgress, 0.35 resistance and 1.25f). The
# production file is carried unchanged, so the literals follow it. Original literal, recomputed literal and calculation:
#   1.0f + 0.4f = 1.4f                    (original 1.14f = 1 + 0.4 * 0.35)
#   dragBy(20f) from 0 = 1.5f             (original 1.25f = 1 + MaxOverExpansion)
#   1.5f + (-0.25f) = 1.25f               (original 1f)
ORIGINAL_TEST_EDITS = (
    ('        motion.dragBy(0.4f)\n        assertEquals(1.14f, motion.progress, absoluteTolerance = 0.0001f)\n',
     '        motion.dragBy(0.4f)\n'
     '        // Windows port: dragBy at v0.4.6 adds the delta (1f + 0.4f) up to the 1.5f clamp; the upstream literal 1.14f\n'
     '        // assumed the 0.35 resistance of ControlCenterMotionSpec.dragProgress.\n'
     '        assertEquals(1.4f, motion.progress, absoluteTolerance = 0.0001f)\n',
     'upwardPullAddsResistanceOnlyAfterFullyOpen: 1.14f -> 1.4f'),
    ('        motion.dragBy(20f)\n        assertEquals(1.25f, motion.progress)\n',
     '        motion.dragBy(20f)\n'
     '        // Windows port: the v0.4.6 clamp is 1.5f; the upstream literal 1.25f is 1f + ControlCenterMotionSpec.MaxOverExpansion.\n'
     '        assertEquals(1.5f, motion.progress)\n',
     'overExpansionSettlesOpenAndCanBeReversedWithoutADeadZone: first 1.25f -> 1.5f'),
    ('        motion.dragBy(-0.25f)\n        assertEquals(1f, motion.progress)\n',
     '        motion.dragBy(-0.25f)\n'
     '        // Windows port: 1.5f - 0.25f; the upstream literal 1f followed from the 1.25f cap.\n'
     '        assertEquals(1.25f, motion.progress)\n',
     'overExpansionSettlesOpenAndCanBeReversedWithoutADeadZone: reversal 1f -> 1.25f'),
)

# VideoPlayerOverlay.kt: thirteen counted hunks on the output of the producer chain, after the v0.4.6 overlay hunks, the
# follow confirmation and the page cover hunk. Each `after` is a verbatim slice of the v0.4.6 blob.
OVERLAY_PATH = OVERLAY + 'VideoPlayerOverlay.kt'
OVERLAY_UPSTREAM_BLOB = 'f2255e332befc2437daa3c5ae1ed8a78ce04dbc3'
OVERLAY_EMIT_REL = 'feature/video/ui/overlay/VideoPlayerOverlay.kt'
OVERLAY_RELATIVE = OVERLAY_PATH[len('app/src/main/java/'):]
OVERLAY_HUNKS = (
    dict(
        label='imports: the two drawer modifiers of LandscapeSidePanel.kt',
        before=(
            'import com.android.purebilibili.feature.video.ui.components.LandscapeSidePanel\n'
            'import com.android.purebilibili.feature.video.ui.components.LandscapeSidePanelEdge\n'
        ),
        after=(
            'import com.android.purebilibili.feature.video.ui.components.LandscapeSidePanel\n'
            'import com.android.purebilibili.feature.video.ui.components.landscapeControlCenterGroup\n'
            'import com.android.purebilibili.feature.video.ui.components.landscapeEndDrawerPadding\n'
            'import com.android.purebilibili.feature.video.ui.components.LandscapeSidePanelEdge\n'
        ),
    ),
    dict(
        label='overlay content reads the drawer width as a provider and the drawer close progress',
        before=(
            '    val endDrawerReservedWidth = state.endDrawerReservedWidth\n'
        ),
        after=(
            '    val endDrawerReservedWidthProvider = state.endDrawerReservedWidthProvider\n'
            '    val endDrawerCloseProgress = state.endDrawerCloseProgress ?: remember { mutableFloatStateOf(1f) }\n'
        ),
    ),
    dict(
        label='video viewport inset follows the drawer width provider during measurement',
        before=(
            '            val endInset = endDrawerReservedWidth.roundToPx()\n'
        ),
        after=(
            '            val endInset = endDrawerReservedWidthProvider().roundToPx()\n'
        ),
    ),
    dict(
        label='top bar reserves the drawer width through the measure-time modifier',
        before=(
            '                .align(Alignment.TopCenter)\n'
            '                .padding(end = endDrawerReservedWidth)\n'
        ),
        after=(
            '                .align(Alignment.TopCenter)\n'
            '                .landscapeEndDrawerPadding(endDrawerReservedWidthProvider)\n'
        ),
    ),
    dict(
        label='bottom bar reserves the drawer width through the measure-time modifier',
        before=(
            '\n'
            '                .align(Alignment.BottomCenter)\n'
            '                .padding(end = endDrawerReservedWidth)\n'
        ),
        after=(
            '\n'
            '                .align(Alignment.BottomCenter)\n'
            '                .landscapeEndDrawerPadding(endDrawerReservedWidthProvider)\n'
        ),
    ),
    dict(
        label='persistent progress bar reserves the drawer width through the measure-time modifier',
        before=(
            '\n'
            '                    .align(Alignment.BottomCenter)\n'
            '                    .padding(end = endDrawerReservedWidth)\n'
        ),
        after=(
            '\n'
            '                    .align(Alignment.BottomCenter)\n'
            '                    .landscapeEndDrawerPadding(endDrawerReservedWidthProvider)\n'
        ),
    ),
    dict(
        label='the drawer call hands over its close progress',
        before=(
            '        LandscapeEndDrawer(\n'
            '            visible = endDrawerVisible && isFullscreen,\n'
        ),
        after=(
            '        LandscapeEndDrawer(\n'
            '            visible = endDrawerVisible && isFullscreen,\n'
            '            closeProgress = endDrawerCloseProgress,\n'
        ),
    ),
    dict(
        label='the drawer takes the close progress parameter',
        before=(
            '    hazeState: HazeState? = null,\n'
            '    modifier: Modifier = Modifier\n'
            ') {\n'
            '    val context = LocalContext.current\n'
        ),
        after=(
            '    hazeState: HazeState? = null,\n'
            '    modifier: Modifier = Modifier,\n'
            '    closeProgress: androidx.compose.runtime.MutableFloatState = remember { mutableFloatStateOf(1f) },\n'
            ') {\n'
            '    val context = LocalContext.current\n'
        ),
    ),
    dict(
        label='the drawer panel shares the progress and opts into the control-center motion',
        before=(
            '                width = layoutPolicy.drawerWidthDp.dp,\n'
            '                onDismiss = onDismiss,\n'
        ),
        after=(
            '                width = layoutPolicy.drawerWidthDp.dp,\n'
            '                closeProgress = closeProgress,\n'
            '                enableControlCenterMotion = true,\n'
            '                onDismiss = onDismiss,\n'
        ),
    ),
    dict(
        label='group 0: the owner header',
        before=(
            '                            .fillMaxWidth()\n'
            '                            .padding(layoutPolicy.headerPaddingDp.dp)\n'
        ),
        after=(
            '                            .fillMaxWidth()\n'
            '                            .landscapeControlCenterGroup(index = 0)\n'
            '                            .padding(layoutPolicy.headerPaddingDp.dp)\n'
        ),
    ),
    dict(
        label='group 1: the tab row',
        before=(
            '                                .fillMaxWidth()\n'
            '                                .padding(horizontal = layoutPolicy.listContentPaddingDp.dp),\n'
            '                            labelFontSize = layoutPolicy.followButtonFontSp.sp,\n'
        ),
        after=(
            '                                .fillMaxWidth()\n'
            '                                .landscapeControlCenterGroup(index = 1)\n'
            '                                .padding(horizontal = layoutPolicy.listContentPaddingDp.dp),\n'
            '                            labelFontSize = layoutPolicy.followButtonFontSp.sp,\n'
        ),
    ),
    dict(
        label='group 1: the section title',
        before=(
            '                            fontWeight = FontWeight.Bold,\n'
            '                            modifier = Modifier.padding(layoutPolicy.listContentPaddingDp.dp)\n'
        ),
        after=(
            '                            fontWeight = FontWeight.Bold,\n'
            '                            modifier = Modifier.landscapeControlCenterGroup(index = 1)\n'
            '                                .padding(layoutPolicy.listContentPaddingDp.dp)\n'
        ),
    ),
    dict(
        label='group 2: the list',
        before=(
            '                    Box(modifier = Modifier.weight(1f)) {\n'
            '                        if (selectedTab == 0) {\n'
        ),
        after=(
            '                    Box(modifier = Modifier.weight(1f).landscapeControlCenterGroup(index = 2)) {\n'
            '                        if (selectedTab == 0) {\n'
        ),
    ),
)

# VideoSettingsPanel.kt: three counted hunks plus the counted item rule, on the output of the producer chain after the
# Windows enhancement leaf and the native audio slot.
SETTINGS_PATH = COMPONENTS + 'VideoSettingsPanel.kt'
SETTINGS_UPSTREAM_BLOB = '590790ab86a8d26f93fc3788c41192a9b31dda05'
SETTINGS_EMIT_REL = 'feature/video/ui/components/VideoSettingsPanel.kt'
SETTINGS_RELATIVE = SETTINGS_PATH[len('app/src/main/java/'):]
SETTINGS_HUNKS = (
    dict(
        label='the glass sheet opens fully: the motion owner decides the sheet state',
        before=(
            '    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)\n'
        ),
        after=(
            '    val controlCenterMotion = rememberVideoControlCenterMotion()\n'
            '    // Glass opens fully so its header can stretch the groups upward without competing\n'
            '    // with a half-expanded anchor. Non-glass retains the original partial-sheet behavior.\n'
            '    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = controlCenterMotion != null)\n'
        ),
    ),
    dict(
        label='glass alone supplies the gesture header; native hosts keep their original drag handle',
        before=(
            '        onDismissRequest = onDismiss,\n'
            '        sheetState = sheetState\n'
            '    ) {\n'
            '        LazyColumn(\n'
        ),
        after=(
            '        onDismissRequest = onDismiss,\n'
            '        sheetState = sheetState,\n'
            '        // Glass alone supplies a gesture header; native hosts keep their original handle.\n'
            '        dragHandle = if (controlCenterMotion != null) null else {\n'
            '            { BottomSheetDefaults.DragHandle() }\n'
            '        },\n'
            '    ) {\n'
            '        if (controlCenterMotion != null) {\n'
            '            VideoControlCenterHandle(\n'
            '                motion = controlCenterMotion,\n'
            '                onDismiss = onDismiss,\n'
            '                modifier = Modifier.fillMaxWidth(),\n'
            '            )\n'
            '        }\n'
            '        LazyColumn(\n'
        ),
    ),
    dict(
        label='the lazy column declares the motion groups before its first item',
        before=(
            '                    8.dp\n'
            '                }\n'
            '            )\n'
            '        ) {\n'
        ),
        after=(
            '                    8.dp\n'
            '                }\n'
            '            )\n'
            '        ) {\n'
            '            val groups = VideoControlCenterGroups(this, controlCenterMotion)\n'
            '\n'
        ),
    ),
)
GROUPS_DECLARATION = '            val groups = VideoControlCenterGroups(this, controlCenterMotion)\n'
# 21 `item {` blocks in the v0.3.7 file become `groups.item {` in v0.4.6; the Windows panel has one more: the native audio
# track slot (extract-upstream-video-player-full-controls.py, windows_native_audio_settings_ui).
SETTINGS_UPSTREAM_GROUP_ITEMS = 21
SETTINGS_WINDOWS_GROUP_ITEMS = 1
ITEM_PATTERN = re.compile(r'(?m)^( *)item \{')


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def default_repo():
    return Path(__file__).resolve().parents[2]


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing control-center motion inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Control-center motion input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Control-center motion manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Control-center motion source identity changed')
    need([row['filename'] for row in manifest['files']] == list(ARCHIVE_FILES), 'Control-center motion source set changed')
    need([(row['replacedBy'], row['predecessorSha256']) for row in manifest['producerPredecessors']] ==
         [(PANEL_FILE, PANEL_PREDECESSOR_SHA256)], 'Control-center motion producer predecessor pin changed')
    hunks = manifest['emittedHunks']
    need((hunks['overlay']['path'], hunks['overlay']['upstreamBlob'], hunks['overlay']['hunks']) ==
         (OVERLAY_PATH, OVERLAY_UPSTREAM_BLOB, len(OVERLAY_HUNKS)), 'Control-center motion overlay hunk pins changed')
    need((hunks['settingsPanel']['path'], hunks['settingsPanel']['upstreamBlob'], hunks['settingsPanel']['hunks'],
          hunks['settingsPanel']['upstreamGroupItems'], hunks['settingsPanel']['windowsGroupItems']) ==
         (SETTINGS_PATH, SETTINGS_UPSTREAM_BLOB, len(SETTINGS_HUNKS), SETTINGS_UPSTREAM_GROUP_ITEMS,
          SETTINGS_WINDOWS_GROUP_ITEMS), 'Control-center motion settings panel hunk pins changed')
    need(len(manifest['windowsAdaptations']) == len(HOST_EDITS) and
         len(manifest['originalTestEdits']) == len(ORIGINAL_TEST_EDITS), 'Control-center motion adaptation pins changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous control-center motion source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Control-center motion upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Control-center motion bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Control-center motion Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and
             ('package ' + COMPONENTS_PACKAGE + '\n') in text[:200], 'Control-center motion text changed: ' + file.name)
        for token in DECLARATIONS.get(file.name, ()):
            need(text.count(token) == 1, 'Control-center motion declaration changed: ' + token.strip())
        need(text.count('@Test') == TEST_COUNTS.get(file.name, 0), 'Control-center motion test count changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Control-center motion license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps control-center motion inputs')


def chain_source(repo, path, previous):
    """The read() layer of the fullscreen-controls producer: the whole v0.4.6 side panel replaces the v0.3.7 one."""
    if path != UPSTREAM_PATHS[PANEL_FILE]:
        return previous
    sources = fixed(repo)[0]
    need(sha(previous.replace('\r\n', '\n')) == PANEL_PREDECESSOR_SHA256, 'Landscape side panel predecessor changed')
    return sources[PANEL_FILE]


def host_source(repo):
    """The v0.4.6 host with the owned Windows dialog; returns (text, edit rows)."""
    original = fixed(repo)[0][HOST_FILE]
    text, rows = original, []
    for before, after, label in HOST_EDITS:
        need(text.count(before) == 1, 'Danmaku panel host anchor changed: ' + label)
        text = text.replace(before, after, 1)
        rows.append(dict(label=label, before=before, after=after))
    need('androidx.compose.ui.window.Dialog\n' not in text, 'Danmaku panel host still names the in-canvas dialog')
    return text, rows


def definer_files(repo):
    """(path relative to com/android/purebilibili/, text, mode) of the four new main files, for the producer's emit()."""
    sources = fixed(repo)[0]
    host, _rows = host_source(repo)
    return [
        (EMIT_REL[SPEC_FILE], sources[SPEC_FILE], 'complete-original-v046-control-center-motion-spec'),
        (EMIT_REL[MOTION_FILE], sources[MOTION_FILE], 'complete-original-v046-video-control-center-motion'),
        (EMIT_REL[APPEARANCE_FILE], sources[APPEARANCE_FILE], 'complete-original-v046-danmaku-panel-appearance'),
        (EMIT_REL[HOST_FILE], host, 'complete-original-v046-danmaku-panel-host-owned-windows-dialog'),
    ]


def _apply(text, hunks, what):
    rows = []
    for hunk in hunks:
        need(text.count(hunk['before']) == 1, what + ' anchor changed: ' + hunk['label'])
        text = text.replace(hunk['before'], hunk['after'], 1)
        rows.append(dict(label=hunk['label'], before=hunk['before'], after=hunk['after']))
    return text, rows


def advance_overlay(text):
    """The thirteen end-drawer hunks on the producer's overlay text; returns (text, rows)."""
    return _apply(text, OVERLAY_HUNKS, 'Control-center overlay')


def advance_settings_panel(text):
    """The sheet hunks and the counted item rule on the producer's settings panel text; returns (text, rows)."""
    text, rows = _apply(text, SETTINGS_HUNKS, 'Control-center settings panel')
    need(text.count(GROUPS_DECLARATION) == 1, 'Control-center settings panel group declaration moved')
    at = text.index(GROUPS_DECLARATION)
    head, tail = text[:at], text[at:]
    need(not ITEM_PATTERN.search(head), 'Control-center settings panel has a lazy item before the group declaration')
    found = ITEM_PATTERN.findall(tail)
    need(len(found) == SETTINGS_UPSTREAM_GROUP_ITEMS + SETTINGS_WINDOWS_GROUP_ITEMS,
         'Control-center settings panel item count changed: %d' % len(found))
    tail = ITEM_PATTERN.sub(lambda match: match.group(1) + 'groups.item {', tail)
    need(not ITEM_PATTERN.search(tail), 'Control-center settings panel kept a plain lazy item')
    rows.append(dict(label='every LazyColumn item of the settings panel is a motion group', before='item {',
                     after='groups.item {', count=len(found)))
    return head + tail, rows


EMITTED_ADVANCES = {OVERLAY_EMIT_REL: advance_overlay, SETTINGS_EMIT_REL: advance_settings_panel}


def advance_emitted(rel, text):
    """The emit() hook of the producer: the last layer of the overlay and the settings panel; (text, adaptation rows)."""
    advance = EMITTED_ADVANCES.get(rel)
    if advance is None:
        return text, []
    return advance(text)


def original_tests(repo):
    """{name: text} of the two upstream tests; the motion test carries the three recomputed literals."""
    sources = fixed(repo)[0]
    result = {DANMAKU_TEST_FILE: sources[DANMAKU_TEST_FILE]}
    text = sources[MOTION_TEST_FILE]
    for before, after, label in ORIGINAL_TEST_EDITS:
        need(text.count(before) == 1, 'Original motion test anchor changed: ' + label)
        text = text.replace(before, after, 1)
    result[MOTION_TEST_FILE] = text
    return result


def emit_original_tests(repo, test_output):
    written = []
    for name, text in original_tests(repo).items():
        target = Path(test_output) / TEST_RELATIVE[name]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(text.encode('utf8'))
        written.append(target)
    return written


def _save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def write_proofs(repo, output):
    """One receipt per emitted file this slice touches."""
    sources, _raws, manifest = fixed(repo)
    root = Path(output)
    written = []
    for name, rel in EMIT_REL.items():
        emitted = (root / 'com/android/purebilibili' / rel).read_text(encoding='utf8')
        original = sources[name]
        proof = dict(upstreamCommit=COMMIT, sourcePath=UPSTREAM_PATHS[name], sourceManifestSHA256=MANIFEST_SHA256,
                     originalSHA256=sha(original), emittedSHA256=sha(emitted), wholeOriginalSelected=True,
                     wholeOriginalEmitted=emitted == original, sourceQualified=False, mainCompiled=False,
                     jvmTestsExecuted=False)
        if emitted != original:
            proof['originalToWindows'] = inverse_proof(original, emitted)
        _save(root / PROOF_DIRECTORY / (name + '.json'), proof)
        written.append(name)
    panel = (root / RELATIVE[PANEL_FILE]).read_text(encoding='utf8')
    need(panel == sources[PANEL_FILE], 'Emitted side panel is not the whole v0.4.6 file')
    _save(root / PROOF_DIRECTORY / (PANEL_FILE + '.json'),
          dict(upstreamCommit=COMMIT, sourcePath=UPSTREAM_PATHS[PANEL_FILE], sourceManifestSHA256=MANIFEST_SHA256,
               predecessorSHA256=PANEL_PREDECESSOR_SHA256, originalSHA256=sha(sources[PANEL_FILE]),
               emittedSHA256=sha(panel), wholeOriginalSelected=True, wholeOriginalEmitted=True,
               sourceQualified=False, mainCompiled=False, jvmTestsExecuted=False))
    written.append(PANEL_FILE)
    for path, relative, hunks, blob, extra in (
            (OVERLAY_PATH, OVERLAY_RELATIVE, OVERLAY_HUNKS, OVERLAY_UPSTREAM_BLOB, {}),
            (SETTINGS_PATH, SETTINGS_RELATIVE, SETTINGS_HUNKS, SETTINGS_UPSTREAM_BLOB,
             dict(groupItems=SETTINGS_UPSTREAM_GROUP_ITEMS + SETTINGS_WINDOWS_GROUP_ITEMS))):
        emitted = (root / relative).read_text(encoding='utf8')
        for hunk in hunks:
            need(hunk['after'] in emitted, 'Emitted file lost a control-center hunk: ' + hunk['label'])
        # No digest of the emitted file: other layers of the same file (the overlay hunks, the follow confirmation, the page
        # cover) change it, and this receipt only describes the counted hunks of this slice.
        _save(root / PROOF_DIRECTORY / (Path(path).name + '.json'),
              dict(upstreamCommit=COMMIT, sourcePath=path, upstreamBlob=blob, sourceManifestSHA256=MANIFEST_SHA256,
                   wholeOriginalSelected=False, hunksFoundInEmitted=True,
                   countedHunks=[dict(label=h['label'], beforeSHA256=sha(h['before']), afterSHA256=sha(h['after']))
                                 for h in hunks],
                   sourceQualified=False, mainCompiled=False, jvmTestsExecuted=False, **extra))
        written.append(Path(path).name)
    return written
