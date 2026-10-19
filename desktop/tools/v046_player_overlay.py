"""Whole v0.4.6 player overlay chrome (roadmap slice plr-overlay-and-controlbar-repin).

The vendored overlay is what Bangumi and the offline player draw (ordinary video uses the desktop leaf). Nothing is
re-implemented: the archive carries the verbatim v0.4.6 bytes, each producer that already owns a file swaps its own
predecessor for them behind a hash assertion, and every platform binding that already existed (Android imports, the
NVIDIA slot, the owned dialogs) runs unchanged on top.

  BottomControlBar.kt, TopControlBar.kt, VideoPlayerOverlayContracts.kt, LandscapeDanmakuComposer.kt
                              chain layer of read() in extract-upstream-video-player-full-controls.py, after the
                              v0.3.7 chrome and closure archives
  PersistentProgressBar.kt, VideoProgressBarLayoutPolicy.kt
                              direct-mode v0.2.5 copies; the same chain layer emits the whole v0.4.6 file and Gradle
                              excludes the vendored copy (the packages, plugin and skin features keep using the
                              emitted file under the same top-level names)
  PlayerControlGlass.kt       render closure (v037_player_render.generate_render_sources), same contract as
                              v040_chrome_actions.retained_render_source
  PlainLandscapeDanmakuComposer.kt
                              new in v0.4.0; emitted by the fullscreen-controls producer with six counted Windows
                              adaptations (no Android window handle, the owned player dialog instead of Dialog)
  VideoPlayerOverlay.kt       stays the complete v0.3.7 file with nine counted hunks, because the v0.4.6 file needs
                              LandscapeSidePanel and PagesSelector changes owned by other roadmap slices
  PersistentProgressBarPolicyTest.kt (emitted original test), VideoProgressBarLayoutPolicyTest.kt (the Windows copy in
                              desktop/src/test now holds these exact bytes)

The manifest records predecessors, replaced vendored copies, behaviour changes and what is not carried.
"""
from pathlib import Path
import hashlib
import json

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-player-overlay'
MANIFEST_SHA256 = '4b52704176e7228ef4af4ed204626660b3d39432e00af205884f8577c66b5063'
PROOF_DIRECTORY = 'v046-player-overlay-proofs'
APP = 'app/src/main/java/com/android/purebilibili/'
OVERLAY = APP + 'feature/video/ui/overlay/'
COMPONENTS = APP + 'feature/video/ui/components/'
TEST = 'app/src/test/java/com/android/purebilibili/feature/video/ui/overlay/'
OVERLAY_PACKAGE = 'com.android.purebilibili.feature.video.ui.overlay'
COMPONENTS_PACKAGE = 'com.android.purebilibili.feature.video.ui.components'

ARCHIVE_FILES = (
    'BottomControlBar.kt', 'TopControlBar.kt', 'PlayerControlGlass.kt', 'PersistentProgressBar.kt',
    'VideoProgressBarLayoutPolicy.kt', 'VideoPlayerOverlayContracts.kt', 'LandscapeDanmakuComposer.kt',
    'PlainLandscapeDanmakuComposer.kt', 'VideoProgressBarLayoutPolicyTest.kt', 'PersistentProgressBarPolicyTest.kt',
)
UPSTREAM_PATHS = {
    'BottomControlBar.kt': OVERLAY + 'BottomControlBar.kt',
    'TopControlBar.kt': OVERLAY + 'TopControlBar.kt',
    'PlayerControlGlass.kt': OVERLAY + 'PlayerControlGlass.kt',
    'PersistentProgressBar.kt': OVERLAY + 'PersistentProgressBar.kt',
    'VideoProgressBarLayoutPolicy.kt': OVERLAY + 'VideoProgressBarLayoutPolicy.kt',
    'VideoPlayerOverlayContracts.kt': OVERLAY + 'VideoPlayerOverlayContracts.kt',
    'LandscapeDanmakuComposer.kt': COMPONENTS + 'LandscapeDanmakuComposer.kt',
    'PlainLandscapeDanmakuComposer.kt': COMPONENTS + 'PlainLandscapeDanmakuComposer.kt',
    'VideoProgressBarLayoutPolicyTest.kt': TEST + 'VideoProgressBarLayoutPolicyTest.kt',
    'PersistentProgressBarPolicyTest.kt': TEST + 'PersistentProgressBarPolicyTest.kt',
}
PACKAGES = {
    'LandscapeDanmakuComposer.kt': COMPONENTS_PACKAGE,
    'PlainLandscapeDanmakuComposer.kt': COMPONENTS_PACKAGE,
    **{name: OVERLAY_PACKAGE for name in ARCHIVE_FILES
       if name not in ('LandscapeDanmakuComposer.kt', 'PlainLandscapeDanmakuComposer.kt')},
}
EXPECTED = set(ARCHIVE_FILES)
BY_PATH = {path: name for name, path in UPSTREAM_PATHS.items()}

# files the read() chain of the fullscreen-controls producer swaps whole
CHAIN_FILES = ('BottomControlBar.kt', 'TopControlBar.kt', 'VideoPlayerOverlayContracts.kt',
               'LandscapeDanmakuComposer.kt', 'PersistentProgressBar.kt', 'VideoProgressBarLayoutPolicy.kt')
RENDER_FILE = 'PlayerControlGlass.kt'
PLAIN_FILE = 'PlainLandscapeDanmakuComposer.kt'
ORIGINAL_TEST_FILE = 'PersistentProgressBarPolicyTest.kt'
DESKTOP_TEST_FILE = 'VideoProgressBarLayoutPolicyTest.kt'
DESKTOP_TEST_PATH = ('desktop/src/test/kotlin/com/android/purebilibili/feature/video/ui/overlay/'
                     'VideoProgressBarLayoutPolicyTest.kt')
DESKTOP_TEST_PREVIOUS_SHA256 = 'de76e072b75c2a53fefca52586473f5a10b588b42071fa32a9a2e88f2f46eb1b'
# the two direct-mode v0.2.5 copies that Gradle excludes from prepareUpstreamSources
EXCLUDED_NOW = ('PersistentProgressBar.kt', 'VideoProgressBarLayoutPolicy.kt')
# emitted file of each whole file, relative to the producer output
RELATIVE = {name: UPSTREAM_PATHS[name][len('app/src/main/java/'):] for name in ARCHIVE_FILES if '/src/main/' in UPSTREAM_PATHS[name]}
# written by write_proofs; BottomControlBar and TopControlBar keep the proofs of v037_player_chrome.write_source_proof,
# PlayerControlGlass the proof of v037_player_render, both now relative to the v0.4.6 original
PROOF_FILES = ('VideoPlayerOverlayContracts.kt', 'LandscapeDanmakuComposer.kt', 'PersistentProgressBar.kt',
               'VideoProgressBarLayoutPolicy.kt', PLAIN_FILE)

# LF sha256 of what each producer hands this layer
PREDECESSOR_SHA256 = {
    'BottomControlBar.kt': 'd869e05e416286c340431cd06c66aff59350b6ee656c249763e15564ebf8d29c',
    'TopControlBar.kt': 'ce2a77f51a5b7edeee80e1392f09148cf166705969d05c3e66b92ad8a243e21b',
    'VideoPlayerOverlayContracts.kt': '89fd68782a25c33c4ce5f827f79e987bd47246f4b37ec9d096f3889ba0510c12',
    'LandscapeDanmakuComposer.kt': 'e1b06c6fbf75cb095828cdc3cf5f003fb103cc2539f1ff8b0149e68a916ed747',
    'PersistentProgressBar.kt': '6ad6ecece75468f4e11cd257710c1b8df78e3b695cca2eb26d0e485a5ea41e62',
    'VideoProgressBarLayoutPolicy.kt': '7919c04d360de93abb4cf0832d5ce9b6dde31f33e7fff9971d830fc76e4379c1',
    'PlayerControlGlass.kt': '369653f30e4d7db00118a8f5dc9b2357ca359666d927c2135e0944b64ea9bfbf',
}
# LF sha256 of the direct-mode v0.2.5 copies this slice excludes (desktop/upstream-sources.json pins)
VENDORED_LF_SHA256 = {name: PREDECESSOR_SHA256[name] for name in EXCLUDED_NOW}

DECLARATIONS = {
    'BottomControlBar.kt': (
        'fun BottomControlBar(', 'fullscreenDanmakuActionsInDedicatedDocks: Boolean = false,',
        'private fun PlainLandscapeDockActionRow(', 'private fun PlayerControlMiddleRow(',
        'private fun adaptivePlainDockActionSpacing(',
        'import androidx.compose.ui.platform.LocalConfiguration\n',
        'import androidx.compose.ui.semantics.testTagsAsResourceId\n',
        'import com.android.purebilibili.core.plugin.skin.UiSkinAnimatedAsset\n',
    ),
    'TopControlBar.kt': (
        'fun TopControlBar(', 'onDanmakuSettingsClick: (() -> Unit)? = null,',
        'private fun AdaptivePlayerTitleInfoDock(', 'private fun PlayerTitleInfoDock(',
        'private fun resolveBatteryLevelPercent(context: Context)', 'val context = LocalContext.current',
        'import androidx.compose.ui.platform.LocalConfiguration\n',
    ),
    'PlayerControlGlass.kt': (
        'internal fun PlayerControlGlassDock(', 'LocalAppIconButtonGlassSurfaceProvided provides true,',
    ),
    'PersistentProgressBar.kt': ('AppContentColors.selectionAccent(', 'internal fun PersistentBottomProgressBar('),
    'VideoProgressBarLayoutPolicy.kt': (
        'fun resolveVideoProgressBarLayoutPolicy(', 'internal fun resolveVideoProgressPreviewAreaHeightDp(',
        'baseHeightWithoutChapterDp = 24,', 'touchContainerHeightDp = 12,',
    ),
    'VideoPlayerOverlayContracts.kt': (
        'val hasRenderedFirstFrame: Boolean = true,', 'val suppressTransientOverlaysForTransition: Boolean = false,',
        'val endDrawerReservedWidthProvider: () -> androidx.compose.ui.unit.Dp = { endDrawerReservedWidth },',
        'import androidx.media3.common.Player\n', 'import com.android.purebilibili.core.store.player.PlayerSettingsStore\n',
        '(String, android.os.Bundle?) -> Unit = { _, _ -> }',
    ),
    'LandscapeDanmakuComposer.kt': ('fun LandscapeDanmakuComposerForPlayer(', 'fun LandscapeDanmakuComposer('),
    'PlainLandscapeDanmakuComposer.kt': (
        'fun PlainLandscapeDanmakuComposer(', 'import androidx.compose.ui.window.Dialog\n',
        'import androidx.compose.runtime.SideEffect\n', 'import androidx.compose.ui.platform.LocalView\n',
        'import androidx.compose.ui.window.DialogWindowProvider\n', '                decorFitsSystemWindows = false,\n',
    ),
    'PersistentProgressBarPolicyTest.kt': ('class PersistentProgressBarPolicyTest',),
    'VideoProgressBarLayoutPolicyTest.kt': ('class VideoProgressBarLayoutPolicyTest',),
}

# VideoPlayerOverlay.kt: the complete v0.3.7 file of v037-player-closure plus nine counted hunks. Each `after` is a
# verbatim slice of the v0.4.6 blob below (checked against the git object by the source test).
OVERLAY_PATH = OVERLAY + 'VideoPlayerOverlay.kt'
OVERLAY_PREDECESSOR_SHA256 = '9b490143e3096efc3656df5c23eb7af10c54f52018bae1946ce71fc2053df97e'
OVERLAY_UPSTREAM_BLOB = 'f2255e332befc2437daa3c5ae1ed8a78ce04dbc3'
OVERLAY_RELATIVE = OVERLAY_PATH[len('app/src/main/java/'):]
OVERLAY_HUNKS = (
    dict(
        label='import: the player composer chooses the glass or plain landscape composer',
        before=(
            'import com.android.purebilibili.feature.video.ui.components.LandscapeDanmakuComposer\n'
        ),
        after=(
            'import com.android.purebilibili.feature.video.ui.components.LandscapeDanmakuComposerForPlayer\n'
        ),
    ),
    dict(
        label='shouldShowBufferingIndicator is gated on the first rendered frame and on transition suppression',
        before=(
            'internal fun shouldShowBufferingIndicator(\n'
            '    isBuffering: Boolean,\n'
            '    isQualitySwitching: Boolean,\n'
            '    playWhenReady: Boolean\n'
            '): Boolean {\n'
            '    return isBuffering && playWhenReady && !isQualitySwitching\n'
            '}\n'
        ),
        after=(
            '/**\n'
            ' * 缓冲加载圈的显隐判据。\n'
            ' *\n'
            ' * 进场时播放器刚挂上就是 `STATE_BUFFERING` 且 `playWhenReady=true`，而首帧尚未渲染。\n'
            ' * 此时视频槽位由封面/黑底承担加载表达，再叠一层转圈会在首帧到达瞬间闪一次\n'
            ' * （`isBuffering` 与 `isFirstFrameRendered` 来自两个 Listener，中间隔着若干帧）。\n'
            ' *\n'
            ' * 因此除既有条件外，必须同时满足：\n'
            ' * - [hasRenderedFirstFrame]：首帧已渲染，转圈只用于表达「已有画面后的再缓冲」。\n'
            ' * - [!suppressTransientOverlays]：转场/返回动画期间不合成瞬时 overlay。\n'
            ' */\n'
            'internal fun shouldShowBufferingIndicator(\n'
            '    isBuffering: Boolean,\n'
            '    isQualitySwitching: Boolean,\n'
            '    playWhenReady: Boolean,\n'
            '    hasRenderedFirstFrame: Boolean = true,\n'
            '    suppressTransientOverlays: Boolean = false,\n'
            '): Boolean {\n'
            '    if (!hasRenderedFirstFrame) return false\n'
            '    if (suppressTransientOverlays) return false\n'
            '    return isBuffering && playWhenReady && !isQualitySwitching\n'
            '}\n'
        ),
    ),
    dict(
        label='overlay content reads the first-frame and transition-suppression state',
        before=(
            '    val isBuffering = state.isBuffering\n'
        ),
        after=(
            '    val isBuffering = state.isBuffering\n'
            '    val hasRenderedFirstFrame = state.hasRenderedFirstFrame\n'
            '    val suppressTransientOverlaysForTransition = state.suppressTransientOverlaysForTransition\n'
        ),
    ),
    dict(
        label='overlay content reads the liquid glass switch',
        before=(
            '    val onSponsorContributionCancel = actions.onSponsorContributionCancel\n'
        ),
        after=(
            '    val onSponsorContributionCancel = actions.onSponsorContributionCancel\n'
            '    val liquidGlassEnabled = com.android.purebilibili.core.ui.LocalAppThemeConfig.current.liquidGlassEnabled\n'
        ),
    ),
    dict(
        label='landscape top bar: the danmaku settings entry moves to the top bar only with liquid glass',
        before=(
            '                        isLoggedIn = isLoggedIn,\n'
            '                        onDanmakuInputClick = onDanmakuInputClick,\n'
            '                        onCastClick = onCastClickAction,\n'
        ),
        after=(
            '                        isLoggedIn = isLoggedIn,\n'
            '                        onDanmakuSettingsClick = if (liquidGlassEnabled) {\n'
            '                            { showDanmakuSettings = true }\n'
            '                        } else null,\n'
            '                        onCastClick = onCastClickAction,\n'
        ),
    ),
    dict(
        label='bottom bar: danmaku actions live in dedicated docks with the renamed parameter',
        before=(
            '                    danmakuSendInTopBar = isFullscreen,\n'
        ),
        after=(
            '                    fullscreenDanmakuActionsInDedicatedDocks = isFullscreen,\n'
        ),
    ),
    dict(
        label='fullscreen composer: glass or plain landscape composer',
        before=(
            '        if (isFullscreen && danmakuComposerVisible) {\n'
            '            LandscapeDanmakuComposer(\n'
        ),
        after=(
            '        if (isFullscreen && danmakuComposerVisible) {\n'
            '            LandscapeDanmakuComposerForPlayer(\n'
        ),
    ),
    dict(
        label='centre play control hides in plain fullscreen where the bar owns play and pause',
        before=(
            '        CenterPlaybackControl(\n'
            '            visible = isVisible &&\n'
        ),
        after=(
            '        CenterPlaybackControl(\n'
            '            visible = (!isFullscreen || liquidGlassEnabled) && isVisible &&\n'
        ),
    ),
    dict(
        label='buffering indicator: hidden before the first frame, no fade-in',
        before=(
            '                playWhenReady = player.playWhenReady\n'
            '            ) && centerLoadingUiState == null,\n'
            '            modifier = Modifier.align(Alignment.Center),\n'
            '            enter = fadeIn(OverlayControlFadeSpec),\n'
        ),
        after=(
            '                playWhenReady = player.playWhenReady,\n'
            '                hasRenderedFirstFrame = hasRenderedFirstFrame,\n'
            '                suppressTransientOverlays = suppressTransientOverlaysForTransition,\n'
            '            ) && centerLoadingUiState == null,\n'
            '            modifier = Modifier.align(Alignment.Center),\n'
            '            // 首帧前整段不参与合成，出现时也硬切：淡入会让「已有画面后的再缓冲」\n'
            '            // 看起来像又一次进场闪烁。\n'
            '            enter = EnterTransition.None,\n'
        ),
    ),
)

# PlainLandscapeDanmakuComposer.kt: Android has a window handle behind every Dialog; Compose Desktop has none, and an
# in-canvas Dialog would sit under the heavyweight MPV canvas, so the composer uses the owned player dialog.
PLAIN_EDITS = (
    ('import androidx.compose.runtime.SideEffect\n', '', 'unused after the Android window handle block is removed'),
    ('import androidx.compose.ui.platform.LocalView\n', '', 'Android view handle does not exist on Compose Desktop'),
    ('import androidx.compose.ui.window.DialogWindowProvider\n', '', 'Android dialog window provider does not exist on Compose Desktop'),
    ('import androidx.compose.ui.window.Dialog\n',
     'import com.bilipai.desktop.ui.DesktopWindowsDanmakuComposerDialog as Dialog\n',
     'the composer uses the owned Windows player dialog that stays above the native MPV canvas'),
    ('            val view = LocalView.current\n'
     '            SideEffect {\n'
     '                ((view.parent as? DialogWindowProvider) ?: (view as? DialogWindowProvider))\n'
     '                    ?.window?.setWindowAnimations(0)\n'
     '            }\n',
     '', 'the Android window animation switch has no Compose Desktop counterpart'),
    ('                decorFitsSystemWindows = false,\n', '', 'DialogProperties has no decorFitsSystemWindows on Compose Desktop'),
)
PLAIN_ABSENT = ('LocalView', 'DialogWindowProvider', 'SideEffect', 'decorFitsSystemWindows', 'setWindowAnimations')


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def default_repo():
    return Path(__file__).resolve().parents[2]


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original player overlay inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Player overlay input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Player overlay manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Player overlay source identity changed')
    need([row['filename'] for row in manifest['files']] == list(ARCHIVE_FILES), 'Player overlay source set changed')
    need({row['replacedBy']: row['predecessorSha256'] for row in manifest['producerPredecessors']} ==
         {name: PREDECESSOR_SHA256[name] for name in CHAIN_FILES + (RENDER_FILE,)},
         'Player overlay producer predecessor pins changed')
    need({row['replacedBy']: row['vendoredLfSha256'] for row in manifest['replacedVendoredCopies']} ==
         VENDORED_LF_SHA256, 'Player overlay replaced vendored pins changed')
    hunks = manifest['overlayHunks']
    need((hunks['path'], hunks['upstreamBlob'], hunks['predecessorSha256'], hunks['hunks']) ==
         (OVERLAY_PATH, OVERLAY_UPSTREAM_BLOB, OVERLAY_PREDECESSOR_SHA256, len(OVERLAY_HUNKS)),
         'Player overlay hunk pins changed')
    need((manifest['replacedDesktopTest']['path'], manifest['replacedDesktopTest']['previousSha256'],
          manifest['replacedDesktopTest']['replacedBy']) ==
         (DESKTOP_TEST_PATH, DESKTOP_TEST_PREVIOUS_SHA256, DESKTOP_TEST_FILE), 'Player overlay desktop test pin changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous player overlay source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Player overlay upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Player overlay bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Player overlay Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and
             ('package ' + PACKAGES[file.name] + '\n') in text[:900], 'Player overlay text changed: ' + file.name)
        for token in DECLARATIONS.get(file.name, ()):
            need(text.count(token) == 1, 'Player overlay declaration changed: ' + token.strip())
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Player overlay license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original player overlay inputs')


def _predecessor(name, previous):
    need(sha(previous.replace('\r\n', '\n')) == PREDECESSOR_SHA256[name],
         'Player overlay predecessor changed: ' + name)


def advance_overlay(previous):
    """The complete v0.3.7 overlay plus the counted hunks; returns (text, hunk rows)."""
    need(sha(previous.replace('\r\n', '\n')) == OVERLAY_PREDECESSOR_SHA256, 'Player overlay predecessor changed')
    text, rows = previous, []
    for hunk in OVERLAY_HUNKS:
        need(text.count(hunk['before']) == 1, 'Player overlay anchor changed: ' + hunk['label'])
        text = text.replace(hunk['before'], hunk['after'], 1)
        rows.append(dict(label=hunk['label'], beforeSHA256=sha(hunk['before']), afterSHA256=sha(hunk['after'])))
    restored = text
    for hunk in reversed(OVERLAY_HUNKS):
        need(restored.count(hunk['after']) == 1, 'Player overlay hunk is not unique: ' + hunk['label'])
        restored = restored.replace(hunk['after'], hunk['before'], 1)
    need(restored == previous, 'Player overlay hunks do not invert')
    return text, rows


def chain_source(repo, path, previous):
    """The read() layer of the fullscreen-controls producer, after the v0.3.7 chrome and closure archives."""
    if path == OVERLAY_PATH:
        fixed(repo)
        return advance_overlay(previous)[0]
    name = BY_PATH.get(path)
    if name not in CHAIN_FILES:
        return previous
    sources, _raws, _manifest = fixed(repo)
    _predecessor(name, previous)
    return sources[name]


def _row(repo, name):
    return next(row for row in fixed(repo)[2]['files'] if row['filename'] == name)


def render_source(repo, path, previous, row, commit):
    """Same contract as v040_chrome_actions.retained_render_source: the v0.4.6 glass replaces the v0.3.7 one."""
    if path != UPSTREAM_PATHS[RENDER_FILE]:
        return previous, row, commit
    sources, _raws, _manifest = fixed(repo)
    _predecessor(RENDER_FILE, previous)
    archived = _row(repo, RENDER_FILE)
    return sources[RENDER_FILE], dict(path=path, filename=RENDER_FILE, bytes=archived['bytes'],
                                      sha256Bytes=archived['sha256'], gitBlob=archived['gitBlob']), COMMIT


def effective_original(path, previous, commit):
    """v037_player_chrome.write_source_proof: the original of the two bars is now the v0.4.6 file."""
    name = BY_PATH.get(path)
    if name in ('BottomControlBar.kt', 'TopControlBar.kt'):
        return fixed(default_repo())[0][name], COMMIT
    return previous, commit


def plain_composer(repo):
    """The v0.4.6 Plain landscape composer with the Windows adaptations; returns (text, edit rows)."""
    original = fixed(repo)[0][PLAIN_FILE]
    text, rows = original, []
    for before, after, label in PLAIN_EDITS:
        need(text.count(before) == 1, 'Plain landscape composer anchor changed: ' + label)
        text = text.replace(before, after, 1)
        rows.append(dict(label=label, before=before, after=after))
    for token in PLAIN_ABSENT:
        need(token not in text, 'Plain landscape composer still names ' + token)
    return text, rows


def emit_original_tests(repo, test_output):
    """The upstream PersistentProgressBarPolicyTest, byte for byte (it already uses kotlin.test)."""
    raws = fixed(repo)[1]
    target = Path(test_output) / TEST[len('app/src/test/java/'):] / ORIGINAL_TEST_FILE
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raws[ORIGINAL_TEST_FILE])
    return target


def _save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def write_proofs(repo, output):
    """One proof per emitted file whose producer layer does not already write one."""
    sources, _raws, manifest = fixed(repo)
    root = Path(output)
    written = []
    for name in PROOF_FILES:
        emitted = (root / RELATIVE[name]).read_text(encoding='utf8')
        original = sources[name]
        proof = dict(upstreamCommit=COMMIT, sourcePath=UPSTREAM_PATHS[name], sourceManifestSHA256=MANIFEST_SHA256,
                     originalSHA256=sha(original), emittedSHA256=sha(emitted), wholeOriginalSelected=True,
                     wholeOriginalEmitted=emitted == original, originalToWindows=inverse_proof(original, emitted),
                     sourceQualified=False, mainCompiled=False, jvmTestsExecuted=False)
        if name in PREDECESSOR_SHA256:
            proof['predecessorSHA256'] = PREDECESSOR_SHA256[name]
        _save(root / PROOF_DIRECTORY / (name + '.json'), proof)
        written.append(name)
    emitted = (root / OVERLAY_RELATIVE).read_text(encoding='utf8')
    hunks = advance_overlay(_overlay_predecessor())[1]
    for hunk in OVERLAY_HUNKS:
        need(hunk['after'] in emitted, 'Emitted overlay lost a hunk: ' + hunk['label'])
    _save(root / PROOF_DIRECTORY / 'VideoPlayerOverlay.kt.json',
          dict(upstreamCommit=COMMIT, sourcePath=OVERLAY_PATH, upstreamBlob=OVERLAY_UPSTREAM_BLOB,
               sourceManifestSHA256=MANIFEST_SHA256, predecessorSHA256=OVERLAY_PREDECESSOR_SHA256,
               wholeOriginalSelected=False, hunks=hunks, emittedSHA256=sha(emitted),
               sourceQualified=False, mainCompiled=False, jvmTestsExecuted=False))
    return written + ['VideoPlayerOverlay.kt']


def _overlay_predecessor():
    from v037_player_closure import fixed_sources
    return fixed_sources()[OVERLAY_PATH]
