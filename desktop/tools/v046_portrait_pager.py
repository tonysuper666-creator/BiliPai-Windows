"""Whole v0.4.6 portrait pager family (roadmap slice plr-portrait-story-pager-repin).

The vertical feed (Story and the portrait video pager) draws PortraitVideoPager through six overlay and policy files.
Nothing is re-implemented: the archive carries the verbatim v0.4.6 bytes, each producer that already owns a file swaps
its own v0.2.5 predecessor for them behind hash assertions, and every platform binding that already existed (the
window metrics alias, the creator team facade, the Windows media/cache ports, the isVerticalVideo parameter) runs
unchanged on top.

  PortraitFullscreenOverlay.kt, PortraitInteractionBar.kt, PortraitBottomInputBar.kt, PortraitProgressBar.kt,
  PortraitVideoLoadPolicy.kt
                              rows of SPECS in extract-upstream-video-fullscreen-pager.py: spec_source() hands the
                              producer the archive bytes instead of the vendored v0.2.5 file and the row's own
                              counted edits then apply to them unchanged (each row's outputSHA is the new value)
  PortraitPagerSwitchPolicy.kt
                              extract-upstream-story-topic.py: switch_policy() applies the producer's own two
                              isVerticalVideo substitutions to the archive bytes and one counted hunk that keeps the
                              Windows cover rule (see COVER_HUNK)
  PortraitControlsVisibilityPolicy.kt
                              new in v0.4.0, emitted whole by the fullscreen pager producer
  PortraitVideoPager.kt       NOT carried whole (4567 lines, 124 existing producer edits and nine layers). The last
                              layer of the producer applies thirteen counted hunks; each inserted text is a verbatim
                              slice of the v0.4.6 blob (checked against the git object by the source test) except the
                              one declared settings adaptation of the declaration hunk.

The follow confirmation call sites of the overlay (v046_follow_confirmation.PORTRAIT) are retired by this slice: the
whole v0.4.6 overlay already calls rememberFollowActionWithConfirmation at both follow buttons.

The manifest records the predecessors, the hunk pins, what is not carried, what is only partly delivered and the known
deviations.

Partly delivered (manifest partiallyDelivered): v035-portrait-pager-preload-identity. The cid-aware policies are carried
(resolvePortraitMediaId, the cid-aware shouldSkipPortraitReloadForCurrentMedia, resolvePortraitPlayUrlPreloadTargets and the
new resolvePortraitPrefetchWindowTargets), but the retained Windows pager still deduplicates its prefetches by bvid alone
(portraitPrefetchedPlayUrlBvids.add(identity.bvid) and .add(target.bvid)) and nothing outside the JVM test calls the prefetch
window helper: preloading is NOT cid-aware end to end until the whole pager is re-pinned (or a counted hunk keys the
prefetch set by media id).

Known deviations (manifest knownDeviations, static judgement, not run): the idle auto-hide waits for Lifecycle RESUMED, which
Compose Desktop lowers to STARTED while the window is unfocused, and hidden controls return only through the tap gesture (no
mouse-move reveal); the danmaku flag the hunk reads is upstream's showDanmakuInputDialog, which the composer never sets.

Size and cut: roughly 1700 changed lines without the archive (this generator, its source test, the JVM class and the
registration edits, 166 of them catalog rows), above the 1200 line guideline. The cut is coherent: the pager gets
thirteen counted hunks instead of a whole re-pin. The follow-up slices are the whole
PortraitVideoPager re-pin, the PortraitWifiPreloadQueue / prefetch-window caller, v029-portrait-background-continue and the
PortraitDetailSheet / PortraitTopBarLayoutPolicy theme-role sizes.
"""
from pathlib import Path
import hashlib
import json
import os

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-portrait-pager'
MANIFEST_SHA256 = '18b0c91a698130d5266703dceb299a162d2c7a02f9a7b828951e313d3ad42f58'
PROOF_DIRECTORY = 'v046-portrait-pager-proofs'
APP = 'app/src/main/java/com/android/purebilibili/'
OVERLAY = APP + 'feature/video/ui/overlay/'
PAGER_DIRECTORY = APP + 'feature/video/ui/pager/'
OVERLAY_PACKAGE = 'com.android.purebilibili.feature.video.ui.overlay'
PAGER_PACKAGE = 'com.android.purebilibili.feature.video.ui.pager'
OUT = 'com/android/purebilibili/feature/video/ui/'

ARCHIVE_FILES = (
    'PortraitFullscreenOverlay.kt', 'PortraitInteractionBar.kt', 'PortraitBottomInputBar.kt', 'PortraitProgressBar.kt',
    'PortraitVideoLoadPolicy.kt', 'PortraitPagerSwitchPolicy.kt', 'PortraitControlsVisibilityPolicy.kt',
)
UPSTREAM_PATHS = {
    'PortraitFullscreenOverlay.kt': OVERLAY + 'PortraitFullscreenOverlay.kt',
    'PortraitInteractionBar.kt': OVERLAY + 'PortraitInteractionBar.kt',
    'PortraitBottomInputBar.kt': OVERLAY + 'PortraitBottomInputBar.kt',
    'PortraitProgressBar.kt': OVERLAY + 'PortraitProgressBar.kt',
    'PortraitVideoLoadPolicy.kt': PAGER_DIRECTORY + 'PortraitVideoLoadPolicy.kt',
    'PortraitPagerSwitchPolicy.kt': PAGER_DIRECTORY + 'PortraitPagerSwitchPolicy.kt',
    'PortraitControlsVisibilityPolicy.kt': PAGER_DIRECTORY + 'PortraitControlsVisibilityPolicy.kt',
}
PACKAGES = {name: (OVERLAY_PACKAGE if UPSTREAM_PATHS[name].startswith(OVERLAY) else PAGER_PACKAGE)
            for name in ARCHIVE_FILES}
EXPECTED = set(ARCHIVE_FILES)

# Rows of SPECS in the fullscreen pager producer: emitted path -> archive leaf.
SPEC_LEAVES = {
    OUT + 'overlay/PortraitFullscreenOverlay.kt': 'PortraitFullscreenOverlay.kt',
    OUT + 'overlay/PortraitInteractionBar.kt': 'PortraitInteractionBar.kt',
    OUT + 'overlay/PortraitBottomInputBar.kt': 'PortraitBottomInputBar.kt',
    OUT + 'overlay/PortraitProgressBar.kt': 'PortraitProgressBar.kt',
    OUT + 'pager/PortraitVideoLoadPolicy.kt': 'PortraitVideoLoadPolicy.kt',
}
SWITCH_LEAF = 'PortraitPagerSwitchPolicy.kt'
CONTROLS_LEAF = 'PortraitControlsVisibilityPolicy.kt'
CONTROLS_OUTPUT = OUT + 'pager/PortraitControlsVisibilityPolicy.kt'
CONTROLS_MODE = 'complete-original-v046-portrait-controls-visibility-policy'
PAGER = OUT + 'pager/PortraitVideoPager.kt'
PAGER_UPSTREAM_PATH = PAGER_DIRECTORY + 'PortraitVideoPager.kt'
PAGER_UPSTREAM_BLOB = 'c4b0d9bea38d488d6bf74fae4d62467178f19115'

# LF sha256 of the vendored v0.2.5 file each producer started from, and of the producer text before this layer.
CANONICAL_LF_SHA256 = {
    'PortraitFullscreenOverlay.kt': '8dd7fb38cedd8b1cdf0accc796c8077c22012f18c1b47988cde6b716f51c4353',
    'PortraitInteractionBar.kt': '1a155295c162521a3d860f341a12cbc1f94ca08dd0d1be25d1dfd8a0f2562263',
    'PortraitBottomInputBar.kt': '8e52f5641a61ce40c874a0610170fa57782293f4ff25d908bf7330b7d32ee6a7',
    'PortraitProgressBar.kt': 'fc1d5baefa3033d00be84ced887d801f9148dd19934a2dbf527ed08611c51b7f',
    'PortraitVideoLoadPolicy.kt': 'e80e604e6393d0e44764b49e813fff004fb581293f8d71eeb8bdeaf18c9a9fc3',
    'PortraitPagerSwitchPolicy.kt': '6805c3dfcd1f853d5251c47001cbb5a16adfa070e46971468dd84e3c7c0c59c0',
}
PREVIOUS_OUTPUT_SHA256 = {
    'PortraitFullscreenOverlay.kt': '93d4ca25709f4acaa88eabda992f6db73b003c769e1fe060d7786d3893094593',
    'PortraitInteractionBar.kt': '9c1e2b436b47ab7bd80630483d0a8ffaede3a2fa0c3d587d5e84965ee7c30b0f',
    'PortraitBottomInputBar.kt': '190ff2772f250667aaf3561bb4cffc6812c6faf63033039ced9740e439c75089',
    'PortraitProgressBar.kt': 'd070f8b9069c814d68b4449fae83053a664d0a264844f5326370028c8094f402',
    'PortraitVideoLoadPolicy.kt': '6e2e4bf11aff2d40996541f02d7e3426706f16837033d5d2c1ac180ca66dada4',
    'PortraitPagerSwitchPolicy.kt': '18c847742327ae9d56ce62975955574712b1ec39a938ba00edd9ca35841afb8c',
}
# LF sha256 of what the producer's own counted edits make of the v0.4.6 bytes (the SPECS row outputSHA).
CURRENT_OUTPUT_SHA256 = {
    'PortraitFullscreenOverlay.kt': '79fac2dbbebf8ff2c08d7a6ac4bf308bc41d279606ac2e0408027a7ea4d03175',
    'PortraitInteractionBar.kt': '6dc31077ec3e65f1a7caf8e1a4752d859e441885f7b54c4f866ed6e810254e45',
    'PortraitBottomInputBar.kt': '47155fa10465169afef2d6f8be9ddcfd8cecc39632e0e03bc1580ae4a895c610',
    'PortraitProgressBar.kt': '6209cf4f97f5ab73ea6ec0d6b2630a1a6023eaaf5cae041fbbb424c8bb183c7d',
    'PortraitVideoLoadPolicy.kt': '00c3783d49cf2665746d4f32e05f849e3e68ec6dee213ef3c4ca267538da9d05',
    'PortraitPagerSwitchPolicy.kt': '584fcd49e776814793dd50df564176344fcbbe696df97fe5e7c0273d26c7e3bb',
}
PAGER_PREDECESSOR_SHA256 = 'fdf7a9ee692b22929385f9e58bd3de30436bff6417fec776da7ab3298fcdc5b9'
PAGER_EMITTED_SHA256 = '2ae6625946b5c2e738eb79226dc3c080201c173d6418bc5d2d0355b11923f2ec'

# Tokens that must occur exactly once in a leaf: catches an archive that is internally consistent but not the file.
DECLARATIONS = {
    'PortraitFullscreenOverlay.kt': (
        'fun PortraitFullscreenOverlay(\n    title: String,\n    declarations: List<String> = emptyList(),\n',
        '    isLiked: Boolean,\n    likeStatusKnown: Boolean,\n', '    isFollowing: Boolean = false,\n    followStatusKnown: Boolean,\n',
        '    playbackReady: Boolean = true,\n',
        'private fun PortraitGlassIconButton(', 'rememberFollowActionWithConfirmation(\n                                targetKey = ownerMid,',
        'import androidx.compose.ui.platform.LocalConfiguration\n',
    ),
    'PortraitInteractionBar.kt': (
        'fun PortraitInteractionBar(', '    likeStatusKnown: Boolean,\n', 'countAnimationRequest = countRequests.like,',
        'import androidx.compose.ui.platform.LocalConfiguration\n',
    ),
    'PortraitBottomInputBar.kt': (
        'fun PortraitBottomInputBar(', 'PiliPlusDanmakuSendIcon(', 'import androidx.compose.ui.platform.LocalConfiguration\n',
    ),
    'PortraitProgressBar.kt': (
        'fun PortraitBottomContainer(', '.playerControlGlassProgressDock()', 'PlayerControlGlassThumb(',
        'import androidx.compose.ui.platform.LocalConfiguration\n',
    ),
    'PortraitVideoLoadPolicy.kt': (
        'internal fun resolvePortraitPrefetchWindowTargets(', 'it.bvid == identity.bvid && it.cid == identity.cid',
        'internal fun buildPortraitCachedMediaSourceFactory(context: Context)',
    ),
    'PortraitPagerSwitchPolicy.kt': (
        'internal fun shouldSkipPortraitReloadForCurrentMedia(', 'internal fun shouldShowPortraitCover(',
        'VideoRepository.isVerticalVideo(', 'import com.android.purebilibili.data.repository.VideoRepository\n',
    ),
    'PortraitControlsVisibilityPolicy.kt': (
        'internal const val PORTRAIT_CONTROLS_AUTO_HIDE_DELAY_MS = 4_000L', 'internal fun shouldAutoHidePortraitControls(',
    ),
}


def block(text):
    """A literal that starts on the line after the opening quotes; keeps every following byte."""
    assert text.startswith('\n')
    return text[1:]


# The one adaptation of the switch policy: the v0.4.6 cover rule needs the first-frame bookkeeping of the v0.4.6 pager.
COVER_HUNK = dict(
    label='shouldShowPortraitCover keeps the v0.2.5 rule: the current page never shows the static cover',
    before=block('''
    if (!isCurrentPage) return true
    // 首帧出现后不因缓冲重新盖回封面，避免播放中画面跳变。
    if (isLoading && !hasRenderedFirstFrame) return true
    return !isPlayerReadyForThisVideo || !hasRenderedFirstFrame
'''),
    after=block('''
    if (!isCurrentPage) return true
    // 当前播放页一律不显示静态封面，保持黑屏底色平滑过渡起播，避免进入时闪烁封面
    return false
'''),
)

_DECLARATIONS_UPSTREAM = block('''
    val showVideoDeclarations by remember(context) {
        com.android.purebilibili.core.store.SettingsManager.getVideoArgueMsgShown(context)
    }.collectAsStateWithLifecycle(initialValue = true)
    val videoDeclarations = remember(portraitDetailInfo, showVideoDeclarations) {
        if (!showVideoDeclarations) emptyList() else buildList {
            portraitDetailInfo?.argueInfo?.argueMsg?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            if (portraitDetailInfo?.rights?.noReprint == 1) add("未经作者授权，请勿转载")
        }.distinct()
    }
''')
_SETTINGS_UPSTREAM = '        com.android.purebilibili.core.store.SettingsManager.getVideoArgueMsgShown(context)\n'
_SETTINGS_WINDOWS = (
    '        com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings'
    '.getVideoArgueMsgShown(context.pluginContext)\n'
)
_DECLARATIONS_WINDOWS = _DECLARATIONS_UPSTREAM.replace(_SETTINGS_UPSTREAM, _SETTINGS_WINDOWS)
assert _DECLARATIONS_WINDOWS != _DECLARATIONS_UPSTREAM

_DETAIL_INFO_TAIL = block('''
        fallbackInfo = fallbackDetailInfo,
    )
''')
_SEED_COMMENT = '    // seed 进场可能没有 owner；加载成功后用 Success.info 补齐 UP 名/头像。\n'

_AUTO_HIDE = block('''
    var controlsInteractionGeneration by remember(bvid) { mutableLongStateOf(0L) }
    var controlsPointerPressed by remember(bvid) { mutableStateOf(false) }
    var controlsLifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { owner, _ ->
            controlsLifecycleResumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val danmakuInputVisible by viewModel.showDanmakuInputDialog.collectAsStateWithLifecycle()
    val favoriteFoldersVisible by viewModel.favoriteFolderDialogVisible.collectAsStateWithLifecycle()
    val controlsOverlayOpen = showCommentSheet || subReplyState.visible || showDetailSheet ||
        showUpPreview || commentSheetVisibilityProgress > 0.001f || upPreviewVisibilityProgress > 0.001f ||
        showSpeedMenu || showAudioQualityMenu || showQualityMenu || showRatioMenu || showSubtitlePanel ||
        danmakuInputVisible || favoriteFoldersVisible
    val autoHidePortraitControls = shouldAutoHidePortraitControls(
        isCurrentPage = isCurrentPage,
        playbackActive = controlsLifecycleResumed && isPortraitPlaybackAllowed &&
            isPlayerReadyForThisVideo && isPlaying && !isLoading,
        controlsVisible = portraitOverlayVisible,
        userInteracting = controlsPointerPressed || isLongPressing || seekSession.isSliderMoving,
        overlayOpen = controlsOverlayOpen,
    )
    val latestOnPortraitOverlayVisibleChange by rememberUpdatedState(onPortraitOverlayVisibleChange)
    LaunchedEffect(bvid, isCurrentPage) {
        if (isCurrentPage) latestOnPortraitOverlayVisibleChange(true)
    }
    // User activity and blocking overlays restart the countdown; playback-position
    // polling does not. Hidden controls stay hidden until the existing tap gesture.
    LaunchedEffect(bvid, autoHidePortraitControls, controlsInteractionGeneration) {
        if (!autoHidePortraitControls) return@LaunchedEffect
        delay(PORTRAIT_CONTROLS_AUTO_HIDE_DELAY_MS)
        // A down event can arrive before recomposition cancels this effect.
        if (!controlsPointerPressed && !isLongPressing && !seekSession.isSliderMoving) {
            latestOnPortraitOverlayVisibleChange(false)
        }
    }

''')
_LONG_PRESS_ANCHOR = '    val latestIsLongPressing by rememberUpdatedState(isLongPressing)\n'

_POINTER_OBSERVER = block('''
            .pointerInput(isCurrentPage, bvid) {
                if (!isCurrentPage) return@pointerInput
                // Observe controls and video gestures without taking their input.
                // Initial-pass observation includes child buttons, progress scrubs,
                // and menus whose own handlers consume the pointer in Main.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    controlsPointerPressed = true
                    controlsInteractionGeneration++
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        controlsPointerPressed = false
                        controlsInteractionGeneration++
                    }
                }
            }
''')
_GESTURE_ANCHOR = '            .pointerInput(isCurrentPage, bvid, playerGesturesEnabled) {\n'

# Counted hunks of the generated PortraitVideoPager.kt. `upstream` is the text that must be a verbatim slice of the
# v0.4.6 blob (PAGER_UPSTREAM_BLOB); `after` is that text in its Windows form.
PAGER_HUNKS = (
    dict(
        label='import PointerEventPass for the controls activity observer',
        before='import androidx.compose.ui.input.pointer.pointerInput\n',
        after='import androidx.compose.ui.input.pointer.PointerEventPass\nimport androidx.compose.ui.input.pointer.pointerInput\n',
        upstream='import androidx.compose.ui.input.pointer.PointerEventPass\nimport androidx.compose.ui.input.pointer.pointerInput\n',
    ),
    dict(
        label='interaction state carries whether the like state is known',
        before='    val isLiked: Boolean,\n    val isFavorited: Boolean,\n    val likeCount: Int,\n',
        after='    val isLiked: Boolean,\n    val likeStatusKnown: Boolean,\n    val isFavorited: Boolean,\n    val likeCount: Int,\n',
        upstream='    val isLiked: Boolean,\n    val likeStatusKnown: Boolean,\n    val isFavorited: Boolean,\n    val likeCount: Int,\n',
    ),
    dict(
        label='a matching shared state forwards its known flag',
        before='            isLiked = currentSharedState.isLiked,\n            isFavorited = currentSharedState.isFavorited,\n',
        after=('            isLiked = currentSharedState.isLiked,\n            likeStatusKnown = currentSharedState.likeStatusKnown,\n'
               '            isFavorited = currentSharedState.isFavorited,\n'),
        upstream=('            isLiked = currentSharedState.isLiked,\n            likeStatusKnown = currentSharedState.likeStatusKnown,\n'
                  '            isFavorited = currentSharedState.isFavorited,\n'),
    ),
    dict(
        label='an unmatched video keeps its like state unknown',
        before='            isLiked = false,\n            isFavorited = false,\n            likeCount = fallbackStat.like,\n',
        after=('            isLiked = false,\n            likeStatusKnown = false,\n            isFavorited = false,\n'
               '            likeCount = fallbackStat.like,\n'),
        upstream=('            isLiked = false,\n            likeStatusKnown = false,\n            isFavorited = false,\n'
                  '            likeCount = fallbackStat.like,\n'),
    ),
    dict(
        label='a local like override makes the like state known',
        before='        isLiked = localOverride.isLiked ?: base.isLiked,\n        isFavorited = localOverride.isFavorited ?: base.isFavorited,\n',
        after=('        isLiked = localOverride.isLiked ?: base.isLiked,\n'
               '        likeStatusKnown = localOverride.isLiked != null || base.likeStatusKnown,\n'
               '        isFavorited = localOverride.isFavorited ?: base.isFavorited,\n'),
        upstream=('        isLiked = localOverride.isLiked ?: base.isLiked,\n'
                  '        likeStatusKnown = localOverride.isLiked != null || base.likeStatusKnown,\n'
                  '        isFavorited = localOverride.isFavorited ?: base.isFavorited,\n'),
    ),
    dict(
        label='a triple action never asserts a like over an unknown state',
        before='        isLiked = nextLiked,\n        isFavorited = nextFavorited,\n',
        after='        isLiked = nextLiked.takeIf { currentState.likeStatusKnown || likeSuccess },\n        isFavorited = nextFavorited,\n',
        upstream='        isLiked = nextLiked.takeIf { currentState.likeStatusKnown || likeSuccess },\n        isFavorited = nextFavorited,\n',
    ),
    dict(
        label='creation declarations of the current video (settings read through the metadata store)',
        before=_DETAIL_INFO_TAIL + _SEED_COMMENT,
        after=_DETAIL_INFO_TAIL + _DECLARATIONS_WINDOWS + _SEED_COMMENT,
        upstream=_DECLARATIONS_UPSTREAM,
        adaptation=dict(before=_SETTINGS_UPSTREAM, after=_SETTINGS_WINDOWS),
    ),
    dict(
        label='overlay call: playback readiness and declarations',
        before='    PortraitFullscreenOverlay(\n            title = title,\n',
        after=('    PortraitFullscreenOverlay(\n            playbackReady = isCurrentPage && !isLoading && isPlayerReadyForThisVideo,\n'
               '            title = title,\n            declarations = videoDeclarations,\n'),
        upstream=('    PortraitFullscreenOverlay(\n            playbackReady = isCurrentPage && !isLoading && isPlayerReadyForThisVideo,\n'
                  '            title = title,\n            declarations = videoDeclarations,\n'),
    ),
    dict(
        label='overlay call: the like state is known',
        before='            isLiked = resolvedInteractionState.isLiked,\n            isCoined = isCurrentModelVideo',
        after=('            isLiked = resolvedInteractionState.isLiked,\n            likeStatusKnown = resolvedInteractionState.likeStatusKnown,\n'
               '            isCoined = isCurrentModelVideo'),
        upstream=('            isLiked = resolvedInteractionState.isLiked,\n            likeStatusKnown = resolvedInteractionState.likeStatusKnown,\n'
                  '            isCoined = isCurrentModelVideo'),
    ),
    dict(
        label='overlay call: the follow state is known, and a follow click needs an admitted subject',
        before=('            isFollowing = isFollowing,\n            onFollowClick = { \n'
                '                onToggleFollow(authorMid, isFollowing)\n            },\n'),
        after=('            isFollowing = isFollowing,\n            followStatusKnown = followStatusKnown,\n            onFollowClick = {\n'
               '                if (canHandlePortraitInteraction && authorMid > 0L) onToggleFollow(authorMid, isFollowing)\n            },\n'),
        upstream=('            isFollowing = isFollowing,\n            followStatusKnown = followStatusKnown,\n            onFollowClick = {\n'
                  '                if (canHandlePortraitInteraction && authorMid > 0L) onToggleFollow(authorMid, isFollowing)\n            },\n'),
    ),
    dict(
        label='overlay call: the triple action needs a known like state',
        before='            onLikeLongClick = {\n                if (canHandlePortraitInteraction) {\n',
        after='            onLikeLongClick = {\n                if (canHandlePortraitInteraction && resolvedInteractionState.likeStatusKnown) {\n',
        upstream='            onLikeLongClick = {\n                if (canHandlePortraitInteraction && resolvedInteractionState.likeStatusKnown) {\n',
    ),
    dict(
        label='controls hide themselves after four idle seconds of playback',
        before=_LONG_PRESS_ANCHOR,
        after=_AUTO_HIDE + _LONG_PRESS_ANCHOR,
        upstream=_AUTO_HIDE,
    ),
    dict(
        label='initial-pass observer restarts the idle countdown on any pointer activity',
        before='                portraitPageHeightPx = size.height\n            }\n' + _GESTURE_ANCHOR,
        after='                portraitPageHeightPx = size.height\n            }\n' + _POINTER_OBSERVER + _GESTURE_ANCHOR,
        upstream=_POINTER_OBSERVER,
    ),
)


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def wide(path):
    """Extended-length form on Windows, so a deep Gradle output path cannot reach MAX_PATH; unchanged elsewhere."""
    path = Path(path).absolute()
    if os.name != 'nt':
        return path
    text = str(path)
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(text if text.startswith(prefix) else prefix + text)


def fixed(repo):
    """Verified archive text by leaf name and the manifest. Raises when anything differs from its pins."""
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v046 portrait pager archive')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'}, 'v046 portrait pager leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in raw and sha(raw) == MANIFEST_SHA256, 'v046 portrait pager manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'v046 portrait pager source identity changed')
    need([row['filename'] for row in manifest['files']] == list(ARCHIVE_FILES), 'v046 portrait pager source set changed')
    predecessors = {row['replacedBy']: (row['canonicalLfSha256'], row.get('previousSpecOutputSha256') or row['previousAdaptedSha256'])
                    for row in manifest['producerPredecessors']}
    need(predecessors == {name: (CANONICAL_LF_SHA256[name], PREVIOUS_OUTPUT_SHA256[name]) for name in CANONICAL_LF_SHA256},
         'v046 portrait pager producer predecessor pins changed')
    hunks = manifest['pagerHunks']
    need((hunks['path'], hunks['upstreamBlob'], hunks['predecessorSha256'], hunks['hunks']) ==
         (PAGER_UPSTREAM_PATH, PAGER_UPSTREAM_BLOB, PAGER_PREDECESSOR_SHA256, len(PAGER_HUNKS)),
         'v046 portrait pager hunk pins changed')
    sources = {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous v046 portrait pager source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'v046 portrait pager upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'v046 portrait pager bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'v046 portrait pager Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.endswith('\n') and ('package ' + PACKAGES[file.name] + '\n') in text[:200],
             'v046 portrait pager text changed: ' + file.name)
        for token in DECLARATIONS.get(file.name, ()):
            need(text.count(token) == 1, 'v046 portrait pager declaration changed: ' + token.strip())
        sources[file.name] = text
    licence = root / 'LICENSE'
    need(licence.is_file() and not licence.is_symlink() and sha(licence.read_bytes()) == manifest['license']['sha256'],
         'v046 portrait pager license changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    """Call first in every producer that hooks this slice: archive intact and no output inside or around it."""
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps v046 portrait pager inputs')


def _save(path, value):
    target = wide(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def spec_source(repo, relative, canonical, expected):
    """Pager producer, per SPECS row: (text to edit, outputSHA to assert). Unchanged for rows this slice does not own."""
    leaf = SPEC_LEAVES.get(relative)
    if leaf is None:
        return canonical, expected
    need(sha(canonical) == CANONICAL_LF_SHA256[leaf], 'Portrait pager canonical predecessor changed: ' + leaf)
    need(expected == PREVIOUS_OUTPUT_SHA256[leaf], 'Portrait pager SPECS predecessor row changed: ' + leaf)
    return fixed(repo)[0][leaf], CURRENT_OUTPUT_SHA256[leaf]


def record_spec(repo, relative, final, output):
    """Pager producer, after every layer: proves the emitted overlay or policy inverts to the v0.4.6 bytes."""
    leaf = SPEC_LEAVES.get(relative)
    if leaf is None:
        return
    sources, _manifest = fixed(repo)
    upstream = sources[leaf]
    proof = dict(
        upstreamCommit=COMMIT, archive=ARCHIVE, sourceManifestSHA256=MANIFEST_SHA256, leaf=leaf,
        upstreamPath=UPSTREAM_PATHS[leaf], replacedCanonicalLfSha256=CANONICAL_LF_SHA256[leaf],
        previousSpecOutputSha256=PREVIOUS_OUTPUT_SHA256[leaf], upstreamSHA256=sha(upstream), emittedSHA256=sha(final),
        originalToWindows=inverse_proof(upstream, final), mainCompiled=False, applicationExecuted=False)
    _save(Path(output) / PROOF_DIRECTORY / (leaf + '.json'), proof)


def switch_policy(repo, canonical, adapt, output):
    """Story topic producer: (text recorded as original, emitted body) of PortraitPagerSwitchPolicy.kt.

    `adapt` is the producer's own platform substitution (the two isVerticalVideo edits); it runs on the v0.4.6 bytes.
    """
    need(sha(canonical) == CANONICAL_LF_SHA256[SWITCH_LEAF], 'Portrait switch policy canonical predecessor changed')
    upstream = fixed(repo)[0][SWITCH_LEAF]
    adapted = adapt(upstream)
    need(adapted.count(COVER_HUNK['before']) == 1, 'Portrait switch policy cover rule changed')
    final = adapted.replace(COVER_HUNK['before'], COVER_HUNK['after'], 1)
    need(sha(final) == CURRENT_OUTPUT_SHA256[SWITCH_LEAF], 'Portrait switch policy emitted bytes changed')
    _save(Path(output) / PROOF_DIRECTORY / (SWITCH_LEAF + '.json'), dict(
        upstreamCommit=COMMIT, archive=ARCHIVE, sourceManifestSHA256=MANIFEST_SHA256, leaf=SWITCH_LEAF,
        upstreamPath=UPSTREAM_PATHS[SWITCH_LEAF], replacedCanonicalLfSha256=CANONICAL_LF_SHA256[SWITCH_LEAF],
        previousAdaptedSha256=PREVIOUS_OUTPUT_SHA256[SWITCH_LEAF], upstreamSHA256=sha(upstream),
        platformAdapted=sha(adapted), coverHunk=dict(label=COVER_HUNK['label'], before=COVER_HUNK['before'],
                                                      after=COVER_HUNK['after'], count=1),
        emittedSHA256=sha(final), originalToWindows=inverse_proof(upstream, final),
        mainCompiled=False, applicationExecuted=False))
    return upstream, final


def emit_controls_policy(repo, output):
    """Pager producer: the whole new v0.4.6 policy; returns its inventory row."""
    body = fixed(repo)[0][CONTROLS_LEAF]
    target = wide(Path(output) / CONTROLS_OUTPUT)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(body.encode('utf8'))
    _save(Path(output) / PROOF_DIRECTORY / (CONTROLS_LEAF + '.json'), dict(
        upstreamCommit=COMMIT, archive=ARCHIVE, sourceManifestSHA256=MANIFEST_SHA256, leaf=CONTROLS_LEAF,
        upstreamPath=UPSTREAM_PATHS[CONTROLS_LEAF], wholeOriginalEmittedByteForByte=True, emittedSHA256=sha(body),
        mainCompiled=False, applicationExecuted=False))
    return dict(path=CONTROLS_OUTPUT, origin=UPSTREAM_PATHS[CONTROLS_LEAF], mode=CONTROLS_MODE, sha256LF=sha(body),
                generated=True)


def advance_pager(repo, relative, text, output):
    """Pager producer, last layer: thirteen counted hunks on the generated PortraitVideoPager.kt."""
    if relative != PAGER:
        return text
    fixed(repo)
    need(sha(text) == PAGER_PREDECESSOR_SHA256, 'Portrait pager predecessor changed')
    result = text
    applied = []
    for hunk in PAGER_HUNKS:
        need(hunk['before'] != hunk['after'], 'Portrait pager hunk is empty: ' + hunk['label'])
        need(result.count(hunk['before']) == 1, 'Portrait pager anchor changed: ' + hunk['label'])
        at = result.index(hunk['before'])
        applied.append(dict(label=hunk['label'], offset=at, before=hunk['before'], after=hunk['after'], count=1))
        result = result[:at] + hunk['after'] + result[at + len(hunk['before']):]
    need(sha(result) == PAGER_EMITTED_SHA256, 'Portrait pager emitted bytes changed')
    restored = result
    for hunk in reversed(applied):
        at = hunk['offset']
        need(restored[at:at + len(hunk['after'])] == hunk['after'], 'Portrait pager inverse boundary changed')
        restored = restored[:at] + hunk['before'] + restored[at + len(hunk['after']):]
    need(restored == text, 'Portrait pager input was not preserved')
    _save(Path(output) / PROOF_DIRECTORY / 'PortraitVideoPager.kt.json', dict(
        upstreamCommit=COMMIT, archive=ARCHIVE, sourceManifestSHA256=MANIFEST_SHA256, upstreamPath=PAGER_UPSTREAM_PATH,
        upstreamBlob=PAGER_UPSTREAM_BLOB, inputSHA256=sha(text), outputSHA256=sha(result), hunks=applied,
        fullInputInverseRecovered=True, mainCompiled=False, applicationExecuted=False))
    return result


def peel_pager(text, proof):
    """Removes this layer from a generated pager using its recorded proof (older tests unwind layers newest first)."""
    need(sha(text) == proof['outputSHA256'], 'Portrait pager proof does not describe this text')
    result = text
    for hunk in reversed(proof['hunks']):
        at = hunk['offset']
        need(result[at:at + len(hunk['after'])] == hunk['after'], 'Portrait pager inverse boundary changed')
        result = result[:at] + hunk['before'] + result[at + len(hunk['after']):]
    need(sha(result) == proof['inputSHA256'], 'Portrait pager peel did not recover the input')
    return result
