package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DataStoreAicuConsentStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Real atomic settings backing, scoped to a temporary directory rather than any user profile. */
class DesktopAicuConsentStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `original consent integer persists in the same global namespace and rejects a retired owner`() = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(directory))
        val current = AtomicBoolean(true)
        fun adapter(pluginContext: DesktopPluginContext) = DesktopOriginalPlayerSettingsContext(pluginContext,
            current::get, { block -> if (current.get()) { block(); true } else false })
        val store = DataStoreAicuConsentStore(adapter(context))
        assertEquals(0, store.acceptedVersion())
        store.accept(1)
        assertEquals(JsonPrimitive(1), context.store.preferences("settings")["aicu_disclaimer_accepted_version"])
        val persisted = Files.readAllBytes(directory.resolve("plugin-settings.json"))
        assertEquals(1, Json.parseToJsonElement(persisted.decodeToString()).jsonObject["settings"]!!
            .jsonObject["aicu_disclaimer_accepted_version"]!!.jsonPrimitive.int)
        val coldDirectory = Files.createDirectory(directory.resolve("cold"))
        Files.write(coldDirectory.resolve("plugin-settings.json"), persisted)
        assertEquals(1, DataStoreAicuConsentStore(adapter(DesktopPluginContext(DesktopPluginStore(coldDirectory)))).acceptedVersion())
        val sameBacking = DesktopPluginContext(DesktopPluginStore(directory))
        assertEquals(1, DataStoreAicuConsentStore(adapter(sameBacking)).acceptedVersion())
        current.set(false)
        assertFailsWith<CancellationException> { store.accept(2) }
        assertEquals(JsonPrimitive(1), context.store.preferences("settings")["aicu_disclaimer_accepted_version"])
        assertContentEquals(persisted, Files.readAllBytes(directory.resolve("plugin-settings.json")))
        current.set(true)
        assertEquals(1, DataStoreAicuConsentStore(adapter(DesktopPluginContext(DesktopPluginStore(directory)))).acceptedVersion())
    }
}
