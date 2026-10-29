package com.bilipai.desktop.ui

import androidx.datastore.preferences.DesktopPreferencesDataStoreOwner
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.android.purebilibili.core.store.SettingsSearchHistoryStore
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.coroutines.resume
import kotlin.test.assertNull

/** The page's upstream settings_prefs DataStore serializes edits like DataStore: a transform always runs on the values
 * it commits to, outside a settings operation (CAS rerun) and inside one (replayed on the actual snapshot). */
class DesktopOriginalSettingsAndroidContextTest {
    private val directory = Files.createTempDirectory("bp-original-settings-android-context-")
    private val plugin = DesktopPluginContext(DesktopPluginStore(directory))
    private val current = AtomicBoolean(true)
    private val context = DesktopOriginalPlayerSettingsContext(plugin, current::get, commitIfCurrent = { action ->
        if (current.get()) { action(); true } else false
    })
    private val counter = intPreferencesKey("fixture_counter")
    private val settings = (context.androidContext as DesktopPreferencesDataStoreOwner).desktopPreferencesDataStore("settings_prefs")
    private fun stored() = DesktopPluginStore(directory).preferences("settings")["fixture_counter"]

    @Test fun `a write that lands while the transform runs is read again instead of overwritten`() = runBlocking {
        plugin.store.update("settings", mapOf("fixture_counter" to JsonPrimitive(1)))
        var runs = 0
        settings.edit { prefs ->
            runs++
            // Another writer moves the namespace after this transform has read it.
            if (runs == 1) plugin.store.update("settings", mapOf("fixture_counter" to JsonPrimitive(10)))
            prefs[counter] = (prefs[counter] ?: 0) + 1
        }
        assertEquals(2, runs)
        assertEquals(JsonPrimitive(11), stored())
    }

    @Test fun `an unchanged namespace commits the first transform`() = runBlocking {
        var runs = 0
        settings.edit { prefs -> runs++; prefs[counter] = 5 }
        assertEquals(1, runs)
        assertEquals(JsonPrimitive(5), stored())
    }

    @Test fun `two concurrent increments both land when one transform suspends`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            settings.edit { prefs ->
                val old = prefs[counter] ?: 0
                entered.complete(Unit)
                release.await()
                prefs[counter] = old + 1
            }
        }
        entered.await()
        val second = async(Dispatchers.Default) { settings.edit { prefs -> prefs[counter] = (prefs[counter] ?: 0) + 1 } }
        withTimeoutOrNull(300) { second.await() }
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(JsonPrimitive(2), stored())
    }

    @Test fun `an operation replays the transform on a value another owner wrote while it was staging`() = runBlocking {
        DesktopOriginalPlaybackPreferenceOperation.run(context, current::get) {
            settings.edit { it[counter] = (it[counter] ?: 0) + 1 }
            plugin.store.update("settings", mapOf("fixture_counter" to JsonPrimitive(10)))
        }
        assertEquals(JsonPrimitive(11), stored())
    }

    @Test fun `upstream search history keeps the query another owner recorded during the operation`() = runBlocking {
        DesktopOriginalPlaybackPreferenceOperation.run(context, current::get) {
            SettingsSearchHistoryStore.record(context.androidContext, "first")
            SettingsSearchHistoryStore.record(plugin.asAndroidContext(), "second")
        }
        assertEquals(setOf("first", "second"), SettingsSearchHistoryStore.observe(context.androidContext).first().toSet())
    }

    @Test fun `a transform that suspends inside an operation is cancelled, cleans up and never resumes`() = runBlocking {
        var pending: CancellableContinuation<Unit>? = null
        var cleanedUp = false
        var ranAfterRejection = false
        try {
            assertFailsWith<UnsupportedDesktopCapability> {
                DesktopOriginalPlaybackPreferenceOperation.run(context, current::get) {
                    settings.edit { prefs ->
                        try {
                            suspendCancellableCoroutine<Unit> { pending = it }
                            ranAfterRejection = true
                            prefs[counter] = 1
                        } finally { cleanedUp = true }
                    }
                }
            }
            val continuation = checkNotNull(pending)
            assertFalse(continuation.isActive, "the rejected transform is still suspended")
            assertTrue(continuation.isCancelled)
            assertTrue(cleanedUp, "the rejected transform's finally did not run")
            // A late resume of the cancelled continuation must not run the rest of the transform.
            continuation.resume(Unit)
            assertFalse(ranAfterRejection)
            assertNull(stored())
        } finally { pending?.cancel() }
    }

    @Test fun `a cancelled caller cancels the replayed transform instead of reporting a capability`() = runBlocking {
        val operation = launch {
            DesktopOriginalPlaybackPreferenceOperation.run(context, current::get) {
                val caller = coroutineContext.job
                settings.edit { prefs ->
                    caller.cancel()
                    suspendCancellableCoroutine<Unit> { }
                    prefs[counter] = 1
                }
            }
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertNull(stored())
    }

    @Test fun `a replayed transform leaves no child job running under the caller`() = runBlocking {
        lateinit var caller: Job
        DesktopOriginalPlaybackPreferenceOperation.run(context, current::get) {
            caller = coroutineContext.job
            // The operation's own coroutine is already a child; the edit must not leave another one behind.
            val before = caller.children.toSet()
            settings.edit { it[counter] = 1 }
            assertEquals(before, caller.children.toSet(), "a replayed transform's job was left running")
        }
        assertEquals(JsonPrimitive(1), stored())
    }
}
