"""Slice V2 source checks: the player layout flag read, the hoisted LEGACY chrome and the controls binding. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_leaf_layout_binding.py

test_ux_leaf_anchor_guard.py pins the anchors of the leaf. This suite pins what slice V2 itself did to it:

  LeafIdentityTest      the ten hunks of slice V2, reverse-applied to the leaf, give the base leaf (commit dee21a6c5) byte for byte, so
                        the LEGACY layout differs from the old one only in the places the hunks name; the diff is 35 added and 16
                        removed lines; each of the ten routed callbacks is the original lambda after a mechanical substitution
  LeafStructureTest     the flag read, the three hoisted lambdas and their invocations (strip and bar only while LEGACY is the
                        effective layout, the panel in both layouts), the construction of the binding, the routes to the bar
  BindingSourceTest     DesktopPlayerControlsBinding.kt agrees with the model line by line, the model agrees with an independent
                        oracle on every combination of page, gate and muted flag, and the file is pure
  SelectionSourceTest   DesktopPlayerLayoutSelection.kt agrees with the model and the contract; the settings row of the flag
                        follows the canonical capability, now enabled by the mounted DESKTOP consumer
  CarrierSourceTest     the real native foreground carrier, hit regions, subtitle reserve and shared layout selection
  CarrierMutationTest   every carrier check rejects its own mutation; the consumer hunks restore the exact pre-consumer leaf
  JvmTestsTest          the two JVM test classes exist and use no window
  MutationTest          each V2 check fails on an edited copy of the real leaf

A later slice that edits the leaf (V3 to V7) prepends its own hunk tuple to the chain in reverse_apply() in the same commit, so the
chain always ends at the V2 hunks and then at the base. It never loosens a check.

Slice V6 (the window-level shortcut router) is the first of them: V6_HUNKS below holds its nine hunks, reverse_apply() undoes them first
and then the ten of V2, so the base leaf (commit dee21a6c5) still comes out byte for byte. The V2 checks that read the leaf as it is now
stay as they were, with two exact updates that V6 caused: legacyChrome is read four times (it also feeds the shortcut binding), and the
recorded diff size of V2 is measured between the V2 state (V6 undone) and the base. test_ux_player_keys_source.py pins what V6 did.

TON-1592 prepends ux_player_consumer_hunks.json before those historical chains. The new stage and native carrier are checked on
the current leaf; historical anchors are checked after undoing only this consumer layer. Pre-existing V6 baseline failures remain
visible, rather than broadening an old identity assertion to accept unrelated changes.
"""
import hashlib
import json
import itertools
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_source as source  # noqa: E402
import ux_leaf_model as model  # noqa: E402
from test_ux_leaf_anchor_guard import call_arguments, argument_names, check_legacy_branch, check_must_keep, BAR_ARGUMENTS  # noqa: E402

BASE_COMMIT = "dee21a6c523948c9ba64e83810c4f6b97bccc02c"  # the branch head before slice V2; the hash below is of its leaf blob
BASE_SHA256 = "cb44523d1c71e0931e6601c4408627ed6975aabb9a8d6ae3e5360e1505b8b978"
BASE_BYTES = 123287
ADDED_LINES, REMOVED_LINES = 35, 16

# The ten hunks of slice V2 in leaf order: (label, lines before, lines after). The first and the last line of each hunk are unchanged
# context, so each "after" block is anchored and occurs exactly once in the leaf.
HUNKS = (
    ("imports", (
        "import com.bilipai.desktop.player.PlayerPreferences",
        "import kotlinx.coroutines.CancellationException",
    ), (
        "import com.bilipai.desktop.player.PlayerPreferences",
        "import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout",
        "import com.bilipai.desktop.appearance.LocalDesktopWindowsUx",
        "import kotlinx.coroutines.CancellationException",
    )),
    ("flag read and controls binding", (
        "    }",
        "    BoxWithConstraints(Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 8.dp)) {",
    ), (
        "    }",
        "    // V2 (spec 4.13 and 4.15): the player_layout flag is read here. DESKTOP has no overlay yet, so it resolves to LEGACY and the",
        "    // three pieces below render exactly as before. The controls binding holds the admitted actions of the bar and the strip.",
        "    val windowsUxSettings by LocalDesktopWindowsUx.current.settings.collectAsState()",
        "    val legacyChrome = desktopPlayerEffectiveLayout(windowsUxSettings.playerLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE) ==",
        "        DesktopWindowsUxPlayerLayout.LEGACY",
        "    val controls = DesktopPlayerControlsBinding(",
        "        current = ::current, command = ::command,",
        "        togglePause = { native.togglePause() }, setMuted = { native.setMuted(it) }, setVolume = { native.setVolume(it) },",
        "        isMuted = { state.muted }, preferences = preferences, preferencesChanged = preferencesChanged,",
        "        navigate = ::navigateFromThisClick,",
        "        switchQuality = { shell.playback.switchQuality(it) }, seekToSeconds = { shell.playback.seekTo(it) },",
        "        goBack = { actions.back() }, enterPictureInPicture = { actions.pictureInPicture() },",
        "        toggleFullscreen = { actions.fullscreen() }, speed = ::setSpeed,",
        "    )",
        "    BoxWithConstraints(Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 8.dp)) {",
    )),
    ("top strip lambda opens", (
        "            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (chromeVisible) 6.dp else 0.dp)) {",
        "                if (chromeVisible) DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()",
        "                    .desktopWindowsChromePointerInput(::observeChromePointer)",
    ), (
        "            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (chromeVisible) 6.dp else 0.dp)) {",
        "                val legacyTopStrip: @Composable () -> Unit = { DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()",
        "                    .desktopWindowsChromePointerInput(::observeChromePointer)",
    )),
    ("top strip back button routed", (
        "                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {",
        "                        IconButton(onClick = { if (current()) actions.back() }, modifier = Modifier.size(44.dp)) {",
        "                            Icon(Icons.Default.ArrowBack, contentDescription = \"\u8fd4\u56de\")",
    ), (
        "                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {",
        "                        IconButton(onClick = controls.back, modifier = Modifier.size(44.dp)) {",
        "                            Icon(Icons.Default.ArrowBack, contentDescription = \"\u8fd4\u56de\")",
    )),
    ("top strip lambda closes and is called", (
        "                    }",
        "                }",
        "                // One retained native slot; original fixed ratios letterbox the same viewport.",
    ), (
        "                    }",
        "                } }",
        "                if (chromeVisible && legacyChrome) legacyTopStrip()",
        "                // One retained native slot; original fixed ratios letterbox the same viewport.",
    )),
    ("control bar lambda opens", (
        "                if (bootstrapError != null || playback.error != null) failureContent()",
        "                if (chromeVisible) DesktopWindowsVideoControlBar(",
        "                    state = state, sourceVersion = native.currentSourceSnapshot()?.sourceVersion ?: 0L,",
    ), (
        "                if (bootstrapError != null || playback.error != null) failureContent()",
        "                val legacyControlBar: @Composable () -> Unit = { DesktopWindowsVideoControlBar(",
        "                    state = state, sourceVersion = native.currentSourceSnapshot()?.sourceVersion ?: 0L,",
    )),
    ("control bar callbacks routed", (
        "                    chapters = chapters, chaptersSource = chaptersSource, onChapterSeek = ::seekChapter,",
        "                    onPlayPause = { command { native.togglePause() } },",
        "                    onPrevious = { navigateFromThisClick(false) }, onNext = { navigateFromThisClick(true) },",
        "                    onMute = { if(command { native.setMuted(!state.muted) }) preferencesChanged(preferences.copy(muted=!state.muted)) },",
        "                    onVolume = { value -> if(command { native.setVolume(value) }) preferencesChanged(preferences.copy(volume=value)) },",
        "                    onSpeed = ::setSpeed,",
        "                    onQuality = { quality -> if (current()) shell.playback.switchQuality(quality) },",
        "                    onSeek = { seconds -> if (current()) shell.playback.seekTo(seconds) },",
        "                    onPictureInPicture = { if (current()) actions.pictureInPicture() },",
        "                    onFullscreen = { if (current()) actions.fullscreen() },",
        "                    onDetails = {",
    ), (
        "                    chapters = chapters, chaptersSource = chaptersSource, onChapterSeek = ::seekChapter,",
        "                    onPlayPause = controls.playPause,",
        "                    onPrevious = controls.previous, onNext = controls.next,",
        "                    onMute = controls.mute,",
        "                    onVolume = controls.volume,",
        "                    onSpeed = controls.speed,",
        "                    onQuality = controls.quality,",
        "                    onSeek = controls.seek,",
        "                    onPictureInPicture = controls.pictureInPicture,",
        "                    onFullscreen = controls.fullscreen,",
        "                    onDetails = {",
    )),
    ("control bar lambda closes and is called", (
        "                    enhancement = actions.enhancement,",
        "                )",
        "            }",
    ), (
        "                    enhancement = actions.enhancement,",
        "                ) }",
        "                if (chromeVisible && legacyChrome) legacyControlBar()",
        "            }",
    )),
    ("details panel lambda opens", (
        "            }",
        "            if (detailsOpen) DesktopWindowsVideoDetailsPanel(",
        "                modifier = Modifier.width(detailsWidth).fillMaxHeight(), selectedTab = detailsTab,",
    ), (
        "            }",
        "            val detailsPanel: @Composable () -> Unit = { DesktopWindowsVideoDetailsPanel(",
        "                modifier = Modifier.width(detailsWidth).fillMaxHeight(), selectedTab = detailsTab,",
    )),
    ("details panel lambda closes and is called", (
        "                },",
        "            )",
        "        }",
    ), (
        "                },",
        "            ) }",
        "            if (detailsOpen) detailsPanel()",
        "        }",
    )),
)


# The nine hunks of slice V6 in leaf order, in the same form as HUNKS above: the first and the last line of each hunk are unchanged
# context and each "after" block occurs exactly once in the leaf. Generated from the diff against the V2 state, then reviewed.
V6_HUNKS = (
    ("chapter normalizer import", (
        "import com.android.purebilibili.feature.video.ui.overlay.NextWatchOverlay",
        "import kotlinx.coroutines.ensureActive",
    ), (
        "import com.android.purebilibili.feature.video.ui.overlay.NextWatchOverlay",
        "import com.android.purebilibili.feature.video.ui.overlay.normalizeViewPointSegments",
        "import kotlinx.coroutines.ensureActive",
    )),
    ("shortcut binding holder", (
        "    val windowKeyFallback = LocalDesktopWindowKeyFallback.current",
        "    val latestKeyInputFocus by rememberUpdatedState(",
    ), (
        "    val windowKeyFallback = LocalDesktopWindowKeyFallback.current",
        "    // V6 (spec 6.5 and 6.6): the layout, the flyout flag and the effects of the shortcut router arrive here from a SideEffect below,",
        "    // once the page state they read exists. The router itself is built with the key input, because that is where it is registered.",
        "    val shortcutBinding = remember(assembly) { DesktopWindowsVideoShortcutBinding() }",
        "    val latestKeyInputFocus by rememberUpdatedState(",
    )),
    ("shared keyboard gate", (
        "        route, active, presentationAlive, pipActive) {",
        "        val input = DesktopWindowsVideoKeyInput(",
        "            currentSource = { latestChromeSource?.takeIf { chromeCurrent() } },",
        "            ownsFocus = { latestKeyInputFocus && native.surface.isShowing && chromeWindow != null &&",
        "                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === chromeWindow },",
        "            isFullscreen = { latestFullscreen },",
    ), (
        "        route, active, presentationAlive, pipActive) {",
        "        // V6 (spec 6.6): the LEGACY layout keeps its gate (focus on the strip, the bar, the viewport or the native surface); the DESKTOP",
        "        // layout has none of those controls, so the binding decides by layout. Both keyboard inputs below share this one rule.",
        "        val keyboardOwned = { shortcutBinding.ownsFocus(latestKeyInputFocus, desktopWindowsTextEditorFocused()) &&",
        "            native.surface.isShowing && chromeWindow != null &&",
        "            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === chromeWindow }",
        "        val input = DesktopWindowsVideoKeyInput(",
        "            currentSource = { latestChromeSource?.takeIf { chromeCurrent() } },",
        "            ownsFocus = keyboardOwned,",
        "            isFullscreen = { latestFullscreen },",
    )),
    ("window-level shortcut router", (
        "        )",
        "        // Clicking a Compose control retains its focus. Offer Space/Escape before",
    ), (
        "        )",
        "        // V6 (spec 6.5): the window-level player shortcuts. Every effect goes through the admitted lambdas bound by the SideEffect below.",
        "        val shortcuts = DesktopWindowsVideoShortcutInput(",
        "            currentSource = { latestChromeSource?.takeIf { chromeCurrent() } },",
        "            ownsFocus = keyboardOwned,",
        "            controlHoldsFocus = { topFocused || barInteraction.focused },",
        "            textEditorFocused = ::desktopWindowsTextEditorFocused,",
        "            isFullscreen = { latestFullscreen },",
        "            isPaused = { native.state.value.paused },",
        "            layout = { shortcutBinding.layout },",
        "            perform = { action ->",
        "                shortcutBinding.perform(action).also { handled ->",
        "                    if (handled) chromeActivity(DesktopWindowsFullscreenChromeActivity.NATIVE_KEY)",
        "                }",
        "            },",
        "        )",
        "        // Clicking a Compose control retains its focus. Offer Space/Escape before",
    )),
    ("composite registration", (
        "        val keyRegistration = if (active && presentationAlive && !pipActive)",
        "            windowKeyFallback?.registerLeaf(input::onKey) else null",
        "        val listener = object : java.awt.event.WindowFocusListener {",
    ), (
        "        val keyRegistration = if (active && presentationAlive && !pipActive)",
        "            windowKeyFallback?.registerLeaf { event -> input.onKey(event) || shortcuts.onKey(event) } else null",
        "        val listener = object : java.awt.event.WindowFocusListener {",
    )),
    ("focus loss releases the router", (
        "                input.releaseHeldInput()",
        "                chromeWindowFocused = false",
    ), (
        "                input.releaseHeldInput()",
        "                shortcuts.releaseHeldInput()",
        "                chromeWindowFocused = false",
    )),
    ("dispose closes the router", (
        "            input.close()",
        "        }",
    ), (
        "            input.close()",
        "            shortcuts.close()",
        "        }",
    )),
    ("page-entry focus", (
        "                        }.focusable()) {",
        "                        if (active && !pipActive) {",
    ), (
        "                        }.focusable()) {",
        "                        // V6 (spec 6.6): on page entry the viewport takes the keyboard focus unless a text editor owns it.",
        "                        LaunchedEffect(assembly, route, active, presentationAlive, pipActive) {",
        "                            if (active && presentationAlive && !pipActive && current())",
        "                                desktopWindowsRequestPageEntryFocus(desktopWindowsTextEditorFocused()) { viewportFocus.requestFocus() }",
        "                        }",
        "                        if (active && !pipActive) {",
    )),
    ("effects bound by a SideEffect", (
        "",
        "    audioLanguageMenu?.takeIf { shell.playback.isAudioSelectionCurrent(it) }?.let { selection ->",
    ), (
        "",
        "    // V6 (spec 6.5 and 6.6): bind the shortcut router to this composition. Each effect is an admitted lambda of the leaf: the controls binding,",
        "    // current(), shell.playback.* and actions.*; the router reaches neither the player nor the assembly.",
        "    SideEffect {",
        "        shortcutBinding.legacyLayout = legacyChrome",
        "        shortcutBinding.flyoutOpen = showCollection || showPlaybackQueue || audioLanguageMenu != null || audioTrackMenu != null ||",
        "            interactionMode != null",
        "        shortcutBinding.effects = DesktopPlayerShortcutEffects(",
        "            controls = controls,",
        "            snapshot = { native.state.value.let { DesktopPlayerShortcutSnapshot(it.positionSeconds, it.durationSeconds, it.volume) } },",
        "            seekBy = { seconds -> if (current()) shell.playback.seekBy(seconds) },",
        "            hasPart = { forward -> if (forward) shell.playback.hasNext else shell.playback.hasPrevious },",
        "            canMiniPlayer = { !pipActive && success != null && state.videoCodec != null && !state.audioOnly },",
        "            togglePanel = { if (current()) detailsOpen = !detailsOpen },",
        "            toggleSubtitles = {",
        "                val target = if (subtitleMode != SubtitleDisplayMode.OFF) SubtitleDisplayMode.OFF",
        "                else subtitleDisplayOptions.firstOrNull { it.enabled && it.mode != SubtitleDisplayMode.OFF }?.mode",
        "                if (current() && target != null) { subtitleOverride = target; true } else false",
        "            },",
        "            toggleDanmaku = { if (current()) actions.toggleDanmaku() },",
        "            takeScreenshot = ::takeScreenshot,",
        "            chapterStartsMs = {",
        "                val result = chapters",
        "                if (result == null) emptyList()",
        "                else normalizeViewPointSegments(result.points, (native.state.value.durationSeconds * 1000.0).toLong()).map { it.fromMs }",
        "            },",
        "            seekToChapter = { positionMs ->",
        "                val result = chapters",
        "                val accepted = chaptersSource",
        "                if (result != null && accepted != null) seekChapter(result, accepted, positionMs)",
        "            },",
        "            speedChoices = { resolveDesktopWindowsVideoSpeedOptions(speedOptions, state.speed.toFloat()).map { it.toDouble() } },",
        "            currentSpeed = { state.speed },",
        "        )",
        "    }",
        "",
        "    audioLanguageMenu?.takeIf { shell.playback.isAudioSelectionCurrent(it) }?.let { selection ->",
    )),
)


def reverse_chain(leaf, chain):
    """The leaf with every hunk of the chain undone. Each undone block must be present exactly once when its turn comes."""
    text = leaf
    for label, before, after in reversed(chain):
        block = "\n".join(after)
        if text.count(block) != 1:
            raise AssertionError("hunk %r: its block occurs %d times, expected once" % (label, text.count(block)))
        text = text.replace(block, "\n".join(before), 1)
    return text


# TON-1028 (core/plugin and plugin-sdk compiled from upstream): SkipAction moved from core.plugin to plugin-sdk; the leaf only
# follows the package. Undone before V6, so every V6 and V2 pin still holds.
TON1028_HUNKS = (
    ("SkipAction from plugin-sdk", (
        "                                state.skipToMs <= 0L || state.skipToMs != segment.endTimeMs) return@let null",
        "                            com.android.purebilibili.core.plugin.SkipAction.ShowButton(",
        "                                state.skipToMs, state.label.orEmpty(), segmentId)",
    ), (
        "                                state.skipToMs <= 0L || state.skipToMs != segment.endTimeMs) return@let null",
        "                            com.android.purebilibili.plugin.sdk.SkipAction.ShowButton(",
        "                                state.skipToMs, state.label.orEmpty(), segmentId)",
    )),
)


CONSUMER_RECORD = json.loads(Path(__file__).with_name('ux_player_consumer_hunks.json').read_text())
CONSUMER_HUNKS = tuple((h['label'], h['before'].split('\n'), h['after'].split('\n'))
                       for h in CONSUMER_RECORD['hunks'])


def reverse_consumer(leaf):
    """Undo only TON-1592 before checking the historical V2/V6 identity chain."""
    return reverse_chain(leaf, CONSUMER_HUNKS)


def reverse_v6(leaf):
    """The V2 state of the leaf: the hunks of the later slices (TON-1028, then V6) undone."""
    return reverse_chain(reverse_chain(reverse_consumer(leaf), TON1028_HUNKS), V6_HUNKS)


def reverse_apply(leaf):
    """The base leaf: the newest chains first (TON-1028, V6), then the chain of V2."""
    return reverse_chain(reverse_v6(leaf), HUNKS)


def sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


LEAF_TEXT = model.read(model.LEAF_KT)

# The original lambdas of the base leaf (as they stood in the bar call and in the strip) and the substitutions that turn each into
# the member of the binding: the capability replaces the direct call, and nothing else changes.
ORIGINALS = {
    "back": ("{ if (current()) actions.back() }", (("actions.back()", "goBack()"),)),
    "playPause": ("{ command { native.togglePause() } }", (("native.togglePause()", "togglePause()"),)),
    "previous": ("{ navigateFromThisClick(false) }", (("navigateFromThisClick(", "navigate("),)),
    "next": ("{ navigateFromThisClick(true) }", (("navigateFromThisClick(", "navigate("),)),
    "mute": ("{ if(command { native.setMuted(!state.muted) }) preferencesChanged(preferences.copy(muted=!state.muted)) }",
             (("if(command", "if (command"), ("native.setMuted(", "setMuted("), ("state.muted", "isMuted()"), ("copy(muted=", "copy(muted = "))),
    "volume": ("{ value -> if(command { native.setVolume(value) }) preferencesChanged(preferences.copy(volume=value)) }",
               (("if(command", "if (command"), ("native.setVolume(", "setVolume("), ("copy(volume=", "copy(volume = "))),
    "quality": ("{ quality -> if (current()) shell.playback.switchQuality(quality) }",
                (("{ quality ->", "{ id ->"), ("shell.playback.switchQuality(quality)", "switchQuality(id)"))),
    "seek": ("{ seconds -> if (current()) shell.playback.seekTo(seconds) }", (("shell.playback.seekTo(", "seekToSeconds("),)),
    "pictureInPicture": ("{ if (current()) actions.pictureInPicture() }", (("actions.pictureInPicture()", "enterPictureInPicture()"),)),
    "fullscreen": ("{ if (current()) actions.fullscreen() }", (("actions.fullscreen()", "toggleFullscreen()"),)),
}

# What the leaf passes for each constructor parameter of the binding, in order.
CONSTRUCTION = (
    ("current", "::current"), ("command", "::command"),
    ("togglePause", "{ native.togglePause() }"), ("setMuted", "{ native.setMuted(it) }"), ("setVolume", "{ native.setVolume(it) }"),
    ("isMuted", "{ state.muted }"), ("preferences", "preferences"), ("preferencesChanged", "preferencesChanged"),
    ("navigate", "::navigateFromThisClick"),
    ("switchQuality", "{ shell.playback.switchQuality(it) }"), ("seekToSeconds", "{ shell.playback.seekTo(it) }"),
    ("goBack", "{ actions.back() }"), ("enterPictureInPicture", "{ actions.pictureInPicture() }"),
    ("toggleFullscreen", "{ actions.fullscreen() }"), ("speed", "::setSpeed"),
)

STRIP_OPEN = "header = { DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()"
STRIP_CALL = "if (chromeVisible && legacy) header()"
BAR_OPEN = "controls = { Box(Modifier.onSizeChanged { overlayControlsHeightPx = it.height }) { DesktopWindowsVideoControlBar("
BAR_CALL = "if (chromeVisible && legacy) controls()"
PANEL_OPEN = "panel = { panelModifier -> DesktopWindowsVideoDetailsPanel("
PANEL_CALL = "if (dock.mounted) plan.panel(Modifier.width(dock.widthDp.dp).fillMaxHeight())"
FLAG_READ = "val settings by LocalDesktopWindowsUx.current.settings.collectAsState()"
EFFECTIVE = "layout = desktopPlayerEffectiveLayout(settings.playerLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE)"
IMPORTS = ("import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout", "import com.bilipai.desktop.appearance.LocalDesktopWindowsUx")


def lambda_block(leaf, opener):
    """The text of the lambda that opens with opener: from its opening brace to its closing brace."""
    code = source.mask_kotlin(leaf)
    start = leaf.index(opener)
    begin = leaf.index("= {", start) + 2
    depth, end = 0, begin
    while True:
        char = code[end]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return leaf[begin:end + 1]
        end += 1


def check_v2_structure(leaf):
    """V2's binding survives in the retained plan; both layouts share one viewport."""
    problems = []

    def need(condition, message):
        if not condition:
            problems.append(message)

    for line in IMPORTS:
        need(leaf.count(line + "\n") == 1, "import: " + line)
    need(leaf.count(FLAG_READ) == 1, "the flag read")
    need(leaf.count(EFFECTIVE) == 1, "the effective layout")
    need(not carrier_problems(leaf)['layout-source'], "both effective-layout calculations use the same store and capability")
    for opener, call in ((STRIP_OPEN, STRIP_CALL), (BAR_OPEN, BAR_CALL), (PANEL_OPEN, PANEL_CALL)):
        need(leaf.count(opener) == 1, "lambda defined once: " + opener)
        need(leaf.count(call) == 1, "lambda called once: " + call)
    order = ["val plan = resources.content(panelState)", "DesktopWindowsVideoDetailsDockRow(", EFFECTIVE, PANEL_CALL,
             "plan.afterLayout()", "fun RowScope.DesktopWindowsVideoPlayerStage(", STRIP_CALL, "viewport {", "status()", BAR_CALL,
             "val controls = DesktopPlayerControlsBinding(", STRIP_OPEN, "viewport = { desktopChrome ->", BAR_OPEN, PANEL_OPEN]
    if all(item in leaf for item in order):
        indices = [leaf.index(item) for item in order]
        need(indices == sorted(indices), "composition order of the flag, the binding, the strip, the video, the bar and the panel")
    else:
        problems.append("an order anchor is missing")
    need(leaf.count('viewport {') == 1, 'one retained viewport invocation')
    need(leaf.count('if (chromeVisible && !legacy) {') == 1, 'DESKTOP chrome has its own branch')
    for opener in (STRIP_OPEN, BAR_OPEN, PANEL_OPEN):
        if leaf.count(opener) != 1:
            continue
        body = source.mask_kotlin(lambda_block(leaf, opener))
        need(not re.search(r"\breturn\b(?!@)", body), "a bare return inside " + opener)
        labels = set(re.findall(r"return@(\w+)", body))
        need(all(re.search(r"(\b%s\s*[({]|\.%s\s*\{)" % (label, label), body) for label in labels), "a return label that leaves its lambda in " + opener)
        scoped = re.findall(r"\.(weight|align|alignBy|alignByBaseline|matchParentSize|animateItem)\(", body)
        need(scoped == (["weight"] if opener == STRIP_OPEN else []), "scope-bound modifiers inside %s: %s" % (opener, scoped))
    return problems


def braced_block(text, opener):
    """Balanced Kotlin scope, with braces inside strings/comments masked."""
    code = source.mask_kotlin(text)
    start = text.index(opener)
    begin = code.index('{', start)
    depth = 0
    for end in range(begin, len(code)):
        depth += (code[end] == '{') - (code[end] == '}')
        if depth == 0:
            return text[begin:end + 1]
    raise AssertionError('unclosed scope: ' + opener)


def carrier_problems(leaf):
    """Production carrier invariants absent from the fake native resources in the scene test."""
    result = {key: [] for key in ('placement', 'hit-regions', 'subtitles', 'layout-source')}

    def need(key, condition, message):
        if not condition:
            result[key].append(message)

    try:
        viewport = braced_block(leaf, 'viewport = { desktopChrome ->')
        native = braced_block(viewport, 'DesktopVideoCommandPopup(viewportSize, {')
        content = braced_block(native, 'Box(Modifier.fillMaxSize()) {')
        active = braced_block(viewport, 'if (active && !pipActive) {')
        after_native = viewport[viewport.index(active) + len(active):]
        pip = braced_block(after_native, 'else {')
        need('placement', viewport.count('DesktopVideoCommandPopup(') == 1 and
             '}, native.surface)' in active, 'one native foreground carrier')
        need('placement', native.count('desktopChrome()') == content.count('desktopChrome()') == 1,
             'chrome belongs once to the popup content BoxScope')
        need('placement', content.index('actions.overlay()') < content.index('desktopChrome()') <
             content.index('VideoSubtitleOverlayHost('), 'chrome follows overlays and precedes subtitles')
        need('placement', pip.count('desktopChrome()') == 1 and viewport.count('desktopChrome()') == 2,
             'one additional invocation in the PiP fallback, none elsewhere')
    except (ValueError, AssertionError) as error:
        result['placement'].append(str(error))

    try:
        stage = braced_block(leaf, 'fun RowScope.DesktopWindowsVideoPlayerStage(')
        desktop = braced_block(stage, 'if (chromeVisible && !legacy) {')
        for edge, control in (('Top', 'header'), ('Bottom', 'controls')):
            # The region and its control must belong to the same Box, on the intended edge.
            box = braced_block(desktop, 'Box(Modifier.align(Alignment.' + edge + 'Center)')
            modifier = desktop[desktop.index('Box(Modifier.align(Alignment.' + edge + 'Center)'):desktop.index(box)]
            need('hit-regions', '.desktopCommandHitRegion("desktop-player-' + control + '")' in modifier and
                 model.squash(box) == '{ ' + control + '() }' and
                 leaf.count('.desktopCommandHitRegion("desktop-player-' + control + '")') == 1,
                 'one owned region for ' + control)
    except (ValueError, AssertionError) as error:
        result['hit-regions'].append(str(error))

    squashed = model.squash(source.mask_kotlin(leaf))
    reserve = ('val reservedBottomPx = if (!legacyChrome && chromeVisible) '
               'overlayControlsHeightPx + with(density) { 24.dp.roundToPx() } else 0')
    need('subtitles', reserve in squashed and squashed.count('val reservedBottomPx =') == 1,
         'reserve measured controls plus padding only for visible DESKTOP chrome')
    need('subtitles', 'Box(Modifier.fillMaxSize().padding(bottom = with(density) { reservedBottomPx.toDp() }))' in squashed,
         'subtitle host uses the reserved padding')
    need('subtitles', 'playerViewportSize = IntSize(viewportSize.width, (viewportSize.height - reservedBottomPx).coerceAtLeast(0))' in squashed,
         'subtitle drag viewport subtracts the identical reserve')
    need('subtitles', squashed.count('reservedBottomPx') == 3 and
         'controls = { Box(Modifier.onSizeChanged { overlayControlsHeightPx = it.height })' in squashed,
         'one measured controls height and exactly the two reserve consumers')

    for variable in ('settings', 'windowsUxSettings'):
        need('layout-source', leaf.count('val ' + variable + ' by LocalDesktopWindowsUx.current.settings.collectAsState()') == 1,
             variable + ' reads the same local store')
        call = 'desktopPlayerEffectiveLayout(' + variable + '.playerLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE)'
        need('layout-source', leaf.count(call) == 1, variable + ' uses the canonical selection rule and capability')
    need('layout-source', squashed.count('desktopPlayerEffectiveLayout(') == 2 and
         squashed.count('DESKTOP_PLAYER_OVERLAY_AVAILABLE') == 2 and
         len(re.findall(r'\.playerLayout\b', squashed)) == 2, 'no third or diverging layout calculation')
    need('layout-source', 'val legacyChrome = desktopPlayerEffectiveLayout(windowsUxSettings.playerLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE) == DesktopWindowsUxPlayerLayout.LEGACY' in squashed,
         'carrier subtitle/shortcut layout agrees with the stage')
    return result


def check_routes(leaf):
    """The ten callbacks of the bar and the back button of the strip take the binding's members, each member once."""
    problems = []
    arguments = dict(call_arguments(leaf, "DesktopWindowsVideoControlBar("))
    for argument, member in model.BAR_ROUTES.items():
        if arguments.get(argument) != "controls." + member:
            problems.append("bar argument %s must be controls.%s, is %r" % (argument, member, arguments.get(argument)))
    if "IconButton(onClick = controls.back, modifier = Modifier.size(44.dp))" not in leaf:
        problems.append("the strip back button must take controls.back")
    for member in model.PUBLIC_MEMBERS:
        used = len(re.findall(r"\bcontrols\.%s\b" % member, leaf))
        if used != 1:
            problems.append("controls.%s is used %d times, expected once" % (member, used))
    if len(re.findall(r"\bcontrols\.", leaf)) != len(model.PUBLIC_MEMBERS):
        problems.append("controls is used by something other than the 11 routes")
    return problems


def check_construction(leaf):
    """The one construction of the binding names every constructor parameter once, in order, with the pinned capability."""
    problems = []
    if leaf.count("DesktopPlayerControlsBinding(") != 1:
        return ["DesktopPlayerControlsBinding( is called exactly once"]
    arguments = call_arguments(leaf, "DesktopPlayerControlsBinding(")
    if [name for name, _ in arguments] != [name for name, _ in CONSTRUCTION]:
        problems.append("the construction names %s" % [name for name, _ in arguments])
    for (name, value), (expected, wanted) in zip(arguments, CONSTRUCTION):
        if value != wanted:
            problems.append("%s must be %r, is %r" % (name, wanted, value))
    construction = leaf.index("val controls = DesktopPlayerControlsBinding(")
    for declaration in ("fun current(): Boolean", "fun command(block: () -> Unit): Boolean", "fun navigateFromThisClick(forward: Boolean)",
                        "fun setSpeed(speed: Double)"):
        if leaf.count(declaration) != 1:
            problems.append("%s must be declared once in the leaf" % declaration)
        elif leaf.index(declaration) > construction:
            problems.append("%s must be declared before the binding is built" % declaration)
    return problems


class LeafIdentityTest(unittest.TestCase):
    def test_the_hunks_are_anchored_and_each_block_occurs_once(self):
        self.assertEqual(10, len(HUNKS))
        text = reverse_consumer(LEAF_TEXT)
        for label, before, after in HUNKS:
            self.assertEqual(1, text.count("\n".join(after)), label)
            self.assertEqual(before[0], after[0], label + ": the first line is unchanged context")
            self.assertEqual(before[-1], after[-1], label + ": the last line is unchanged context")

    def test_reversing_the_v2_hunks_gives_the_base_leaf_byte_for_byte(self):
        base = reverse_apply(LEAF_TEXT)
        self.assertEqual(BASE_BYTES, len(base.encode("utf-8")))
        self.assertEqual(BASE_SHA256, sha256(base), "the LEGACY layout changed somewhere outside the recorded hunks")

    def test_the_diff_against_the_base_is_small_and_exactly_the_recorded_size(self):
        import difflib
        base = reverse_apply(LEAF_TEXT).split("\n")
        now = reverse_v6(LEAF_TEXT).split("\n")  # the V2 state: slice V6 has its own size check in test_ux_player_keys_source.py
        added = removed = 0
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, base, now, autojunk=False).get_opcodes():
            if tag != "equal":
                removed += i2 - i1
                added += j2 - j1
        self.assertEqual((ADDED_LINES, REMOVED_LINES), (added, removed))
        self.assertLessEqual(added + removed, 60)

    def test_the_hunk_labels_cover_the_three_hoists_and_nothing_else(self):
        self.assertEqual(["imports", "flag read and controls binding", "top strip lambda opens", "top strip back button routed",
                          "top strip lambda closes and is called", "control bar lambda opens", "control bar callbacks routed",
                          "control bar lambda closes and is called", "details panel lambda opens",
                          "details panel lambda closes and is called"], [label for label, _, _ in HUNKS])

    def test_each_routed_callback_is_the_original_lambda_after_a_mechanical_substitution(self):
        base = reverse_apply(LEAF_TEXT)
        binding = model.binding_members(model.read(model.BINDING_KT))
        self.assertEqual(set(model.MEMBERS), set(ORIGINALS))
        for name, (original, substitutions) in ORIGINALS.items():
            self.assertEqual(1, base.count(original), name + ": the original lambda stood once in the base leaf")
            moved = original
            for old, new in substitutions:
                self.assertIn(old, moved, name + ": " + old)
                moved = moved.replace(old, new)
            self.assertEqual(model.squash(moved), model.squash(binding[name][1]), name)

    def test_the_originals_that_reached_the_bar_stood_in_the_bar_call_of_the_base(self):
        base = reverse_apply(LEAF_TEXT)
        arguments = dict(call_arguments(base, "DesktopWindowsVideoControlBar("))
        self.assertEqual("{ command { native.togglePause() } }", arguments["onPlayPause"])
        self.assertEqual("{ navigateFromThisClick(false) }", arguments["onPrevious"])
        self.assertEqual("{ navigateFromThisClick(true) }", arguments["onNext"])
        self.assertEqual("::setSpeed", arguments["onSpeed"])
        for argument, name in (("onMute", "mute"), ("onVolume", "volume"), ("onQuality", "quality"), ("onSeek", "seek"),
                               ("onPictureInPicture", "pictureInPicture"), ("onFullscreen", "fullscreen")):
            self.assertEqual(model.squash(ORIGINALS[name][0]), model.squash(arguments[argument]), argument)
        self.assertIn("IconButton(onClick = { if (current()) actions.back() }, modifier = Modifier.size(44.dp))", base)


class LeafStructureTest(unittest.TestCase):
    def test_the_v2_shape(self):
        self.assertEqual([], check_v2_structure(LEAF_TEXT))

    def test_the_bar_and_the_strip_take_the_binding(self):
        self.assertEqual([], check_routes(LEAF_TEXT))

    def test_the_binding_is_built_once_from_the_pinned_capabilities(self):
        self.assertEqual([], check_construction(LEAF_TEXT))

    def test_the_construction_matches_the_constructor_of_the_binding(self):
        constructor = model.binding_constructor(model.read(model.BINDING_KT))
        self.assertEqual([(name, kind, public) for name, kind, public in model.CONSTRUCTOR], constructor)
        self.assertEqual([name for name, _, _ in model.CONSTRUCTOR], [name for name, _ in CONSTRUCTION])

    def test_the_legacy_branch_guard_still_passes_with_the_hoists(self):
        self.assertEqual([], check_legacy_branch(LEAF_TEXT))
        self.assertEqual([], check_must_keep(LEAF_TEXT))
        self.assertEqual(BAR_ARGUMENTS, argument_names(LEAF_TEXT, "DesktopWindowsVideoControlBar("))

    def test_desktop_and_legacy_share_the_retained_stage(self):
        code = source.mask_kotlin(LEAF_TEXT)
        self.assertNotIn("UxPlayerLayout.DESKTOP", code)
        self.assertEqual(2, code.count("DesktopWindowsUxPlayerLayout.LEGACY"))
        self.assertEqual(1, code.count('if (chromeVisible && !legacy) {'))
        self.assertEqual(1, code.count('viewport {'))
        self.assertEqual(1, code.count(STRIP_CALL))
        self.assertEqual(1, code.count(BAR_CALL))
        self.assertEqual(1, code.count(PANEL_CALL))
        for needle in ("DesktopPlayerOverlay(", "DesktopPlayerInputLayer(", "DesktopPlayerInputProbe", "DesktopWindowsCarrierScaleInput",
                       "DesktopFlyout"):
            self.assertNotIn(needle, code, needle)

    def test_the_capabilities_that_the_leaf_passes_exist_with_the_types_the_binding_expects(self):
        player = model.read(source.K / "player/MpvPlayer.kt")
        for declaration in ("    fun togglePause() {", "    fun setVolume(volume: Double) {", "    fun setMuted(muted: Boolean) ="):
            self.assertEqual(1, player.count(declaration), declaration)
        facade = model.read(source.K / "ui/DesktopUnifiedPlaybackFacade.kt")
        self.assertEqual(1, len(re.findall(r"fun switchQuality\(", facade)))
        self.assertEqual(1, facade.count("fun switchQuality(quality: Int)"))
        self.assertEqual(1, len(re.findall(r"fun seekTo\(", facade)))
        self.assertEqual(1, facade.count("fun seekTo(seconds: Double)"))
        for declaration in ("val back: () -> Unit,", "val fullscreen: () -> Unit,", "val pictureInPicture: () -> Unit,"):
            self.assertEqual(1, LEAF_TEXT.count(declaration), declaration)
        preferences = model.read(source.K / "player/PlayerPreferences.kt")
        for declaration in ("data class PlayerPreferences(", "    val volume: Double = 75.0,", "    val muted: Boolean = false,"):
            self.assertEqual(1, preferences.count(declaration), declaration)

    def test_the_bar_parameters_have_the_types_of_the_members_that_feed_them(self):
        bar = model.squash(model.read(source.K / "ui/DesktopWindowsVideoControlBar.kt"))
        types = {"onPlayPause": "() -> Unit", "onPrevious": "() -> Unit", "onNext": "() -> Unit", "onMute": "() -> Unit",
                 "onVolume": "(Double) -> Unit", "onSpeed": "(Double) -> Unit", "onQuality": "(Int) -> Unit", "onSeek": "(Double) -> Unit",
                 "onPictureInPicture": "() -> Unit", "onFullscreen": "() -> Unit"}
        member_types = {name: kind for name, (kind, _) in model.MEMBERS.items()}
        member_types["speed"] = {name: kind for name, kind, _ in model.CONSTRUCTOR}["speed"]
        for argument, member in model.BAR_ROUTES.items():
            self.assertIn("%s: %s" % (argument, types[argument]), bar, argument)
            self.assertEqual(types[argument], member_types[member], argument + " takes " + member)

    def test_the_bar_composable_is_not_edited_by_this_slice(self):
        # V2b restyles it; the signature, the names and the call stay (spec 4.13).
        self.assertEqual(1, model.read(source.K / "ui/DesktopWindowsVideoControlBar.kt").count("internal fun DesktopWindowsVideoControlBar("))


class BindingSourceTest(unittest.TestCase):
    TEXT = model.read(model.BINDING_KT)

    def test_the_constructor_and_the_members_agree_with_the_model(self):
        self.assertEqual(list(model.CONSTRUCTOR), model.binding_constructor(self.TEXT))
        members = model.binding_members(self.TEXT)
        self.assertEqual({name: (kind, rhs) for name, (kind, rhs) in model.MEMBERS.items()}, members)
        self.assertEqual(10, len(members))
        self.assertEqual(11, len(model.PUBLIC_MEMBERS))

    def test_the_model_agrees_with_the_independent_oracle_on_every_combination(self):
        cases = 0
        for is_current, accepts, muted in itertools.product((True, False), repeat=3):
            for action in model.MEMBERS:
                value = {"volume": 55.0, "quality": 80, "seek": 12.5}.get(action)
                calls = model.Calls(is_current=is_current, accepts=accepts, muted=muted)
                binding = calls.binding()
                (binding[action](value) if value is not None else binding[action]())
                events, saved = model.expected_events(action, is_current, accepts, muted, value)
                self.assertEqual(events, calls.events, (action, is_current, accepts, muted))
                self.assertEqual([dict(calls.preferences, **change) for change in saved], calls.saved, (action, is_current, accepts, muted))
                self.assertEqual([], calls.speed_calls)
                cases += 1
        self.assertEqual(80, cases)

    def test_speed_is_handed_out_as_it_is(self):
        calls = model.Calls(is_current=False, accepts=False)
        binding = calls.binding()
        binding["speed"](2.0)
        self.assertEqual([2.0], calls.speed_calls)
        self.assertEqual([], calls.events)

    def test_a_click_reads_the_muted_flag_of_that_moment(self):
        calls = model.Calls(muted=False)
        binding = calls.binding()
        calls.muted = True
        binding["mute"]()
        self.assertEqual(["setMuted(false)"], calls.events)
        self.assertEqual([dict(calls.preferences, muted=False)], calls.saved)

    def test_a_preference_is_written_only_after_its_command_ran(self):
        for action, argument in (("mute", None), ("volume", 30.0)):
            calls = model.Calls(accepts=False)
            binding = calls.binding()
            (binding[action](argument) if argument is not None else binding[action]())
            self.assertEqual(([], []), (calls.events, calls.saved), action)

    def test_the_file_is_pure_and_declares_one_class(self):
        self.assertTrue(self.TEXT.startswith("package com.bilipai.desktop.ui\n"))
        self.assertEqual(["import com.bilipai.desktop.player.PlayerPreferences"], re.findall(r"(?m)^import .*$", self.TEXT))
        code = source.mask_kotlin(self.TEXT)
        for banned in ("androidx", "java.awt", "javax.swing", "KeyEvent", "Toolkit", "Composable", "remember(", "mutableStateOf", "KeyFallback",
                       "MpvPlayer", "SwingPanel", "LocalDensity", "native.", "assembly.", "System.currentTimeMillis"):
            self.assertNotIn(banned, code, banned)
        for pattern in (r"\bthrow\b", r"\berror\(", r"\brequire\(", r"\bcheck\(", r"!!", r"\bTODO\(", r"\bassert\("):
            self.assertIsNone(re.search(pattern, code), pattern)
        self.assertEqual(["DesktopPlayerControlsBinding"], re.findall(r"(?m)^(?:internal |private |public )?(?:class|object|fun|val|var|interface) (\w+)", code))
        self.assertEqual([], source.cjk_positions(self.TEXT))
        self.assertTrue(self.TEXT.isascii())

    def test_the_hygiene_rules_of_the_program_cover_the_file_and_it_passes_them(self):
        self.assertEqual([], source.banned_imports(self.TEXT))
        self.assertEqual([], source.hook_uses(self.TEXT))
        self.assertEqual([], source.key_fallback_uses(self.TEXT))
        for glob in ("ui/DesktopPlayer*.kt",):
            self.assertIn(glob, source.BANNED_IMPORT_GLOBS)
            self.assertIn(glob, source.STRICT_TEXT_GLOBS)
        self.assertIn(model.BINDING_KT, source.kotlin_files(source.PROGRAM_GLOBS))


class SelectionSourceTest(unittest.TestCase):
    TEXT = model.read(model.SELECTION_KT)
    CONTRACT = model.load_contract()

    def test_the_flag_in_the_contract_is_legacy_by_default_with_the_two_values(self):
        flag = model.player_layout_flag(self.CONTRACT)
        self.assertEqual(["LEGACY", "DESKTOP"], flag["values"])
        self.assertEqual("LEGACY", flag["default"])
        self.assertEqual("DesktopWindowsUxPlayerLayout", flag["enum"])
        model_text = model.read(source.K / "appearance/DesktopWindowsUxModel.kt")
        self.assertIn("internal enum class DesktopWindowsUxPlayerLayout { LEGACY, DESKTOP }", model_text)
        self.assertIn("val playerLayout: DesktopWindowsUxPlayerLayout = DesktopWindowsUxPlayerLayout.LEGACY,", model_text)

    def test_the_model_and_the_truth_table_agree_on_every_value_of_the_contract(self):
        values = model.player_layout_flag(self.CONTRACT)["values"]
        for requested, available in itertools.product(values, (False, True)):
            self.assertEqual(model.selection_oracle(requested, available), model.effective_layout(requested, available), (requested, available))
        for requested in values:
            self.assertEqual("LEGACY", model.effective_layout(requested, False), requested)
        self.assertEqual("DESKTOP", model.effective_layout("DESKTOP", True))
        self.assertEqual("LEGACY", model.effective_layout("LEGACY", True))

    def test_the_kotlin_function_is_the_model(self):
        squashed = model.squash(self.TEXT)
        self.assertIn("internal const val DESKTOP_PLAYER_OVERLAY_AVAILABLE = true", squashed)
        self.assertIn("internal fun desktopPlayerEffectiveLayout( requested: DesktopWindowsUxPlayerLayout, overlayAvailable: Boolean, ): "
                      "DesktopWindowsUxPlayerLayout = if (requested == DesktopWindowsUxPlayerLayout.DESKTOP && overlayAvailable) "
                      "DesktopWindowsUxPlayerLayout.DESKTOP else DesktopWindowsUxPlayerLayout.LEGACY", squashed)

    def test_the_file_is_pure(self):
        self.assertTrue(self.TEXT.startswith("package com.bilipai.desktop.ui\n"))
        self.assertEqual(["import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout"], re.findall(r"(?m)^import .*$", self.TEXT))
        code = source.mask_kotlin(self.TEXT)
        for banned in ("androidx", "java.awt", "javax.swing", "Composable", "remember(", "KeyFallback", "MpvPlayer", "LocalDesktopWindowsUx"):
            self.assertNotIn(banned, code, banned)
        self.assertEqual(["DESKTOP_PLAYER_OVERLAY_AVAILABLE", "desktopPlayerEffectiveLayout"],
                         re.findall(r"(?m)^(?:internal |private |public )?(?:const val|fun|val|class|object) (\w+)", code))
        self.assertEqual([], source.cjk_positions(self.TEXT))
        self.assertTrue(self.TEXT.isascii())

    def test_the_settings_row_of_the_flag_is_on_exactly_when_an_overlay_exists(self):
        selection = re.search(r"\ninternal const val DESKTOP_PLAYER_OVERLAY_AVAILABLE = (true|false)\n", self.TEXT).group(1)
        settings = model.read(model.SETTINGS_KT)
        self.assertIn('private const val PLAYER_LAYOUT_HAS_CONSUMER = DESKTOP_PLAYER_OVERLAY_AVAILABLE\n', settings)
        self.assertEqual("true", selection)

    def test_the_leaf_is_the_only_reader_of_the_flag_outside_the_store_and_the_settings_group(self):
        readers = []
        for path in sorted(source.K.rglob("*.kt")):
            relative = source.rel(path)
            if relative.startswith("appearance/DesktopWindowsUx") or path == model.SETTINGS_KT:
                continue
            if re.search(r"\.playerLayout\b", source.mask_kotlin(model.read(path))):
                readers.append(relative)
        self.assertEqual(["ui/DesktopWindowsVideoPhysicalLeaf.kt"], readers)


class NamesTest(unittest.TestCase):
    def test_the_new_top_level_names_are_declared_once_in_desktop_src(self):
        names = ("DesktopPlayerControlsBinding", "DESKTOP_PLAYER_OVERLAY_AVAILABLE", "desktopPlayerEffectiveLayout")
        declaration = re.compile(r"(?m)^(?:internal |private |public )?(?:const )?(?:class|object|interface|fun|val|var|typealias)\s+(%s)\b" % "|".join(names))
        found = []
        for path in sorted((source.REPO / "desktop/src").rglob("*.kt")):
            for match in declaration.finditer(source.mask_kotlin(model.read(path))):
                found.append((match.group(1), path.name))
        self.assertEqual(sorted([("DESKTOP_PLAYER_OVERLAY_AVAILABLE", "DesktopPlayerLayoutSelection.kt"),
                                 ("DesktopPlayerControlsBinding", "DesktopPlayerControlsBinding.kt"),
                                 ("desktopPlayerEffectiveLayout", "DesktopPlayerLayoutSelection.kt")]), sorted(found))

    def test_the_leaf_adds_no_top_level_declaration(self):
        base = reverse_apply(LEAF_TEXT)
        pattern = re.compile(r"(?m)^(?:internal |private |public )?(?:data |enum |sealed )?(?:class|object|interface|fun|val|var|typealias)\s+(\w+)")
        self.assertEqual(pattern.findall(source.mask_kotlin(base)), pattern.findall(source.mask_kotlin(LEAF_TEXT)))


class JvmTestsTest(unittest.TestCase):
    def test_the_two_test_classes_exist_with_their_test_counts(self):
        for path, count in ((model.JVM_BINDING_TEST, 7), (model.JVM_SELECTION_TEST, 4)):
            text = model.read(path)
            self.assertEqual(count, len(re.findall(r"(?m)^    @Test fun ", text)), path.name)
            self.assertTrue(text.startswith("package com.bilipai.desktop.ui\n"))
            self.assertEqual(1, len(re.findall(r"(?m)^class \w+Test \{", text)))
        self.assertEqual(("com.bilipai.desktop.ui.DesktopPlayerControlsBindingTest", "com.bilipai.desktop.ui.DesktopPlayerLayoutSelectionTest"),
                         model.JVM_TEST_CLASSES)

    def test_they_need_no_window_no_gpu_and_no_network(self):
        for path in (model.JVM_BINDING_TEST, model.JVM_SELECTION_TEST):
            code = source.mask_kotlin(model.read(path))
            for banned in ("java.awt", "javax.swing", "ImageComposeScene", "androidx.compose", "java.net", "Thread.sleep", "System.getenv"):
                self.assertNotIn(banned, code, "%s uses %s" % (path.name, banned))

    def test_the_selection_test_reads_the_byte_identical_contract_copy(self):
        text = model.read(model.JVM_SELECTION_TEST)
        self.assertIn('DesktopUxContract.section("flags")', text)
        self.assertEqual((source.REPO / "desktop/src/test/resources/desktop-ux-contract.json").read_bytes(), model.CONTRACT_PATH.read_bytes())

    def test_the_binding_test_rig_uses_the_constructor_the_main_file_declares(self):
        text = model.squash(model.read(model.JVM_BINDING_TEST))
        names = [name for name, _, _ in model.CONSTRUCTOR]
        start = text.index("DesktopPlayerControlsBinding(")
        positions = [text.index(name + " = ", start) for name in names]
        self.assertEqual(sorted(positions), positions, "the rig names the parameters in the declared order")


class CarrierSourceTest(unittest.TestCase):
    def test_chrome_is_inside_the_native_popup_and_pip_fallback(self):
        self.assertEqual([], carrier_problems(LEAF_TEXT)['placement'])

    def test_header_and_controls_own_their_hit_regions(self):
        self.assertEqual([], carrier_problems(LEAF_TEXT)['hit-regions'])

    def test_subtitle_padding_and_drag_viewport_use_the_same_reserve(self):
        self.assertEqual([], carrier_problems(LEAF_TEXT)['subtitles'])

    def test_resource_and_stage_effective_layouts_have_one_source(self):
        self.assertEqual([], carrier_problems(LEAF_TEXT)['layout-source'])


# These transformations are also applied to the final head by the delivery runner,
# which runs CarrierSourceTest unchanged and must exit nonzero for each mutant.
CARRIER_MUTATIONS = {
    'placement': ('\n                                    desktopChrome()\n', '\n'),
    'hit-regions': ('.desktopCommandHitRegion("desktop-player-controls")', '.desktopCommandHitRegion("other-controls")'),
    'subtitles': ('viewportSize.height - reservedBottomPx', 'viewportSize.height'),
    'layout-source': ('windowsUxSettings.playerLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE)', 'windowsUxSettings.playerLayout, false)'),
}


class CarrierMutationTest(unittest.TestCase):
    def test_each_carrier_mutant_is_caught_by_its_own_check(self):
        for key, (before, after) in CARRIER_MUTATIONS.items():
            with self.subTest(key=key):
                self.assertEqual(1, LEAF_TEXT.count(before))
                changed = LEAF_TEXT.replace(before, after, 1)
                problems = carrier_problems(changed)
                self.assertTrue(problems[key], key)
                self.assertEqual([], [other for other, errors in problems.items() if other != key and errors])

    def test_native_order_duplication_pip_and_region_swaps_are_caught(self):
        mutations = (
            ('placement', 'actions.overlay()\n                                    desktopChrome()', 'desktopChrome()\n                                    actions.overlay()'),
            ('placement', '                                    desktopChrome()', '                                    desktopChrome()\n                                    desktopChrome()'),
            ('placement', '\n                            desktopChrome()\n', '\n'),
            ('hit-regions', 'desktop-player-header', 'desktop-player-controls'),
            ('subtitles', 'if (!legacyChrome && chromeVisible)', 'if (!legacyChrome)'),
            ('subtitles', 'overlayControlsHeightPx + with(density)', '0 + with(density)'),
        )
        for key, before, after in mutations:
            with self.subTest(key=key, anchor=before):
                self.assertEqual(1, LEAF_TEXT.count(before))
                self.assertTrue(carrier_problems(LEAF_TEXT.replace(before, after, 1))[key])

    def test_the_consumer_chain_restores_its_exact_base(self):
        self.assertEqual('d29d06bb00631b5da9c151b270625cd082a7fddf', CONSUMER_RECORD['base'])
        self.assertEqual(CONSUMER_RECORD['leaf_sha256'], sha256(reverse_consumer(LEAF_TEXT)))
        for label, _before, after in CONSUMER_HUNKS:
            self.assertEqual(1, LEAF_TEXT.count('\n'.join(after)), label)
        with self.assertRaises(AssertionError):
            reverse_consumer(LEAF_TEXT.replace('desktop-player-header', 'other-header', 1))


class MutationTest(unittest.TestCase):
    def mutated(self, old, new, count=1):
        self.assertIn(old, LEAF_TEXT, "the mutation anchor is gone: " + old)
        text = LEAF_TEXT.replace(old, new, count)
        self.assertNotEqual(LEAF_TEXT, text)
        return text

    def test_every_check_passes_on_the_real_leaf(self):
        for check in (check_v2_structure, check_routes, check_construction):
            self.assertEqual([], check(LEAF_TEXT), check.__name__)

    def test_a_bar_that_is_shown_in_any_layout_is_found(self):
        self.assertTrue(check_v2_structure(self.mutated(BAR_CALL, "if (chromeVisible) controls()")))
        self.assertTrue(check_v2_structure(self.mutated(STRIP_CALL, "if (chromeVisible) header()")))

    def test_a_panel_that_is_gated_on_the_legacy_layout_is_found(self):
        self.assertTrue(check_v2_structure(self.mutated(PANEL_CALL, PANEL_CALL.replace('dock.mounted', 'dock.mounted && legacyChrome'))))

    def test_a_lambda_that_is_never_called_or_called_before_it_is_defined_is_found(self):
        self.assertTrue(check_v2_structure(self.mutated(BAR_CALL, "")))
        moved = self.mutated(BAR_CALL, "").replace(BAR_OPEN, BAR_CALL + "\n" + BAR_OPEN, 1)
        self.assertTrue(check_v2_structure(moved))

    def test_a_desktop_request_that_is_honoured_without_an_overlay_is_found(self):
        # The leaf must not pass an availability of its own: it reads the constant of the selection file, once, in this exact form.
        self.assertTrue(check_v2_structure(self.mutated("DESKTOP_PLAYER_OVERLAY_AVAILABLE", "true")))
        self.assertTrue(check_v2_structure(self.mutated("windowsUxSettings.playerLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE) ==",
                                                        "windowsUxSettings.playerLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE) !=")))

    def test_a_bare_return_inside_a_hoisted_lambda_is_found(self):
        self.assertTrue(check_v2_structure(self.mutated("enhancement = actions.enhancement,", "enhancement = actions.enhancement, x = { return },")))
        # A label that names something outside the lambda would leave it.
        self.assertTrue(check_v2_structure(self.mutated("selectedState.info.pages.getOrNull(index)?.cid != part.cid) return@TextButton",
                                                        "selectedState.info.pages.getOrNull(index)?.cid != part.cid) return@LaunchedEffect")))

    def test_an_unrouted_or_mis_routed_bar_callback_is_found(self):
        self.assertTrue(check_routes(self.mutated("onMute = controls.mute,", "onMute = { command { native.togglePause() } },")))
        self.assertTrue(check_routes(self.mutated("onSeek = controls.seek,", "onSeek = controls.quality,")))
        self.assertTrue(check_routes(self.mutated("IconButton(onClick = controls.back,", "IconButton(onClick = { if (current()) actions.back() },")))

    def test_a_changed_capability_in_the_construction_is_found(self):
        self.assertTrue(check_construction(self.mutated("isMuted = { state.muted }", "isMuted = { false }")))
        self.assertTrue(check_construction(self.mutated("setVolume = { native.setVolume(it) },", "setVolume = { native.setVolume(it / 2) },")))
        self.assertTrue(check_construction(self.mutated("current = ::current, command = ::command,", "command = ::command, current = ::current,")))

    def test_a_change_outside_the_recorded_hunks_breaks_the_identity(self):
        edited = self.mutated("surface.isFocusable = true", "surface.isFocusable = false")
        self.assertNotEqual(BASE_SHA256, sha256(reverse_apply(edited)))
        edited = self.mutated("val viewportLease = remember(nativeSurface) { Any() }", "val viewportLease = remember(nativeSurface) { Object() }")
        self.assertNotEqual(BASE_SHA256, sha256(reverse_apply(edited)))

    def test_a_hunk_that_no_longer_matches_is_reported_by_name(self):
        edited = self.mutated("if (chromeVisible && legacyChrome) legacyTopStrip()", "if (chromeVisible && legacyChrome) legacyTopStrip(1)")
        with self.assertRaises(AssertionError) as caught:
            reverse_apply(edited)
        self.assertIn("top strip lambda closes and is called", str(caught.exception))

    def test_a_binding_member_that_drifts_from_the_original_lambda_is_found(self):
        text = model.read(model.BINDING_KT)
        drifted = text.replace("preferencesChanged(preferences.copy(muted = !isMuted()))", "preferencesChanged(preferences.copy(muted = isMuted()))", 1)
        self.assertNotEqual(text, drifted)
        self.assertNotEqual(dict(model.MEMBERS), model.binding_members(drifted))
        gone = text.replace("    val seek: (Double) -> Unit = { seconds -> if (current()) seekToSeconds(seconds) }\n", "", 1)
        self.assertNotEqual(dict(model.MEMBERS), model.binding_members(gone))


class SourceTest(unittest.TestCase):
    def test_every_file_of_the_slice_is_plain_lf(self):
        files = [model.LEAF_KT, model.BINDING_KT, model.SELECTION_KT, model.SETTINGS_KT, model.JVM_BINDING_TEST, model.JVM_SELECTION_TEST,
                 Path(__file__).resolve(), Path(__file__).resolve().parent / "ux_leaf_model.py",
                 Path(__file__).resolve().parent / "test_ux_leaf_anchor_guard.py", Path(__file__).resolve().parent / "test_ux_settings_signatures.py"]
        source.assert_lf_only(self, *files)


if __name__ == "__main__":
    unittest.main()
