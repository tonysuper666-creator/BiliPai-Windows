package com.bilipai.desktop.backup

import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.google.protobuf.CodedOutputStream
import com.bilipai.desktop.settings.DesktopSettingsShareService
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.ensureDesktopWindowsPlaybackDefaults
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DesktopAndroidSettingsBackupTest {
    @Test fun `actual Preferences protobuf scalar values retain signed numbers precision and byte identity`() {
        val values = DesktopAndroidSettingsBackup.preferences(preferences(
            "boolean" to value { writeBool(1, true) }, "float" to value { writeFloat(2, 1.25f) },
            "integer" to value { writeInt32(3, -5) }, "long" to value { writeInt64(4, 9_007_199_254_740_993L) },
            "string" to value { writeString(5, "中文 🎵") }, "double" to value { writeDouble(7, 1.125) },
            "bytes" to value { writeByteArray(8, byteArrayOf(0, -1, 42)) }))
        assertEquals(JsonPrimitive(true), values["boolean"])
        assertEquals(1.25f, values.getValue("float").jsonPrimitive.float)
        assertEquals(JsonPrimitive(-5), values["integer"])
        assertEquals(9_007_199_254_740_993L, values.getValue("long").jsonPrimitive.long)
        assertEquals("中文 🎵", values.getValue("string").jsonPrimitive.content)
        assertEquals(1.125, values.getValue("double").jsonPrimitive.double)
        assertEquals("AP8q", values.getValue("bytes").jsonObject.getValue("androidByteArrayBase64").jsonPrimitive.content)
    }

    @Test fun `original string sets preserve values and empty Preferences files remain valid`() {
        val values = DesktopAndroidSettingsBackup.preferences(preferences("set" to value {
            writeByteArray(6, value { writeString(1, "猫"); writeString(1, "狗"); writeString(1, "猫") })
        }))
        assertEquals(JsonArray(listOf(JsonPrimitive("猫"), JsonPrimitive("狗"))), values["set"])
        assertEquals(JsonObject(emptyMap()), DesktopAndroidSettingsBackup.preferences(byteArrayOf()))
    }

    @Test fun `malformed duplicate unknown nonfinite and invalid UTF8 protobuf values are rejected`() {
        val duplicates = preferences("same" to value { writeBool(1, true) }, "same" to value { writeBool(1, false) })
        val invalidUtf8 = preferences("text" to byteArrayOf(42, 1, -1))
        for (bytes in listOf(byteArrayOf(10, 4, 10), byteArrayOf(0), duplicates, invalidUtf8,
            preferences("bad" to value { writeBool(1, true); writeInt32(3, 1) }),
            preferences("bad" to value { writeString(9, "future") }),
            preferences("bad" to value { writeFloat(2, Float.NaN) }))) {
            assertFails { DesktopAndroidSettingsBackup.preferences(bytes) }
        }
    }

    @Test fun `Android XML registry retains boolean numeric string and string set types`() {
        val values = DesktopAndroidSettingsBackup.sharedPreferences("""<?xml version="1.0" encoding="utf-8"?>
            <map><boolean name="enabled" value="true"/><int name="version" value="3"/>
            <long name="time" value="9007199254740993"/><float name="weight" value="1.25"/>
            <string name="label">猫 &amp; 狗</string><set name="installed"><string>one</string><string>two</string></set></map>""".toByteArray())
        assertEquals(JsonPrimitive(true), values["enabled"])
        assertEquals(3, values.getValue("version").jsonPrimitive.int)
        assertEquals(9_007_199_254_740_993L, values.getValue("time").jsonPrimitive.long)
        assertEquals(1.25f, values.getValue("weight").jsonPrimitive.float)
        assertEquals("猫 & 狗", values.getValue("label").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))), values["installed"])
    }

    @Test fun `XML external entities duplicate names and malformed registry fail before the restore barrier`() {
        val root = Files.createTempDirectory("backup-android-xml-")
        var barriers = 0
        for (xml in listOf("<!DOCTYPE map [<!ENTITY leak SYSTEM 'file:///never-read'>]><map><string name='x'>&leak;</string></map>",
            "<map><boolean name='x' value='true'/><boolean name='x' value='false'/></map>",
            "<map><boolean name='x' value='maybe'/></map>", "<map><string name='x'><nested/></string></map>")) {
            assertFails { DesktopBackupArchive(root) { barriers++ }.restore(archive(mapOf(REGISTRY to xml.toByteArray()))) }
        }
        assertEquals(0, barriers)
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }

    @Test fun `complete original archive migrates through the actual sole store and preserves Windows account files`() {
        val root = Files.createTempDirectory("backup-android-root-")
        write(root, "plugin-settings.json", """{"settings":{"windows_ui":125,"show_online_count":true},"veyra_release_tracking":{"clock":9},"plugin_prefs":{"existing":true}}""")
        write(root, "session.json", """{"cookie":"unchanged"}""")
        write(root, "player-settings.json", """{"exclusiveAudio":true}""")
        val bytes = archive(linkedMapOf(
            SETTINGS to preferences("show_online_count" to value { writeBool(1, false) }),
            PLUGINS to preferences("plugin_enabled_test" to value { writeBool(1, true) }),
            REGISTRY to "<map><set name='installed'><string>notes</string></set></map>".toByteArray(),
            "json_plugins/notes.JSON" to """{"id":"notes","name":"笔记"}""".toByteArray()))
        assertEquals(2, DesktopBackupArchive(root).restore(bytes))
        val store = DesktopPluginStore(root)
        assertEquals(JsonPrimitive(false), store.preferences("settings")["show_online_count"])
        assertEquals(JsonPrimitive(125), store.preferences("settings")["windows_ui"])
        assertEquals(JsonPrimitive(9), store.preferences("veyra_release_tracking")["clock"])
        assertEquals(JsonPrimitive(true), store.preferences("plugin_prefs")["existing"])
        assertEquals(JsonPrimitive(true), store.preferences("plugin_prefs")["plugin_enabled_test"])
        assertEquals(setOf("notes"), DesktopPluginContext(store).getSharedPreferences("json_plugins", 0).getStringSet("installed", null))
        assertEquals("""{"id":"notes","name":"笔记"}""", Files.readString(root.resolve("json_plugins/notes.json")))
        assertEquals("""{"cookie":"unchanged"}""", Files.readString(root.resolve("session.json")))
        assertEquals("""{"exclusiveAudio":true}""", Files.readString(root.resolve("player-settings.json")))
    }

    @Test fun `final Windows write at the existing restore barrier is included in the merge`() {
        val root = Files.createTempDirectory("backup-android-barrier-")
        write(root, "plugin-settings.json", """{"settings":{"early":true}}""")
        var barriers = 0
        DesktopBackupArchive(root) {
            barriers++
            write(root, "plugin-settings.json", """{"settings":{"late":125},"update_clock":{"last":42}}""")
        }.restore(archive(mapOf(SETTINGS to preferences("auto_play" to value { writeBool(1, false) }))))
        val restored = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(1, barriers)
        assertEquals(JsonPrimitive(125), restored.getValue("settings").jsonObject["late"])
        assertEquals(JsonPrimitive(false), restored.getValue("settings").jsonObject["auto_play"])
        assertEquals(JsonPrimitive(42), restored.getValue("update_clock").jsonObject["last"])
    }

    @Test fun `malformed later plugin validates before any earlier preference or barrier changes`() {
        val root = Files.createTempDirectory("backup-android-tail-")
        write(root, "plugin-settings.json", "{}")
        var barriers = 0
        assertFails { DesktopBackupArchive(root) { barriers++ }.restore(archive(linkedMapOf(
            SETTINGS to preferences("auto_play" to value { writeBool(1, false) }), "json_plugins/bad.json" to byteArrayOf(123)))) }
        assertEquals(0, barriers)
        assertEquals("{}", Files.readString(root.resolve("plugin-settings.json")))
    }

    @Test fun `mixed Android Windows entries and unsupported manifest cannot bypass either format`() {
        val root = Files.createTempDirectory("backup-android-mixed-")
        assertFails { DesktopBackupArchive(root).restore(archive(mapOf("windows/library.json" to "{}".toByteArray()))) }
        assertFails { DesktopBackupArchive(root).restore(archive(mapOf(SETTINGS to byteArrayOf()),
            """{"format":"bilipai-windows-settings","schemaVersion":1,"files":[]}""")) }
        assertFails { DesktopBackupArchive(root).restore(archive(mapOf(SETTINGS to byteArrayOf()),
            """{"createdAtEpochMs":1,"scope":[]}""")) }
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }

    @Test fun `plugin extension aliases cannot silently replace the same imported Windows file`() {
        val root = Files.createTempDirectory("backup-android-alias-")
        assertFails { DesktopBackupArchive(root).restore(archive(linkedMapOf(
            "json_plugins/test.JSON" to "{}".toByteArray(), "json_plugins/test.json" to "{}".toByteArray()))) }
        assertFalse(Files.exists(root.resolve("json_plugins/test.json")))
    }

    @Test fun `Android archive paths cannot restore account credentials unknown files or traversal`() {
        for (path in listOf("session.json", "datastore/../session.json", "json_plugins/../session.json", "shared_prefs/accounts.xml", "json_plugins\\test.json")) {
            val root = Files.createTempDirectory("backup-android-path-")
            assertFails { DesktopBackupArchive(root).restore(archive(mapOf(path to "{}".toByteArray()))) }
            assertEquals(0, Files.list(root).use { it.count() })
        }
    }

    @Test fun `later file replacement failure rolls merged namespaces back to the actual old generation`() {
        val root = Files.createTempDirectory("backup-android-rollback-")
        val previous = """{"settings":{"old":true},"clock":{"last":5}}"""
        write(root, "plugin-settings.json", previous)
        Files.createDirectories(root.resolve("json_plugins/bad.json"))
        var completions = 0
        assertFails { DesktopBackupArchive(root).restore(archive(linkedMapOf(
            SETTINGS to preferences("auto_play" to value { writeBool(1, false) }), "json_plugins/bad.json" to "{}".toByteArray()))) { completions++ } }
        assertEquals(previous, Files.readString(root.resolve("plugin-settings.json")))
        assertEquals(0, completions)
    }

    @Test fun `the existing completion callback observes all committed files exactly once`() {
        val root = Files.createTempDirectory("backup-android-complete-")
        var completions = 0
        DesktopBackupArchive(root).restore(archive(linkedMapOf(SETTINGS to byteArrayOf(), "json_plugins/test.json" to "{}".toByteArray()))) {
            completions++
            assertTrue(Files.isRegularFile(root.resolve("plugin-settings.json")))
            assertEquals("{}", Files.readString(root.resolve("json_plugins/test.json")))
        }
        assertEquals(1, completions)
    }

    @Test fun `an empty original archive cannot retire the application without restoring any setting`() {
        val root = Files.createTempDirectory("backup-android-empty-")
        var barriers = 0
        assertFails { DesktopBackupArchive(root) { barriers++ }.restore(archive(emptyMap())) }
        assertEquals(0, barriers)
    }

    @Test fun `real Root startup reuses original setters for imported mirrors and consumes migration once`(): Unit = runBlocking {
        val root = Files.createTempDirectory("backup-android-mirrors-")
        write(root, "plugin-settings.json", """{"player_settings_cache":{"hw_decode_enabled":true},"quality_settings":{"wifi_quality":80,"mobile_quality":64}}""")
        DesktopBackupArchive(root).restore(archive(mapOf(SETTINGS to preferences(
            "hw_decode" to value { writeBool(1, false) }, "auto_highest_quality" to value { writeBool(1, false) },
            "wifi_default_quality" to value { writeInt32(3, 120) }, "mobile_default_quality" to value { writeInt32(3, 32) },
            "theme_mode_v2" to value { writeInt32(3, 2) }, "playback_speed_options" to value { writeString(5, "1.0,1.5") },
            "default_playback_speed" to value { writeFloat(2, 1.5f) }))))
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { action -> action(); true },
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        ensureDesktopWindowsPlaybackDefaults(context)
        assertEquals(JsonPrimitive(false), store.preferences("player_settings_cache")["hw_decode_enabled"])
        assertEquals(JsonPrimitive(false), store.preferences("settings")["auto_highest_quality"])
        assertEquals(JsonPrimitive(false), store.preferences("quality_settings")["auto_highest_quality"])
        assertEquals(JsonPrimitive(120), store.preferences("quality_settings")["wifi_quality"])
        assertEquals(JsonPrimitive(120), store.preferences("quality_settings")["mobile_quality"])
        assertEquals(JsonPrimitive(120), store.preferences("settings")["mobile_default_quality"])
        assertEquals(JsonPrimitive(2), store.preferences("theme_cache")["theme_mode"])
        assertEquals(1.5f, store.preferences("playback_speed_cache").getValue("default_speed").jsonPrimitive.float)
        assertEquals(JsonPrimitive(false), store.preferences("windows_android_backup_migration")["pending"])
        assertFalse("keys" in store.preferences("windows_android_backup_migration"))
        val bytes = Files.readAllBytes(root.resolve("plugin-settings.json"))
        ensureDesktopWindowsPlaybackDefaults(context)
        assertContentEquals(bytes, Files.readAllBytes(root.resolve("plugin-settings.json")))
    }

    @Test fun `a retired actual preference owner cannot consume migration or publish any cache`(): Unit = runBlocking {
        val root = Files.createTempDirectory("backup-android-owner-")
        DesktopBackupArchive(root).restore(archive(mapOf(SETTINGS to preferences("hw_decode" to value { writeBool(1, false) }))))
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { false }, { error("Retired owner cannot admit") })
        val bytes = Files.readAllBytes(root.resolve("plugin-settings.json"))
        assertFailsWith<kotlinx.coroutines.CancellationException> { DesktopSettingsShareService(context).reconcileRestoredAndroidBackup() }
        assertContentEquals(bytes, Files.readAllBytes(root.resolve("plugin-settings.json")))
        assertEquals(JsonPrimitive(true), store.preferences("windows_android_backup_migration")["pending"])
    }

    @Test fun `legacy Android theme keys use the same original theme import policy`(): Unit = runBlocking {
        val root = Files.createTempDirectory("backup-android-theme-")
        DesktopBackupArchive(root).restore(archive(mapOf(SETTINGS to preferences(
            "ui_preset" to value { writeInt32(3, 1) }, "android_native_variant_v1" to value { writeInt32(3, 1) }))))
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { action -> action(); true })
        DesktopSettingsShareService(context).reconcileRestoredAndroidBackup()
        assertEquals(com.android.purebilibili.core.theme.AppUiStyle.fromLegacyValues(1, 1).name,
            store.preferences("settings").getValue("theme_selection_v1").jsonPrimitive.content)
        assertFalse("ui_preset" in store.preferences("settings"))
        assertFalse("android_native_variant_v1" in store.preferences("settings"))
    }

    @Test fun `failed actual Root permit keeps canonical mirrors and pending migration uncommitted`(): Unit = runBlocking {
        val root = Files.createTempDirectory("backup-android-permit-")
        write(root, "plugin-settings.json", """{"player_settings_cache":{"hw_decode_enabled":true}}""")
        DesktopBackupArchive(root).restore(archive(mapOf(SETTINGS to preferences("hw_decode" to value { writeBool(1, false) }))))
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { false })
        val bytes = Files.readAllBytes(root.resolve("plugin-settings.json"))
        assertFailsWith<kotlinx.coroutines.CancellationException> { DesktopSettingsShareService(context).reconcileRestoredAndroidBackup() }
        assertContentEquals(bytes, Files.readAllBytes(root.resolve("plugin-settings.json")))
        assertEquals(JsonPrimitive(true), store.preferences("player_settings_cache")["hw_decode_enabled"])
        assertEquals(JsonPrimitive(true), store.preferences("windows_android_backup_migration")["pending"])
    }

    private fun value(write: CodedOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { output ->
        CodedOutputStream.newInstance(output).apply { write(); flush() }
    }.toByteArray()
    private fun preferences(vararg values: Pair<String, ByteArray>): ByteArray = value {
        values.forEach { (key, bytes) -> writeByteArray(1, value { writeString(1, key); writeByteArray(2, bytes) }) }
    }
    private fun archive(files: Map<String, ByteArray>, manifest: String = MANIFEST): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            (linkedMapOf("manifest.json" to manifest.toByteArray()) + files).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun write(root: Path, name: String, value: String) {
        val path = root.resolve(name); Files.createDirectories(path.parent); Files.writeString(path, value)
    }
    companion object {
        private const val SETTINGS = "datastore/settings_prefs.preferences_pb"
        private const val PLUGINS = "datastore/plugin_prefs.preferences_pb"
        private const val REGISTRY = "shared_prefs/json_plugins.xml"
        private const val MANIFEST = """{"createdAtEpochMs":1700000000000,"createdAtIso":"2023-11-14T22:13:20Z","appVersion":"0.4.4","scope":["datastore/settings_prefs.preferences_pb","datastore/plugin_prefs.preferences_pb","shared_prefs/json_plugins.xml","json_plugins/*.json"]}"""
    }
}
