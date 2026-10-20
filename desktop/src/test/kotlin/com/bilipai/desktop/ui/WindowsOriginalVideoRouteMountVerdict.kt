package com.bilipai.desktop.ui

/**
 * Slice V12a (UX spec 4.13 and 9.2): the pure part of the fixture that answers "does the dormant original VideoDetail branch of the
 * shell mount?". Nothing here touches AWT, Compose, a window or the network, so the truth table below runs as an ordinary JVM test
 * (WindowsOriginalVideoRouteMountVerdictTest) and the Windows fixture (WindowsOriginalVideoRouteMountFixture) only feeds it names.
 *
 * What "dormant" means. The shell hands a VideoDetail key to the desktop-owned DesktopWindowsVideoPhysicalLeaf. The vendored
 * VideoDetailScreenStateHolder, VideoPlayerSection and VideoPlayerOverlay are reachable only through DesktopOriginalVideoPhysicalLeaf,
 * which the shell calls for the audio routes and never for a VideoDetail key. The two branches of the shell's when are exclusive, so
 * the source already says the original page does not mount for an ordinary video. The fixture is the runtime half of that statement:
 * it opens an ordinary video in the actual Main window and records which of the two pages put its own controls into the accessibility
 * tree. A name is an anchor only if exactly one of the two pages can emit it, which desktop/tools/ux/test_ux_original_route_fixture.py
 * re-derives from the call graph of the sources on every run.
 */
internal enum class OriginalVideoRouteVerdict {
    /** Every leaf anchor is present and no original anchor is: the desktop leaf is the page and the original branch did not mount. */
    DESKTOP_LEAF_ONLY,

    /** At least one original anchor is present and no leaf anchor is: the original branch mounted instead of the desktop leaf. */
    ORIGINAL_ONLY,

    /** Original anchors and leaf anchors are both present: the original branch mounted alongside the desktop leaf. */
    BOTH,

    /** Neither page identified itself (or only part of the leaf did): nothing may be claimed. */
    INCONCLUSIVE,
}

/** What the run is required to find. The measurement is always written down first; the expectation only decides the exit code. */
internal enum class OriginalVideoRouteExpectation(val argument: String) {
    /** The shipped shell: the ordinary video page is the desktop leaf. */
    DESKTOP_LEAF("desktop-leaf"),

    /** A shell that routes ordinary videos to the original leaf (slice V12b, only if decision D7 asks). */
    ORIGINAL("original"),

    /** Pure measurement: any identified page passes, only an inconclusive run fails. */
    ANY("any");

    fun accepts(verdict: OriginalVideoRouteVerdict): Boolean = when (this) {
        DESKTOP_LEAF -> verdict == OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY
        ORIGINAL -> verdict == OriginalVideoRouteVerdict.ORIGINAL_ONLY
        ANY -> verdict != OriginalVideoRouteVerdict.INCONCLUSIVE
    }

    companion object {
        fun parse(argument: String): OriginalVideoRouteExpectation =
            requireNotNull(OriginalVideoRouteExpectation.entries.firstOrNull { it.argument == argument }) {
                "Unknown expectation '$argument'; use ${OriginalVideoRouteExpectation.entries.joinToString(" | ") { it.argument }}"
            }
    }
}

internal object WindowsOriginalVideoRouteMount {
    /**
     * Accessible names that only the desktop leaf's LEGACY control bar spells (DesktopWindowsVideoControlBarStrings.DETAILS and
     * MORE_ACTIONS). The bar is mounted whenever the layout resolves to LEGACY and the chrome is visible, which is the case on page
     * entry, with or without a loaded video. Under the future DESKTOP player layout the bar is not mounted, so this list must be
     * revisited by the slice that flips the default (V4a and later).
     */
    val LEAF_ANCHORS: List<String> = listOf("详情", "更多播放操作")

    /**
     * Accessible names that only the vendored original page emits. Each is spelled as a content description or a label inside a
     * composable that DesktopOriginalVideoHolderRoute reaches and DesktopWindowsVideoPhysicalLeaf does not: the uploader block of the
     * information pane (UpInfoSection), the danmaku toggle of the large layout (NativeDanmakuToggleButton), the portrait top bar
     * (PortraitTopBar), the comment and dislike buttons of the landscape top bar (TopControlBar), the part and danmaku-login controls
     * of the overlay's bottom bar (BottomControlBar) and its stall banner (VideoPlayerOverlay). Which of them is on screen depends on
     * the layout the original page picks and on whether its chrome is showing, so the list is wide on purpose and one hit is enough
     * to say the branch mounted.
     */
    val ORIGINAL_ANCHORS: List<String> = listOf(
        "UP主头像", "UP主标识", "开启弹幕", "关闭弹幕", "主界面", "评论区", "不喜欢", "分集", "登录发弹幕", "关闭卡顿提示",
    )

    /**
     * True when one of the accessible names is the anchor, or holds it as a whole line or comma separated segment (a merged
     * Compose node joins its children with a comma or a line break). A longer name that merely contains the anchor does not count:
     * the anchor "全屏" is not found in "退出全屏".
     */
    fun containsAnchor(names: Collection<String>, anchor: String): Boolean {
        val segment = Regex("(^|[\\r\\n,，])\\s*" + Regex.escape(anchor) + "\\s*($|[\\r\\n,，])")
        return names.any { segment.containsMatchIn(it) }
    }

    /** The truth table. Both arguments are the anchors actually seen (subsets of the two lists above). */
    fun classify(leafSeen: Set<String>, originalSeen: Set<String>): OriginalVideoRouteVerdict {
        val leafComplete = leafSeen.containsAll(LEAF_ANCHORS)
        val original = originalSeen.isNotEmpty()
        return when {
            leafComplete && !original -> OriginalVideoRouteVerdict.DESKTOP_LEAF_ONLY
            original && leafSeen.isEmpty() -> OriginalVideoRouteVerdict.ORIGINAL_ONLY
            original -> OriginalVideoRouteVerdict.BOTH
            else -> OriginalVideoRouteVerdict.INCONCLUSIVE
        }
    }
}
