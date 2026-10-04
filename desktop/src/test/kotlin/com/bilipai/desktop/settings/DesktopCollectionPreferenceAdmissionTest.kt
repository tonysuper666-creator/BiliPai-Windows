package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DesktopOriginalCollectionSettings
import com.android.purebilibili.feature.video.ui.components.CollectionSortMode
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DesktopCollectionPreferenceAdmissionTest {
    @TempDir lateinit var directory: Path

    @Test fun originalSortAndSubscriptionPersistThroughSameStoreAndColdFacade() = runBlocking {
        val store = DesktopPluginStore(directory)
        val context = DesktopPluginContext(store)
        store.update("unrelated", mapOf("keep" to JsonPrimitive("value")))
        DesktopCollectionPreferenceWriteOperation.withOwned(context, { true }, { action -> action(); true }) {
            DesktopOriginalCollectionSettings.setCollectionSortMode(context, 7L, CollectionSortMode.DESCENDING)
            DesktopOriginalCollectionSettings.setCollectionSortMode(context, 8L, CollectionSortMode.RECENT)
            DesktopOriginalCollectionSettings.setCollectionSubscription(context, 7L, true)
        }
        assertEquals(CollectionSortMode.DESCENDING, DesktopOriginalCollectionSettings.getCollectionSortMode(context, 7L).first())
        assertTrue(DesktopOriginalCollectionSettings.isCollectionSubscribed(context, 7L).first())
        store.freezeWrites()
        val cold = DesktopPluginContext(DesktopPluginStore(directory))
        assertEquals(CollectionSortMode.DESCENDING, DesktopOriginalCollectionSettings.getCollectionSortMode(cold, 7L).first())
        assertEquals(CollectionSortMode.RECENT, DesktopOriginalCollectionSettings.getCollectionSortMode(cold, 8L).first())
        assertTrue(DesktopOriginalCollectionSettings.isCollectionSubscribed(cold, 7L).first())
        assertEquals(JsonPrimitive("value"), cold.store.preferences("unrelated")["keep"])
    }

    @Test fun uncapturedOriginalSetterFailsClosedWithoutCreatingFile() = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(directory))
        assertFailsWith<IllegalStateException> {
            DesktopOriginalCollectionSettings.setCollectionSortMode(context, 7L, CollectionSortMode.RECENT)
        }
        assertFalse(Files.exists(directory.resolve("plugin-settings.json")))
    }

    @Test fun retirementDuringPureEditCannotReplaceFileOrPublishFlow() = runBlocking {
        val store = DesktopPluginStore(directory)
        val context = DesktopPluginContext(store)
        store.update("settings", mapOf("existing" to JsonPrimitive("keep")))
        val before = Files.readAllBytes(directory.resolve("plugin-settings.json"))
        val flowBefore = store.snapshot("settings").value
        val alive = AtomicBoolean(true)
        assertFailsWith<CancellationException> {
            DesktopCollectionPreferenceWriteOperation.withOwned(context, alive::get, { action -> action(); true }) {
                context.collectionSettingsDataStore.edit { editor ->
                    editor[stringPreferencesKey("collection_sort_preferences")] = "{}"
                    alive.set(false)
                }
            }
        }
        assertContentEquals(before, Files.readAllBytes(directory.resolve("plugin-settings.json")))
        assertSame(flowBefore, store.snapshot("settings").value)
        assertEquals(JsonPrimitive("keep"), store.preferences("settings")["existing"])
    }

    @Test fun rejectedRootPermitCannotPublishOptimisticSort() = runBlocking {
        val store = DesktopPluginStore(directory)
        val context = DesktopPluginContext(store)
        assertFailsWith<CancellationException> {
            DesktopCollectionPreferenceWriteOperation.withOwned(context, { true }, { false }) {
                DesktopOriginalCollectionSettings.setCollectionSortMode(context, 7L, CollectionSortMode.RECENT)
            }
        }
        assertFalse(Files.exists(directory.resolve("plugin-settings.json")))
        assertEquals(CollectionSortMode.ASCENDING, DesktopOriginalCollectionSettings.getCollectionSortMode(context, 7L).first())
    }
}
