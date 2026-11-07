package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.DesktopOriginalPlaybackPreferenceOperation
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class DesktopPartitionPreferencesTest {
    @Test fun defaultSharedUpdatesAndColdDiskRestore(): Unit = runBlocking {
        val root = Files.createTempDirectory("partition-preference-")
        val cold = Files.createTempDirectory("partition-cold-")
        try {
            val store = DesktopPluginStore(root)
            val context = DesktopPluginContext(store)
            val preferences = DesktopPartitionPreferences(context)
            val other = DesktopPartitionPreferences(DesktopPluginContext(DesktopPluginStore(root)))
            assertFalse(preferences.initialSideRailOnRight())
            assertFalse(Files.exists(root.resolve("plugin-settings.json")))
            store.update("settings", mapOf("unrelated" to JsonPrimitive(42)))
            val owner = DesktopOriginalPlayerSettingsContext(context, { true }, { it(); true })
            DesktopOriginalPlaybackPreferenceOperation.run(owner, { true }) {
                preferences.setSideRailOnRight(true, owner)
            }
            assertTrue(withTimeout(1000) { other.onRight.first() })
            assertEquals(JsonPrimitive(42), store.preferences("settings")["unrelated"])
            Files.copy(root.resolve("plugin-settings.json"), cold.resolve("plugin-settings.json"))
            val restored = DesktopPartitionPreferences(DesktopPluginContext(DesktopPluginStore(cold)))
            assertTrue(restored.initialSideRailOnRight(), "Fresh backing reads persisted original key")
            owner.getSharedPreferences("settings", 0).edit().putBoolean("partition_side_rail_on_right", false).apply()
            assertFalse(withTimeout(1000) { preferences.onRight.first() })
            assertTrue(restored.initialSideRailOnRight(), "Separate cold backing is independent")
        } finally {
            root.toFile().deleteRecursively(); cold.toFile().deleteRecursively()
        }
    }

    @Test fun invalidScalarDefaultsWithoutRewritingAndRetiredOrFrozenOwnerCannotPublish(): Unit = runBlocking {
        val root = Files.createTempDirectory("partition-admission-")
        try {
            val store = DesktopPluginStore(root)
            store.update("settings", mapOf("partition_side_rail_on_right" to JsonPrimitive("garbage")))
            val context = DesktopPluginContext(store)
            val preferences = DesktopPartitionPreferences(context)
            val before = Files.readAllBytes(root.resolve("plugin-settings.json"))
            assertFalse(preferences.initialSideRailOnRight())
            val retired = DesktopOriginalPlayerSettingsContext(context, { false }, { false })
            assertFailsWith<CancellationException> { preferences.setSideRailOnRight(true, retired) }
            assertContentEquals(before, Files.readAllBytes(root.resolve("plugin-settings.json")))
            assertFalse(preferences.initialSideRailOnRight())
            store.freezeWrites()
            val owner = DesktopOriginalPlayerSettingsContext(context, { true }, { it(); true })
            assertFailsWith<IllegalStateException> { preferences.setSideRailOnRight(true, owner) }
            assertContentEquals(before, Files.readAllBytes(root.resolve("plugin-settings.json")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun failedDiskReplacementCannotPublish(): Unit = runBlocking {
        val root = Files.createTempDirectory("partition-io-")
        try {
            val store = DesktopPluginStore(root)
            val context = DesktopPluginContext(store)
            val preferences = DesktopPartitionPreferences(context)
            // A nonempty directory at the exact final filename forces atomic replacement to fail.
            Files.createDirectory(root.resolve("plugin-settings.json"))
            Files.writeString(root.resolve("plugin-settings.json/occupied"), "sentinel")
            val owner = DesktopOriginalPlayerSettingsContext(context, { true }, { it(); true })
            assertFails { preferences.setSideRailOnRight(true, owner) }
            assertFalse(preferences.initialSideRailOnRight())
            assertEquals("sentinel", Files.readString(root.resolve("plugin-settings.json/occupied")))
        } finally { root.toFile().deleteRecursively() }
    }
}
