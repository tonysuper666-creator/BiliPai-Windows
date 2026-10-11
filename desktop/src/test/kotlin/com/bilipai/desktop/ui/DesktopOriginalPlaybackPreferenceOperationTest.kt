package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings
import com.android.purebilibili.core.store.FullscreenAspectRatio
import com.android.purebilibili.feature.video.ui.components.VideoAspectRatio
import com.android.purebilibili.feature.video.ui.components.toFullscreenAspectRatio
import com.android.purebilibili.feature.video.ui.components.toVideoAspectRatio
import com.android.purebilibili.core.store.PlaybackCompletionBehavior
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Executes real original setters and the real Store file/CAS; no native or UI claim. */
class DesktopOriginalPlaybackPreferenceOperationTest {
    private fun originalContext(store: DesktopPluginStore, owns: () -> Boolean = { true },
        commit: ((() -> Unit) -> Boolean) = { it(); true }) =
        DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), owns, commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
    private fun store() = DesktopPluginStore(Files.createTempDirectory("bp-original-playback-journal-"))

    @Test fun wholeOriginalSetterPublishesCanonicalAndMirrorDurably(): Unit = runBlocking {
        val store = store(); val context = originalContext(store)
        store.update("settings", mapOf("foreign" to JsonPrimitive("keep")))
        store.update("playback_speed_cache", mapOf("foreign" to JsonPrimitive("mirror")))
        DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(context, 1.5f)
        val disk = Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(1.5f, disk["settings"]!!.jsonObject["last_playback_speed"]!!.jsonPrimitive.float)
        assertEquals(1.5f, disk["playback_speed_cache"]!!.jsonObject["last_speed"]!!.jsonPrimitive.float)
        assertEquals("keep", disk["settings"]!!.jsonObject["foreign"]!!.jsonPrimitive.content)
        assertEquals("mirror", disk["playback_speed_cache"]!!.jsonObject["foreign"]!!.jsonPrimitive.content)
        val cold = originalContext(DesktopPluginStore(store.root))
        assertEquals(1.5f, cold.getSharedPreferences("playback_speed_cache", 0).getFloat("last_speed", -1f))
    }

    @Test fun pageActionStagesReadYourWritesWithoutEarlyMemoryOrDiskPublication(): Unit = runBlocking {
        val store = store(); val context = originalContext(store)
        DesktopOriginalPlaybackPreferenceOperation.run(context, { true }) {
            DesktopOriginalPlaybackSettingsPreferences.setHwDecode(context, false)
            assertFalse(context.settingsDataStore.data.first()[playerBooleanPreferencesKey("hw_decode")]!!)
            assertFalse(context.getSharedPreferences("player_settings_cache", 0).getBoolean("hw_decode_enabled", true))
            assertTrue(store.preferences("settings").isEmpty())
            assertTrue(store.preferences("player_settings_cache").isEmpty())
            assertFalse(Files.exists(store.root.resolve("plugin-settings.json")))
            DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(context, 1.5f)
        }
        assertFalse(store.preferences("settings")["hw_decode"]!!.jsonPrimitive.boolean)
        assertFalse(store.preferences("player_settings_cache")["hw_decode_enabled"]!!.jsonPrimitive.boolean)
        assertEquals(1.5f, store.preferences("playback_speed_cache")["default_speed"]!!.jsonPrimitive.float)
        assertNull(DesktopOriginalPlaybackPreferenceOperation.currentOrNull())
    }

    @Test fun retirementAtPermitRejectsBothNamespaces(): Unit = runBlocking {
        val store = store(); val owned = AtomicBoolean(true)
        val context = originalContext(store, owned::get) { action -> owned.set(false); action(); true }
        assertThrows(CancellationException::class.java) {
            runBlocking { DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(context, 1.5f) }
        }
        assertTrue(store.preferences("settings").isEmpty())
        assertTrue(store.preferences("playback_speed_cache").isEmpty())
        assertFalse(Files.exists(store.root.resolve("plugin-settings.json")))
        assertNull(DesktopOriginalPlaybackPreferenceOperation.currentOrNull())
    }

    @Test fun callerCancelledAfterStagingCannotCommitOrLeakTheJournal(): Unit = runBlocking {
        val store = store(); val context = originalContext(store)
        val staged = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val action = launch(Dispatchers.Default) {
            DesktopOriginalPlaybackPreferenceOperation.run(context, { true }) {
                DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(context, 1.5f)
                staged.complete(Unit); resume.await()
            }
        }
        staged.await(); action.cancelAndJoin()
        assertTrue(store.preferences("settings").isEmpty())
        assertTrue(store.preferences("playback_speed_cache").isEmpty())
        assertFalse(Files.exists(store.root.resolve("plugin-settings.json")))
        DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(context, 2f)
        assertEquals(2f, store.preferences("playback_speed_cache")["last_speed"]!!.jsonPrimitive.float)
        assertNull(DesktopOriginalPlaybackPreferenceOperation.currentOrNull())
    }

    @Test fun canonicalCASConflictReplaysWholeOriginalNormalizationAndDerivedMirror(): Unit = runBlocking {
        val store = store(); val permits = AtomicInteger()
        store.update("settings", mapOf("playback_speed_options" to JsonPrimitive("1,1.5,2")))
        val context = originalContext(store, commit = { action ->
            if (permits.incrementAndGet() == 1) store.update("settings",
                mapOf("playback_speed_options" to JsonPrimitive("1,2"), "concurrent" to JsonPrimitive(true)))
            action(); true
        })
        DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(context, 1.6f)
        assertTrue(permits.get() >= 2)
        assertEquals(2f, store.preferences("settings")["default_playback_speed"]!!.jsonPrimitive.float)
        assertEquals(2f, store.preferences("playback_speed_cache")["default_speed"]!!.jsonPrimitive.float)
        assertTrue(store.preferences("settings")["concurrent"]!!.jsonPrimitive.boolean)
    }

    @Test fun mirrorOnlyCASConflictIsRetriedAgainstTheWholeDocument(): Unit = runBlocking {
        val store = store(); val permits = AtomicInteger()
        DesktopOriginalPlaybackSettingsPreferences.setPlaybackCompletionBehavior(originalContext(store), PlaybackCompletionBehavior.REPEAT_ONE)
        val context = originalContext(store, commit = { action ->
            if (permits.incrementAndGet() == 1) store.update("auto_play_cache",
                mapOf("playback_completion_behavior" to JsonPrimitive(1), "concurrent_mirror" to JsonPrimitive(true)))
            action(); true
        })
        DesktopOriginalPlaybackSettingsPreferences.setPlaybackCompletionBehavior(context, PlaybackCompletionBehavior.REPEAT_ONE)
        assertTrue(permits.get() >= 2)
        assertEquals(PlaybackCompletionBehavior.REPEAT_ONE.value,
            store.preferences("auto_play_cache")["playback_completion_behavior"]!!.jsonPrimitive.int)
        assertTrue(store.preferences("auto_play_cache")["concurrent_mirror"]!!.jsonPrimitive.boolean)
    }

    @Test fun durableRenameFailureLeavesCanonicalAndMirrorUnpublished(): Unit = runBlocking {
        val store = store(); val obstruction = Files.createDirectory(store.root.resolve("plugin-settings.json"))
        Files.writeString(obstruction.resolve("keep"), "block replace")
        assertThrows(Exception::class.java) { runBlocking {
            DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(originalContext(store), 1.5f)
        } }
        assertTrue(store.preferences("settings").isEmpty())
        assertTrue(store.preferences("playback_speed_cache").isEmpty())
        assertTrue(Files.isDirectory(obstruction))
    }

    @Test fun anotherContextCannotJoinAnOriginalPageAction(): Unit = runBlocking {
        val store = store(); val first = originalContext(store); val other = originalContext(store)
        assertThrows(IllegalStateException::class.java) { runBlocking {
            DesktopOriginalPlaybackPreferenceOperation.run(first, { true }) {
                DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(other, 1.5f)
            }
        } }
        assertTrue(store.preferences("settings").isEmpty())
        assertNull(DesktopOriginalPlaybackPreferenceOperation.currentOrNull())
    }

    @Test fun suspendedIndependentActionsDoNotShareThreadLocalStaging(): Unit = runBlocking {
        val left = store(); val right = store(); val leftContext = originalContext(left)
        val staged = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            DesktopOriginalPlaybackPreferenceOperation.run(leftContext, { true }) {
                DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(leftContext, 1.5f)
                staged.complete(Unit); resume.await()
            }
        }
        staged.await()
        DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(originalContext(right), 2f)
        assertEquals(2f, right.preferences("settings")["last_playback_speed"]!!.jsonPrimitive.float)
        assertTrue(left.preferences("settings").isEmpty())
        resume.complete(Unit); first.join()
        assertEquals(1.5f, left.preferences("settings")["last_playback_speed"]!!.jsonPrimitive.float)
    }

    @Test fun canonicalAspectSetterRoundTripsOriginalMapperThroughColdStore(): Unit = runBlocking {
        val store = store(); val context = originalContext(store)
        store.update("settings", mapOf("unrelated" to JsonPrimitive("keep")))
        store.update("unrelated_namespace", mapOf("value" to JsonPrimitive(17)))
        val selected = VideoAspectRatio.RATIO_4_3
        DesktopOriginalPlaybackPreferenceOperation.run(context, { true }) {
            DesktopOriginalPlayerSectionSettings.setFullscreenAspectRatio(context, selected.toFullscreenAspectRatio())
        }
        assertEquals(selected, DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(context)
            .first().fixedFullscreenAspectRatio.toVideoAspectRatio())
        val disk = Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(FullscreenAspectRatio.RATIO_4_3.value,
            disk["settings"]!!.jsonObject["fullscreen_aspect_ratio"]!!.jsonPrimitive.int)
        assertEquals("keep", disk["settings"]!!.jsonObject["unrelated"]!!.jsonPrimitive.content)
        assertEquals(17, disk["unrelated_namespace"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        // Same-root Store construction normally shares its live Backing. Retire
        // that actual production generation to force a real file-backed cold read.
        store.freezeWrites()
        val cold = originalContext(DesktopPluginStore(store.root))
        assertEquals(selected, DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(cold)
            .first().fixedFullscreenAspectRatio.toVideoAspectRatio())
    }

    @Test fun retiredAspectJournalLeavesExactStoredChoiceUnchanged(): Unit = runBlocking {
        val store = store(); val healthy = originalContext(store)
        DesktopOriginalPlayerSectionSettings.setFullscreenAspectRatio(healthy, FullscreenAspectRatio.RATIO_16_9)
        val diskBefore = Files.readAllBytes(store.root.resolve("plugin-settings.json"))
        val settingsBefore = store.preferences("settings")
        val owned = AtomicBoolean(true)
        val retiring = originalContext(store, owned::get) { action -> owned.set(false); action(); true }
        assertThrows(CancellationException::class.java) { runBlocking {
            DesktopOriginalPlaybackPreferenceOperation.run(retiring, owned::get) {
                DesktopOriginalPlayerSectionSettings.setFullscreenAspectRatio(retiring,
                    VideoAspectRatio.STRETCH.toFullscreenAspectRatio())
                assertEquals(FullscreenAspectRatio.STRETCH,
                    DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(retiring)
                        .first().fixedFullscreenAspectRatio)
            }
        } }
        assertFalse(owned.get())
        assertEquals(settingsBefore, store.preferences("settings"))
        assertArrayEquals(diskBefore, Files.readAllBytes(store.root.resolve("plugin-settings.json")))
        assertNull(DesktopOriginalPlaybackPreferenceOperation.currentOrNull())
        store.freezeWrites()
        assertEquals(FullscreenAspectRatio.RATIO_16_9,
            DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(originalContext(DesktopPluginStore(store.root)))
                .first().fixedFullscreenAspectRatio)
    }

    @Test fun cancelledAspectJournalRollsBackThenHealthySetterStillCommits(): Unit = runBlocking {
        val store = store(); val context = originalContext(store)
        DesktopOriginalPlayerSectionSettings.setFullscreenAspectRatio(context, FullscreenAspectRatio.FIT)
        val diskBefore = Files.readAllBytes(store.root.resolve("plugin-settings.json"))
        val settingsBefore = store.preferences("settings")
        val staged = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        val action = launch(Dispatchers.Default) {
            DesktopOriginalPlaybackPreferenceOperation.run(context, { true }) {
                DesktopOriginalPlayerSectionSettings.setFullscreenAspectRatio(context,
                    VideoAspectRatio.FILL.toFullscreenAspectRatio())
                assertEquals(FullscreenAspectRatio.FILL,
                    DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(context)
                        .first().fixedFullscreenAspectRatio)
                staged.complete(Unit); hold.await()
            }
        }
        try {
            withTimeout(5_000) { staged.await() }
            assertEquals(settingsBefore, store.preferences("settings"))
            assertArrayEquals(diskBefore, Files.readAllBytes(store.root.resolve("plugin-settings.json")))
        } finally { action.cancelAndJoin() }
        assertEquals(settingsBefore, store.preferences("settings"))
        assertArrayEquals(diskBefore, Files.readAllBytes(store.root.resolve("plugin-settings.json")))
        DesktopOriginalPlaybackPreferenceOperation.run(context, { true }) {
            DesktopOriginalPlayerSectionSettings.setFullscreenAspectRatio(context,
                VideoAspectRatio.RATIO_4_3.toFullscreenAspectRatio())
        }
        assertEquals(FullscreenAspectRatio.RATIO_4_3,
            DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(context).first().fixedFullscreenAspectRatio)
        assertNull(DesktopOriginalPlaybackPreferenceOperation.currentOrNull())
        store.freezeWrites()
        assertEquals(FullscreenAspectRatio.RATIO_4_3,
            DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(originalContext(DesktopPluginStore(store.root)))
                .first().fixedFullscreenAspectRatio)
    }

    @Test fun aspectJournalCASReplayPreservesInterleavedUnrelatedSettings(): Unit = runBlocking {
        val store = store(); val permits = AtomicInteger()
        store.update("settings", mapOf("unrelated" to JsonPrimitive("initial")))
        store.update("unrelated_namespace", mapOf("existing" to JsonPrimitive(17)))
        val context = originalContext(store, commit = { action ->
            // Actual competing Store writes occur after journal snapshot/staging
            // and before its final whole-document CAS, not a mocked retry result.
            if (permits.incrementAndGet() == 1) {
                store.update("settings", mapOf("unrelated" to JsonPrimitive("concurrent"),
                    "other_preference" to JsonPrimitive(true)))
                store.update("unrelated_namespace", mapOf("new_value" to JsonPrimitive(23)))
            }
            action(); true
        })
        DesktopOriginalPlaybackPreferenceOperation.run(context, { true }) {
            DesktopOriginalPlayerSectionSettings.setFullscreenAspectRatio(context,
                VideoAspectRatio.STRETCH.toFullscreenAspectRatio())
        }
        assertTrue(permits.get() >= 2)
        assertEquals(FullscreenAspectRatio.STRETCH,
            DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(context).first().fixedFullscreenAspectRatio)
        assertEquals("concurrent", store.preferences("settings")["unrelated"]!!.jsonPrimitive.content)
        assertTrue(store.preferences("settings")["other_preference"]!!.jsonPrimitive.boolean)
        val disk = Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(FullscreenAspectRatio.STRETCH.value,
            disk["settings"]!!.jsonObject["fullscreen_aspect_ratio"]!!.jsonPrimitive.int)
        assertEquals(17, disk["unrelated_namespace"]!!.jsonObject["existing"]!!.jsonPrimitive.int)
        assertEquals(23, disk["unrelated_namespace"]!!.jsonObject["new_value"]!!.jsonPrimitive.int)
        store.freezeWrites()
        val cold = DesktopPluginStore(store.root)
        assertEquals(FullscreenAspectRatio.STRETCH,
            DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(originalContext(cold))
                .first().fixedFullscreenAspectRatio)
        assertEquals(disk["settings"]!!.jsonObject, cold.preferences("settings"))
        assertEquals(disk["unrelated_namespace"]!!.jsonObject, cold.preferences("unrelated_namespace"))
    }
}
