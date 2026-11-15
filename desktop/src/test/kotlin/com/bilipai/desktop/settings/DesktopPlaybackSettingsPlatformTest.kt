package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopPlaybackSettingsPlatformTest {
    @Test fun accountReadsAreLiveAndRetiredOwnersNeverEnterCallbacks() {
        var current = true
        var loggedIn = false
        var vip = false
        var reads = 0
        val platform = DesktopPlaybackSettingsPlatform({ current }, { reads++; loggedIn }, { reads++; vip },
            { _, _ -> error("Reading account state must not log a change") })
        for (loginValue in listOf(false, true)) for (vipValue in listOf(false, true)) {
            loggedIn = loginValue
            vip = vipValue
            assertEquals(loginValue, platform.isPlaybackLoggedIn())
            assertEquals(vipValue, platform.isPlaybackVip())
        }
        assertEquals(8, reads)
        current = false
        assertTrue(platform.isPlaybackLoggedIn())
        assertTrue(platform.isPlaybackVip())
        assertEquals(8, reads)
    }

    @Test fun settingChangesKeepTheirArgumentsAndStopAtOwnerRetirement() {
        var current = true
        val received = mutableListOf<Pair<String, String>>()
        val platform = DesktopPlaybackSettingsPlatform({ current }, { error("No account read") },
            { error("No account read") }, { name, value -> received += name to value })
        val expected = listOf("hw_decode" to "false", "auto_skip_op_ed" to "true", "double_tap_like" to "false")
        expected.forEach { (name, value) -> platform.logSettingChange(name, value) }
        assertEquals(expected, received)
        current = false
        platform.logSettingChange("hw_decode", "true")
        assertEquals(expected, received)
    }

    @Test fun compositionScopesKeepRootsIsolatedAndNeverWritePreferences(): Unit = runBlocking {
        val roots = List(2) { Files.createTempDirectory("bp-playback-platform-") }
        try {
            val stores = roots.map(::DesktopPluginStore)
            stores.forEachIndexed { i, store -> store.update("settings", mapOf("hw_decode" to JsonPrimitive(i == 0))) }
            val before = stores.map { it.preferences("settings") }
            val received = List(2) { mutableListOf<Pair<String, String>>() }
            val active = booleanArrayOf(true, true)
            val platforms = List(2) { i -> DesktopPlaybackSettingsPlatform({ active[i] }, { i == 0 }, { i == 1 },
                { name, value -> received[i] += name to value }) }
            var selected by mutableIntStateOf(0)
            var observed: DesktopPlaybackSettingsPlatform? = null
            val scene = ImageComposeScene(320, 200, Density(1f)) {
                CompositionLocalProvider(LocalDesktopPlaybackSettingsPlatform provides platforms[selected]) {
                    val platform = LocalDesktopPlaybackSettingsPlatform.current
                    SideEffect { observed = platform }
                }
            }
            suspend fun await(index: Int) {
                withTimeout(5_000) {
                    while (observed !== platforms[index]) {
                        scene.render(System.nanoTime()).close()
                        delay(12)
                    }
                }
            }
            try {
                await(0)
                val first = assertNotNull(observed)
                assertTrue(first.isPlaybackLoggedIn())
                assertFalse(first.isPlaybackVip())
                first.logSettingChange("hw_decode", "false")
                active[0] = false
                selected = 1
                await(1)
                val second = assertNotNull(observed)
                assertSame(platforms[1], second)
                assertFalse(second.isPlaybackLoggedIn())
                assertTrue(second.isPlaybackVip())
                second.logSettingChange("double_tap_like", "true")
                first.logSettingChange("stale", "ignored")
                assertTrue(first.isPlaybackLoggedIn())
                assertEquals(listOf("hw_decode" to "false"), received[0])
                assertEquals(listOf("double_tap_like" to "true"), received[1])
            } finally {
                scene.close()
                active.fill(false)
            }
            assertTrue(platforms[1].isPlaybackVip())
            assertEquals(before, roots.map { DesktopPluginStore(it).preferences("settings") })
        } finally {
            roots.forEach { it.toFile().deleteRecursively() }
        }
    }

    @Test fun retiredOwnerKeepsItsLastRealAccountValuesWhileTheWindowContinuesRecomposing(): Unit = runBlocking {
        var current = true
        var reads = 0
        val platform = DesktopPlaybackSettingsPlatform({ current }, { reads++; true }, { reads++; true },
            { _, _ -> error("This composition must not log") })
        var revision by mutableIntStateOf(0)
        var observed = -1
        var account: Pair<Boolean, Boolean>? = null
        val scene = ImageComposeScene(320, 200, Density(1f)) {
            CompositionLocalProvider(LocalDesktopPlaybackSettingsPlatform provides platform) {
                val version = revision
                val mounted = LocalDesktopPlaybackSettingsPlatform.current
                val values = mounted.isPlaybackLoggedIn() to mounted.isPlaybackVip()
                SideEffect { observed = version; account = values }
            }
        }
        suspend fun await(expected: Int) = withTimeout(5_000) {
            while (observed != expected) {
                scene.render(System.nanoTime()).close()
                delay(12)
            }
        }
        try {
            await(0)
            assertEquals(true to true, account)
            assertEquals(2, reads)
            current = false
            repeat(3) {
                revision++
                await(revision)
                assertEquals(true to true, account, "Retirement must retain evidence, not invent a logged-out state")
                assertEquals(2, reads, "Retired recompositions must not enter the account owner")
            }
        } finally {
            scene.close()
        }
    }

    @Test fun retirementWithoutAnyPriorReadNeverInventsAnAccount() {
        val platform = DesktopPlaybackSettingsPlatform({ false }, { error("Retired owner read") },
            { error("Retired owner read") }, { _, _ -> error("Retired log") })
        assertFailsWith<CancellationException> { platform.isPlaybackLoggedIn() }
        assertFailsWith<CancellationException> { platform.isPlaybackVip() }
    }

    @Test fun playbackPatchHasExactlyTheSixApprovedBoundaries() {
        val patch = java.nio.file.Path.of("upstream-core/patches/app/src/main/java/com/android/purebilibili/feature/settings/screen/PlaybackSettingsScreen.kt.patch")
            .toFile().readLines()
        assertEquals(6, patch.count { it.startsWith("@@ ") })
        patch.filter { it.startsWith("@@ ") }.forEach { header ->
            val sizes = Regex("@@ -\\d+,(\\d+) \\+\\d+,(\\d+) @@").matchEntire(header)!!
            assertTrue(sizes.groupValues.drop(1).all { it.toInt() <= 10 }, header)
        }
        val removed = patch.filter { it.startsWith("-") && !it.startsWith("---") }.map { it.drop(1).trim() }
        assertEquals(listOf(
            "com.android.purebilibili.core.util.AnalyticsHelper.logSettingChange(\"hw_decode\", it.toString())",
            "val isLoggedIn = com.android.purebilibili.data.repository.VideoRepository.isPlaybackLoggedIn()",
            "val isVip = com.android.purebilibili.data.repository.VideoRepository.isPlaybackVip()",
            "com.android.purebilibili.core.util.AnalyticsHelper.logSettingChange(",
            "com.android.purebilibili.core.util.AnalyticsHelper.logSettingChange(\"double_tap_like\", it.toString())",
        ), removed)
        val added = patch.filter { it.startsWith("+") && !it.startsWith("+++") }.map { it.drop(1).trim() }
        assertEquals(2, added.count { it == "val desktopPlatform = com.bilipai.desktop.settings.LocalDesktopPlaybackSettingsPlatform.current" })
        assertEquals(3, added.count { "desktopPlatform.logSettingChange(" in it })
        assertEquals(1, added.count { "desktopPlatform.isPlaybackLoggedIn()" in it })
        assertEquals(1, added.count { "desktopPlatform.isPlaybackVip()" in it })
    }

    @Test fun unownedCompositionFailsInsteadOfUsingAGlobalAccount() {
        var scene: ImageComposeScene? = null
        try {
            assertFailsWith<UnsupportedDesktopCapability> {
                scene = ImageComposeScene(320, 200, Density(1f)) {
                    LocalDesktopPlaybackSettingsPlatform.current
                }
                scene!!.render(System.nanoTime()).close()
            }
        } finally {
            scene?.close()
        }
    }
}
