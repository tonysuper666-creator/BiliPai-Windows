package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DesktopOriginalNextWatchHintSettings as NextWatch
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlaybackPreferenceOperation
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** The next-watch card's single preference owner over the same persisted Windows store. No window, GPU or network. */
class DesktopNextWatchHintSettingsTest {
    private class Fixture {
        val directory = Files.createTempDirectory("bp-next-watch-hint-settings-")
        val store = DesktopPluginStore(directory)
        var current = true
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { current }, { action ->
            if (current) { action(); true } else false
        }, largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        val file get() = directory.resolve("plugin-settings.json")
    }

    private fun profile(value: String) =
        """{"schemaVersion":1,"app":"BiliPai","appVersion":"0.4.6","exportedAtIso":"2026-10-17T00:00:00Z","profileName":"next","sections":{"playback":{"next_watch_hint_enabled":$value}}}"""

    @Test fun `the card reader defaults on without creating a settings file`(): Unit = runBlocking {
        val f = Fixture()
        assertTrue(NextWatch.getNextWatchHintEnabled(f.context).first())
        assertFalse(Files.exists(f.file))
    }

    @Test fun `the switch setter is seen by the card reader and by a cold reader and keeps foreign keys`(): Unit = runBlocking {
        val f = Fixture()
        f.store.update("settings", mapOf("foreign" to JsonPrimitive("keep")))
        NextWatch.setNextWatchHintEnabled(f.context, false)
        assertFalse(NextWatch.getNextWatchHintEnabled(f.context).first())
        val cold = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(DesktopPluginStore(f.directory)), { true }, { it(); true })
        assertFalse(NextWatch.getNextWatchHintEnabled(cold).first())
        assertEquals(JsonPrimitive(false), f.store.preferences("settings")["next_watch_hint_enabled"])
        assertEquals(JsonPrimitive("keep"), f.store.preferences("settings")["foreign"])
        NextWatch.setNextWatchHintEnabled(f.context, true)
        assertTrue(NextWatch.getNextWatchHintEnabled(f.context).first())
        assertEquals(JsonPrimitive(true), f.store.preferences("settings")["next_watch_hint_enabled"])
    }

    @Test fun `the pane write journal publishes the same key the card reads`(): Unit = runBlocking {
        val f = Fixture()
        // The same journal DesktopOriginalPlaybackSettingsWriteScope.launch enters for the pane's writer.
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, { true }) {
            NextWatch.setNextWatchHintEnabled(f.context, false)
        }
        assertFalse(NextWatch.getNextWatchHintEnabled(f.context).first())
        assertEquals(JsonPrimitive(false), f.store.preferences("settings")["next_watch_hint_enabled"])
    }

    @Test fun `a retired settings page cannot change the card`(): Unit = runBlocking {
        val f = Fixture()
        NextWatch.setNextWatchHintEnabled(f.context, false)
        val before = Files.readAllBytes(f.file)
        f.current = false
        assertFailsWith<CancellationException> { NextWatch.setNextWatchHintEnabled(f.context, true) }
        f.current = true
        assertFalse(NextWatch.getNextWatchHintEnabled(f.context).first())
        assertContentEquals(before, Files.readAllBytes(f.file))
    }

    @Test fun `a cancelled writer cannot create settings`(): Unit = runBlocking {
        val f = Fixture()
        val job = launch {
            cancel("Original caller has retired")
            NextWatch.setNextWatchHintEnabled(f.context, false)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(NextWatch.getNextWatchHintEnabled(f.context).first())
        assertFalse(Files.exists(f.file))
    }

    @Test fun `backup exports the switch and imports the boolean but rejects string coercion`(): Unit = runBlocking {
        val exporter = Fixture()
        NextWatch.setNextWatchHintEnabled(exporter.context, false)
        val exported = DesktopSettingsShareService(exporter.context).buildExportArtifact("next", "0.4.6").profile.sections.playback
        assertEquals(JsonPrimitive(false), exported["next_watch_hint_enabled"])

        val f = Fixture()
        val service = DesktopSettingsShareService(f.context)
        val imported = service.applyImport(service.readImportSession(profile("false")))
        assertEquals(listOf("next_watch_hint_enabled"), imported.appliedKeys)
        assertFalse(NextWatch.getNextWatchHintEnabled(f.context).first())
        val again = service.applyImport(service.readImportSession(profile("true")))
        assertEquals(listOf("next_watch_hint_enabled"), again.appliedKeys)
        assertTrue(NextWatch.getNextWatchHintEnabled(f.context).first())

        val before = Files.readAllBytes(f.file)
        val invalid = service.applyImport(service.readImportSession(profile("\"false\"")))
        assertTrue(invalid.appliedKeys.isEmpty())
        assertEquals(listOf("next_watch_hint_enabled"), invalid.skippedKeys)
        assertContentEquals(before, Files.readAllBytes(f.file))
        val number = service.applyImport(service.readImportSession(profile("0")))
        assertTrue(number.appliedKeys.isEmpty())
        assertContentEquals(before, Files.readAllBytes(f.file))
    }

    @Test fun `search reaches the switch through the playback behaviour group and the auto continue queries stay single`() {
        for (query in listOf("接下来观看", "接下来观看卡片", "下一集提示", "相关推荐提示")) {
            val result = resolveDesktopWindowsSettingsSearchResults(query).single()
            assertEquals(SettingsSearchTarget.PLAYBACK, result.target, query)
            assertEquals("windows_playback_behavior", result.focusId, query)
        }
        for (query in listOf("自动播放下一个", "列表连续播放", "收藏夹")) {
            val result = resolveDesktopWindowsSettingsSearchResults(query).single()
            assertEquals("windows_playback_behavior", result.focusId, query)
        }
    }
}
