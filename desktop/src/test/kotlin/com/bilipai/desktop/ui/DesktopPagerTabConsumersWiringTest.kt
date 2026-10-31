package com.bilipai.desktop.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every pager tab row compiled into the desktop reads the rail of upstream's navigation/PagerSelectionMotion
 * (tabIndicatorPosition, tabSelectedPage), not the raw pager position: the click helper pre-jumps the pager, so a row
 * that still reads currentPage + offset would hard-cut instead of gliding. This reads the files the compiler actually
 * receives (build/transition-patched, build/patched-upstream, the merged root), so it fails when a producer copy or a
 * patch drifts. Source wiring only: the glide itself is DesktopPagerSelectionMotionFrameTest.
 */
class DesktopPagerTabConsumersWiringTest {
    private val build = File("build")
    private val merged = File("../app/src/main/java/com/android/purebilibili")

    private fun compiled(vararg relative: String): String =
        listOf(build.resolve("transition-patched"), build.resolve("patched-upstream"), build.resolve("generated"))
            .flatMap { root -> relative.flatMap { listOf(root.resolve(it), root.resolve("app/src/main/java/" + it)) } }
            .firstOrNull { it.isFile }?.readText()
            ?: error("no compiled copy of ${relative.toList()} under ${build.absolutePath}; run compileKotlin first")

    private val rawIndicator = Regex("""currentPage\s*\+\s*\w+\.currentPageOffsetFraction""")

    private fun assertReadsRail(name: String, source: String, vararg required: String) {
        required.forEach { assertTrue(source.contains(it), "$name does not read `$it`") }
        val raw = source.lineSequence().withIndex().filter { (_, line) -> rawIndicator.containsMatchIn(line) }.map { it.index + 1 }.toList()
        assertTrue(raw.isEmpty() || name in tolerated, "$name still computes an indicator from currentPage + offset at lines $raw")
    }

    /** Sites that legitimately read the raw pager position (lyrics scrim progress; their upstream does as well). */
    private val tolerated = setOf("music")

    @Test fun bottomPager() = assertReadsRail("bottom",
        compiled("app/src/main/java/com/android/purebilibili/navigation/MainBottomPagerState.kt"), "pagerState.tabIndicatorPosition")

    @Test fun homeTopBar() = assertReadsRail("homeTopBar",
        compiled("com/android/purebilibili/feature/home/components/TopBar.kt"), "pagerState.tabIndicatorPosition")

    @Test fun homeScreenHighlightsTheClickedTab() =
        assertTrue(compiled("com/android/purebilibili/feature/home/DesktopOriginalHomeScreen.kt").contains("pagerState.tabSelectedPage"))

    @Test fun videoContent() = assertReadsRail("video",
        compiled("com/android/purebilibili/feature/video/screen/DesktopOriginalVideoContentSection.kt"), "pagerState.tabIndicatorPosition")

    @Test fun tabletVideo() = assertReadsRail("tabletVideo",
        compiled("com/android/purebilibili/feature/video/screen/TabletVideoLayout.kt"),
        "pagerState.tabIndicatorPosition", "selectedIndex = pagerState.tabSelectedPage")

    @Test fun tabletCinema() = assertReadsRail("tabletCinema",
        compiled("com/android/purebilibili/feature/video/screen/TabletCinemaLayout.kt"),
        "pagerState.tabIndicatorPosition", "selectedIndex = pagerState.tabSelectedPage")

    @Test fun space() = assertReadsRail("space",
        compiled("com/android/purebilibili/feature/space/SpaceScreen.kt"), "pagerState.tabIndicatorPosition")

    @Test fun profile() = assertReadsRail("profile",
        compiled("com/android/purebilibili/feature/profile/ProfileScreen.kt"), "pagerState.tabIndicatorPosition")

    @Test fun listenVideo() = assertReadsRail("listen",
        compiled("com/android/purebilibili/feature/audio/screen/ListenVideoScreen.kt"),
        "pagerState.tabIndicatorPosition", "selectedIndex = pagerState.tabSelectedPage")

    @Test fun musicPlayer() = assertReadsRail("music",
        compiled("com/android/purebilibili/feature/audio/screen/MusicPlayerContent.kt"),
        "pagerState.tabIndicatorPosition.coerceIn(0f, 1f)", "selectedIndex = pagerState.tabSelectedPage")

    @Test fun search() = assertReadsRail("search",
        compiled("com/android/purebilibili/feature/search/SearchScreen.kt"),
        "pagerState.tabIndicatorPosition", "pagerState.tabSelectedPage")

    @Test fun commonList() = assertReadsRail("commonList",
        compiled("com/android/purebilibili/feature/list/CommonListScreen.kt"), "historyPagerState.tabIndicatorPosition")

    @Test fun liveArea() {
        val source = compiled("com/android/purebilibili/feature/live/LiveAreaScreen.kt")
        assertReadsRail("liveArea", source, "pagerState.tabIndicatorPosition", "selectedTab = pagerState.tabSelectedPage")
        // The page-to-tab sync must not pull the selection back to an intermediate page while a click is travelling.
        assertTrue(source.contains("!pagerState.isTabSelectionInProgress && selectedTab != pagerState.currentPage"))
    }

    @Test fun everyConsumerOfTheClickHelperIsCovered() {
        val covered = setOf("MainBottomPagerState.kt", "DesktopOriginalHomeScreen.kt", "SearchScreen.kt", "CommonListScreen.kt",
            "ListenVideoScreen.kt", "DesktopOriginalVideoContentSection.kt", "MusicPlayerContent.kt", "LiveAreaScreen.kt",
            "TabletVideoLayout.kt", "TabletCinemaLayout.kt", "ContentTabPager.kt",
            // Bangumi's player tab row moves with TON-1002 (PR 18), tracked on the card.
            "DesktopOriginalBangumiPlayerContent.kt")
        val users = listOf(build.resolve("transition-patched"), build.resolve("patched-upstream"), build.resolve("generated"))
            .filter { it.isDirectory }.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { file -> file.readText().contains("animatePagerSelection(") && !file.path.contains("original-retained") }
            .map { it.name }.toSet() - "PagerSelectionMotion.kt"
        assertTrue(users.all { it in covered }, "pager click users without a rail wiring check: ${users - covered}")
        assertFalse(users.isEmpty())
    }
}
