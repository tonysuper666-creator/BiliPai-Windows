package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
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
}
