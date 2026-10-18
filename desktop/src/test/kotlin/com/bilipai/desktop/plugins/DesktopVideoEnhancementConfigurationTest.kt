package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.bilipai.desktop.player.DesktopNvidiaVideoHdrMode
import com.bilipai.desktop.player.DesktopNvidiaVideoContent
import com.bilipai.desktop.player.DesktopNvidiaVideoPreferences
import com.bilipai.desktop.player.DesktopNvidiaVideoQuality
import com.bilipai.desktop.player.DesktopNvidiaVideoIntensity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DesktopVideoEnhancementConfigurationTest {
    @Test fun `effect intensity persists with independent quality and frozen writes stay atomic`(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        val config = configuration(store)
        try {
            withTimeout(5000) {
                config.setQuality(DesktopNvidiaVideoQuality.HIGH).await()
                config.setHdrMode(DesktopNvidiaVideoHdrMode.OFF).await()
                config.setAutomaticEnabled(false).await()
            }
            assertEquals(DesktopNvidiaVideoIntensity.FULL, config.preferences.value.intensity)
            assertEquals(listOf(50, 75, 100), DesktopNvidiaVideoIntensity.entries.map { it.nativePercent })
            for (intensity in DesktopNvidiaVideoIntensity.entries) {
                withTimeout(5000) { config.setIntensity(intensity).await() }
                val expected = config.preferences.value
                assertEquals(intensity, expected.intensity)
                assertEquals(DesktopNvidiaVideoQuality.HIGH, expected.quality)
                assertEquals(DesktopNvidiaVideoHdrMode.OFF, expected.hdrMode)
                assertFalse(expected.enabled)
                val namespace = document()["windows_video_enhancement"]!!.jsonObject
                assertEquals(intensity.nativePercent, namespace["effect_intensity_percent"]!!.jsonPrimitive.int)
                assertEquals(3, namespace["quality_level"]!!.jsonPrimitive.int)
                val coldRoot = Files.createDirectory(root.resolve("intensity-${intensity.nativePercent}-cold"))
                Files.copy(root.resolve("plugin-settings.json"), coldRoot.resolve("plugin-settings.json"))
                val cold = configuration(DesktopPluginStore(coldRoot))
                withTimeout(5000) { cold.flushAndClose() }
                assertEquals(expected, cold.preferences.value)
            }
            val before = config.preferences.value
            val saved = document()
            store.freezeWrites()
            assertFailsWith<IllegalStateException> {
                withTimeout(5000) { config.setIntensity(DesktopNvidiaVideoIntensity.GENTLE).await() }
            }
            assertEquals(before, config.preferences.value)
            assertEquals(saved, document())
            assertNotNull(config.error.value)
        } finally { withTimeout(5000) { config.flushAndClose() } }
    }

    @Test fun `quality and HDR writes merge in queue order and drain on retirement`(): Unit = runBlocking {
        val accepting = AtomicBoolean(true)
        val config = configuration(accepting = accepting::get)
        val requests = listOf(config.setQuality(DesktopNvidiaVideoQuality.STANDARD),
            config.setHdrMode(DesktopNvidiaVideoHdrMode.OFF), config.setAutomaticEnabled(false),
            config.setQuality(DesktopNvidiaVideoQuality.HIGH))
        accepting.set(false)
        assertFailsWith<IllegalStateException> { config.setQuality(DesktopNvidiaVideoQuality.HIGHEST) }
        withTimeout(5000) { config.flushAndClose(); requests.forEach { it.await() } }
        assertEquals(DesktopNvidiaVideoPreferences(false, DesktopNvidiaVideoQuality.HIGH, DesktopNvidiaVideoHdrMode.OFF),
            config.preferences.value)
        val namespace = document()["windows_video_enhancement"]!!.jsonObject
        assertEquals(3, namespace["quality_level"]!!.jsonPrimitive.int)
        assertEquals("off", namespace["hdr_mode"]!!.jsonPrimitive.content)
        val coldRoot = Files.createDirectory(root.resolve("quality-cold-store"))
        Files.copy(root.resolve("plugin-settings.json"), coldRoot.resolve("plugin-settings.json"))
        val cold = configuration(DesktopPluginStore(coldRoot))
        withTimeout(5000) { cold.flushAndClose() }
        assertEquals(config.preferences.value, cold.preferences.value)
    }

    @Test fun `existing migration keeps off and defaults missing quality and carries HDR automatic in memory without rewriting disk`(): Unit = runBlocking {
        val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":false},"unrelated":{"keep":7}}"""
        Files.writeString(root.resolve("plugin-settings.json"), original)
        val config = configuration()
        withTimeout(5000) { config.flushAndClose() }
        // A store with no explicit choice matches the untouched-seed signature: HDR automatic is effective in memory only.
        assertEquals(DesktopNvidiaVideoPreferences(false, DesktopNvidiaVideoQuality.HIGHEST, DesktopNvidiaVideoHdrMode.AUTO),
            config.preferences.value)
        assertFalse(config.automaticEnabled.value)
        assertEquals(Json.parseToJsonElement(original), document())
    }

    @Test fun `invalid quality or HDR stays disabled and is never overwritten`(): Unit = runBlocking {
        for ((index, field) in listOf("\"quality_level\":\"4\"", "\"quality_level\":0", "\"quality_level\":5",
            "\"hdr_mode\":true", "\"hdr_mode\":\"unknown\"",
            "\"effect_intensity_percent\":\"100\"", "\"effect_intensity_percent\":51", "\"effect_intensity_percent\":true").withIndex()) {
            val path = Files.createDirectory(root.resolve("invalid-$index"))
            val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":true,$field}}"""
            Files.writeString(path.resolve("plugin-settings.json"), original)
            val config = configuration(DesktopPluginStore(path))
            assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setHdrMode(DesktopNvidiaVideoHdrMode.OFF).await() } }
            assertFalse(config.preferences.value.enabled)
            assertNotNull(config.error.value)
            assertEquals(Json.parseToJsonElement(original), document(path))
            withTimeout(5000) { config.flushAndClose() }
        }
    }

    @Test fun `frozen quality write does not optimistically change the atomic preferences`(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        val config = configuration(store)
        withTimeout(5000) { config.setQuality(DesktopNvidiaVideoQuality.HIGHEST).await() }
        val before = config.preferences.value
        val saved = document()
        store.freezeWrites()
        assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setQuality(DesktopNvidiaVideoQuality.STANDARD).await() } }
        assertEquals(before, config.preferences.value)
        assertEquals(saved, document())
        assertNotNull(config.error.value)
        withTimeout(5000) { config.flushAndClose() }
    }

    @TempDir lateinit var root: Path
    private fun configuration(store: DesktopPluginStore = DesktopPluginStore(root),
        accepting: () -> Boolean = { true }) = DesktopVideoEnhancementConfiguration(store,
            dispatcher = Dispatchers.Default, acceptChanges = accepting)
    private fun document(path: Path = root): JsonObject =
        Json.parseToJsonElement(Files.readString(path.resolve("plugin-settings.json"))).jsonObject

    @Test fun `first Windows migration enables NVIDIA without changing legacy JSON or other settings`(): Unit = runBlocking {
        val original = Json.parseToJsonElement("""{"plugin_prefs":{"plugin_enabled_anime4k":false,"plugin_config_anime4k":"{\"algorithm\":\"FSR_1_0\",\"rememberedEnabled\":false}"},"unrelated":{"keep":7}}""").jsonObject
        Files.writeString(root.resolve("plugin-settings.json"), original.toString())
        val config = configuration()
        withTimeout(5000) { config.flushAndClose() }
        assertTrue(config.automaticEnabled.value)
        val saved = document()
        assertEquals(original["plugin_prefs"], saved["plugin_prefs"])
        assertEquals(original["unrelated"], saved["unrelated"])
        val seeded = saved["windows_video_enhancement"]!!.jsonObject
        assertEquals(1, seeded["migration_version"]!!.jsonPrimitive.int)
        assertTrue(seeded["enabled"]!!.jsonPrimitive.boolean)
        // The fresh seed: HDR automatic, the stamped profile version, every other value the old default.
        assertEquals(4, seeded["quality_level"]!!.jsonPrimitive.int)
        assertEquals("auto", seeded["hdr_mode"]!!.jsonPrimitive.content)
        assertTrue(seeded["sr_enabled"]!!.jsonPrimitive.boolean)
        assertEquals(100, seeded["effect_intensity_percent"]!!.jsonPrimitive.int)
        assertEquals(2, seeded["profile_version"]!!.jsonPrimitive.int)
        assertEquals(setOf("migration_version", "enabled", "quality_level", "hdr_mode", "sr_enabled",
            "effect_intensity_percent", "profile_version"), seeded.keys)
        assertEquals(DesktopNvidiaVideoPreferences(true, DesktopNvidiaVideoQuality.HIGHEST, DesktopNvidiaVideoHdrMode.AUTO),
            config.preferences.value)
    }

    @Test fun `manual off survives independent persisted Store reload and startup migration`(): Unit = runBlocking {
        val first = configuration()
        withTimeout(5000) { first.setAutomaticEnabled(false).await(); first.flushAndClose() }
        val coldRoot = Files.createDirectory(root.resolve("independent-cold-store"))
        Files.copy(root.resolve("plugin-settings.json"), coldRoot.resolve("plugin-settings.json"))
        val reloaded = configuration(DesktopPluginStore(coldRoot))
        withTimeout(5000) { reloaded.flushAndClose() }
        assertFalse(reloaded.automaticEnabled.value)
        assertFalse(document(coldRoot)["windows_video_enhancement"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `queued user intent is serialized and accepted writes drain after shutdown starts`(): Unit = runBlocking {
        val accepting = AtomicBoolean(true)
        val config = configuration(accepting = accepting::get)
        val requests = listOf(false, true, false).map(config::setAutomaticEnabled)
        accepting.set(false)
        assertFailsWith<IllegalStateException> { config.setAutomaticEnabled(true) }
        withTimeout(5000) { config.flushAndClose(); requests.forEach { it.await() } }
        assertFalse(config.automaticEnabled.value)
        assertFalse(document()["windows_video_enhancement"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertFailsWith<IllegalStateException> { config.setAutomaticEnabled(true) }
    }

    @Test fun `frozen Store write fails visibly without optimistic preference publication`(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        val config = configuration(store)
        withTimeout(5000) { config.setAutomaticEnabled(true).await() }
        store.freezeWrites()
        assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setAutomaticEnabled(false).await() } }
        assertTrue(config.automaticEnabled.value)
        assertNotNull(config.error.value)
        assertTrue(document()["windows_video_enhancement"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        withTimeout(5000) { config.flushAndClose() }
    }

    @Test fun `legacy algorithm selection cannot mutate Windows intent or reinstall old configuration`(): Unit = runBlocking {
        val config = configuration()
        withTimeout(5000) { config.setAutomaticEnabled(false).await() }
        val before = document()
        assertFailsWith<IllegalStateException> { config.setAlgorithm(VideoEnhancementAlgorithm.FSR_1_0).await() }
        assertFalse(config.automaticEnabled.value)
        assertEquals(before, document())
        assertNotNull(config.error.value)
        withTimeout(5000) { config.flushAndClose() }
    }

    @Test fun `invalid migrated value stays disabled with visible error and is not silently overwritten`(): Unit = runBlocking {
        val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":"true"}}"""
        Files.writeString(root.resolve("plugin-settings.json"), original)
        val config = configuration()
        assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setAutomaticEnabled(false).await() } }
        assertFalse(config.automaticEnabled.value)
        assertNotNull(config.error.value)
        assertEquals(Json.parseToJsonElement(original), document())
        withTimeout(5000) { config.flushAndClose() }
    }
    @Test fun oldHdrAutoMapsToCombinedContentWithoutRewritingDisk() = runBlocking<Unit> {
        val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":true,"hdr_mode":"auto","quality_level":3}}"""
        Files.writeString(root.resolve("plugin-settings.json"), original)
        val config = configuration()
        withTimeout(5000) { config.flushAndClose() }
        assertEquals(DesktopNvidiaVideoContent.CLARITY_AND_HDR, config.preferences.value.content)
        assertTrue(config.preferences.value.srEnabled)
        assertEquals(DesktopNvidiaVideoQuality.HIGH, config.preferences.value.quality)
        assertEquals(Json.parseToJsonElement(original), document())
    }

    @Test fun contentWritesAreAtomicPreserveQualityAndDrainAtRetirement() = runBlocking<Unit> {
        val accepting = AtomicBoolean(true)
        val config = configuration(accepting = accepting::get)
        val queued = listOf(config.setQuality(DesktopNvidiaVideoQuality.STANDARD),
            config.setContent(DesktopNvidiaVideoContent.CLARITY_AND_HDR),
            config.setContent(DesktopNvidiaVideoContent.HDR_ONLY))
        accepting.set(false)
        assertFailsWith<IllegalStateException> { config.setContent(DesktopNvidiaVideoContent.CLARITY) }
        withTimeout(5000) { config.flushAndClose(); queued.forEach { it.await() } }
        assertEquals(DesktopNvidiaVideoContent.HDR_ONLY, config.preferences.value.content)
        assertFalse(config.preferences.value.srEnabled)
        assertEquals(DesktopNvidiaVideoQuality.STANDARD, config.preferences.value.quality)
        val saved = document()["windows_video_enhancement"]!!.jsonObject
        assertFalse(saved["sr_enabled"]!!.jsonPrimitive.boolean)
        assertEquals("auto", saved["hdr_mode"]!!.jsonPrimitive.content)
        assertEquals(2, saved["quality_level"]!!.jsonPrimitive.int)
        val cold = Files.createDirectory(root.resolve("content-cold"))
        Files.copy(root.resolve("plugin-settings.json"), cold.resolve("plugin-settings.json"))
        val reloaded = configuration(DesktopPluginStore(cold))
        withTimeout(5000) { reloaded.flushAndClose() }
        assertEquals(config.preferences.value, reloaded.preferences.value)
    }

    @Test fun legacyHdrOffNormalizesHdrOnlyToClarityInOneSavedSnapshot() = runBlocking<Unit> {
        val config = configuration()
        withTimeout(5000) {
            config.setContent(DesktopNvidiaVideoContent.HDR_ONLY).await()
            config.setHdrMode(DesktopNvidiaVideoHdrMode.OFF).await()
            config.flushAndClose()
        }
        assertEquals(DesktopNvidiaVideoContent.CLARITY, config.preferences.value.content)
        val saved = document()["windows_video_enhancement"]!!.jsonObject
        assertTrue(saved["sr_enabled"]!!.jsonPrimitive.boolean)
        assertEquals("off", saved["hdr_mode"]!!.jsonPrimitive.content)
    }

    @Test fun malformedSrOrEmptyEffectsNeverOverwriteTheSavedDocument() = runBlocking<Unit> {
        for ((index, field) in listOf("\"sr_enabled\":\"false\",\"hdr_mode\":\"auto\"",
            "\"sr_enabled\":0,\"hdr_mode\":\"auto\"",
            "\"sr_enabled\":false,\"hdr_mode\":\"off\"").withIndex()) {
            val path = Files.createDirectory(root.resolve("invalid-content-$index"))
            val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":true,$field}}"""
            Files.writeString(path.resolve("plugin-settings.json"), original)
            val config = configuration(DesktopPluginStore(path))
            assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setContent(DesktopNvidiaVideoContent.CLARITY).await() } }
            assertFalse(config.preferences.value.enabled)
            assertNotNull(config.error.value)
            assertEquals(Json.parseToJsonElement(original), document(path))
            withTimeout(5000) { config.flushAndClose() }
        }
    }

    @Test fun frozenContentWriteCannotPublishHalfOfTheRequestedMode() = runBlocking<Unit> {
        val store = DesktopPluginStore(root)
        val config = configuration(store)
        withTimeout(5000) { config.setContent(DesktopNvidiaVideoContent.CLARITY).await() }
        val saved = document()
        val before = config.preferences.value
        store.freezeWrites()
        assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setContent(DesktopNvidiaVideoContent.HDR_ONLY).await() } }
        assertEquals(before, config.preferences.value)
        assertEquals(saved, document())
        withTimeout(5000) { config.flushAndClose() }
    }


    @Test fun `the root switches and the profile version are the documented values`() {
        assertTrue(SEED_FRESH_STORE_HDR_AUTO)
        assertTrue(EFFECTIVE_HDR_AUTO_FOR_UNTOUCHED_SEED)
        assertEquals(2, DesktopVideoEnhancementConfiguration.PROFILE_VERSION_CURRENT)
        assertEquals("profile_version", DesktopVideoEnhancementConfiguration.PROFILE_VERSION_KEY)
        // The in-memory default before any load is unchanged: off and HDR off.
        assertEquals(DesktopNvidiaVideoPreferences(false, DesktopNvidiaVideoQuality.HIGHEST, DesktopNvidiaVideoHdrMode.OFF),
            DesktopNvidiaVideoPreferences())
    }

    @Test fun `an untouched old seed carries HDR automatic in memory only and never changes the switch or the disk`(): Unit = runBlocking {
        val untouched = listOf(
            """{"migration_version":1,"enabled":false,"quality_level":4,"hdr_mode":"off","sr_enabled":true,"effect_intensity_percent":100}""",
            """{"migration_version":1,"enabled":true,"quality_level":4,"hdr_mode":"off","sr_enabled":true,"effect_intensity_percent":100}""",
            """{"migration_version":1,"enabled":true}""",
            """{"migration_version":1,"enabled":false,"hdr_mode":"off"}""")
        for ((index, namespace) in untouched.withIndex()) {
            val path = Files.createDirectory(root.resolve("untouched-$index"))
            val original = """{"windows_video_enhancement":$namespace,"unrelated":{"keep":7}}"""
            Files.writeString(path.resolve("plugin-settings.json"), original)
            val config = configuration(DesktopPluginStore(path))
            withTimeout(5000) { config.flushAndClose() }
            val shown = config.preferences.value
            assertEquals(DesktopNvidiaVideoHdrMode.AUTO, shown.hdrMode, "row $index")
            assertEquals(DesktopNvidiaVideoContent.CLARITY_AND_HDR, shown.content, "row $index")
            assertEquals(namespace.contains("\"enabled\":true"), shown.enabled, "row $index")
            assertEquals(shown.enabled, config.automaticEnabled.value, "row $index")
            assertNull(config.error.value, "row $index")
            assertEquals(Json.parseToJsonElement(original), document(path), "row $index")
        }
    }

    @Test fun `any stored choice or a stamped profile keeps the stored HDR mode`(): Unit = runBlocking {
        val authoritative = listOf(
            """{"migration_version":1,"enabled":true,"quality_level":4,"hdr_mode":"off","sr_enabled":true,"effect_intensity_percent":100,"profile_version":2}""",
            """{"migration_version":1,"enabled":true,"hdr_mode":"off","profile_version":3}""",
            """{"migration_version":1,"enabled":true,"quality_level":3,"hdr_mode":"off"}""",
            """{"migration_version":1,"enabled":true,"hdr_mode":"off","effect_intensity_percent":75}""")
        for ((index, namespace) in authoritative.withIndex()) {
            val path = Files.createDirectory(root.resolve("authoritative-$index"))
            val original = """{"windows_video_enhancement":$namespace}"""
            Files.writeString(path.resolve("plugin-settings.json"), original)
            val config = configuration(DesktopPluginStore(path))
            withTimeout(5000) { config.flushAndClose() }
            assertEquals(DesktopNvidiaVideoHdrMode.OFF, config.preferences.value.hdrMode, "row $index")
            assertEquals(DesktopNvidiaVideoContent.CLARITY, config.preferences.value.content, "row $index")
            assertEquals(Json.parseToJsonElement(original), document(path), "row $index")
        }
    }

    @Test fun `the profile version is lenient and never throws or disables the feature`(): Unit = runBlocking {
        // Anything that is not a whole number of at least 1 counts as the version before any stamp.
        val invalid = listOf("\"2\"", "true", "0", "-4", "2.5", "null", "[]", "{}", "99999999999")
        for ((index, value) in invalid.withIndex()) {
            val path = Files.createDirectory(root.resolve("lenient-$index"))
            val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":true,"profile_version":$value}}"""
            Files.writeString(path.resolve("plugin-settings.json"), original)
            val config = configuration(DesktopPluginStore(path))
            withTimeout(5000) { config.flushAndClose() }
            assertNull(config.error.value, "profile_version=$value")
            assertTrue(config.preferences.value.enabled, "profile_version=$value")
            assertEquals(DesktopNvidiaVideoHdrMode.AUTO, config.preferences.value.hdrMode, "profile_version=$value")
            assertEquals(Json.parseToJsonElement(original), document(path), "profile_version=$value")
        }
    }

    @Test fun `the first save of an untouched old seed writes the effective values and stamps the profile version`(): Unit = runBlocking {
        val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":false,"quality_level":4,"hdr_mode":"off","sr_enabled":true,"effect_intensity_percent":100},"unrelated":{"keep":7}}"""
        Files.writeString(root.resolve("plugin-settings.json"), original)
        val config = configuration()
        try {
            withTimeout(5000) { config.setAutomaticEnabled(true).await() }
            val saved = document()["windows_video_enhancement"]!!.jsonObject
            assertTrue(saved["enabled"]!!.jsonPrimitive.boolean)
            assertEquals("auto", saved["hdr_mode"]!!.jsonPrimitive.content)
            assertEquals(2, saved["profile_version"]!!.jsonPrimitive.int)
            assertEquals(1, saved["migration_version"]!!.jsonPrimitive.int)
            assertEquals(Json.parseToJsonElement(original).jsonObject["unrelated"], document()["unrelated"])
            // From now on the stored value is authoritative: an explicit off survives a cold reload.
            withTimeout(5000) { config.setHdrMode(DesktopNvidiaVideoHdrMode.OFF).await() }
            val explicit = document()["windows_video_enhancement"]!!.jsonObject
            assertEquals("off", explicit["hdr_mode"]!!.jsonPrimitive.content)
            assertEquals(2, explicit["profile_version"]!!.jsonPrimitive.int)
            val coldRoot = Files.createDirectory(root.resolve("effective-cold"))
            Files.copy(root.resolve("plugin-settings.json"), coldRoot.resolve("plugin-settings.json"))
            val cold = configuration(DesktopPluginStore(coldRoot))
            withTimeout(5000) { cold.flushAndClose() }
            assertEquals(DesktopNvidiaVideoHdrMode.OFF, cold.preferences.value.hdrMode)
            assertEquals(config.preferences.value, cold.preferences.value)
        } finally { withTimeout(5000) { config.flushAndClose() } }
    }

    @Test fun `unknown future keys are ignored on load and kept by a save`(): Unit = runBlocking {
        val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":true,"future_key":5,"future_flag":{"a":[1]}}}"""
        Files.writeString(root.resolve("plugin-settings.json"), original)
        val config = configuration()
        try {
            withTimeout(5000) { config.setIntensity(DesktopNvidiaVideoIntensity.GENTLE).await() }
            assertNull(config.error.value)
            val saved = document()["windows_video_enhancement"]!!.jsonObject
            assertEquals(5, saved["future_key"]!!.jsonPrimitive.int)
            assertNotNull(saved["future_flag"])
            assertEquals(2, saved["profile_version"]!!.jsonPrimitive.int)
            assertEquals(DesktopNvidiaVideoIntensity.GENTLE, config.preferences.value.intensity)
        } finally { withTimeout(5000) { config.flushAndClose() } }
    }

    @Test fun `a strict value that is invalid still disables the feature whatever the profile version says`(): Unit = runBlocking {
        for ((index, namespace) in listOf(
            """{"migration_version":1,"enabled":true,"quality_level":"4","profile_version":2}""",
            """{"migration_version":1,"enabled":true,"hdr_mode":"unknown"}""",
            """{"migration_version":2,"enabled":true,"profile_version":2}""").withIndex()) {
            val path = Files.createDirectory(root.resolve("strict-$index"))
            val original = """{"windows_video_enhancement":$namespace}"""
            Files.writeString(path.resolve("plugin-settings.json"), original)
            val config = configuration(DesktopPluginStore(path))
            assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setAutomaticEnabled(true).await() } }
            assertFalse(config.preferences.value.enabled, "row $index")
            assertNotNull(config.error.value, "row $index")
            assertEquals(Json.parseToJsonElement(original), document(path), "row $index")
            withTimeout(5000) { config.flushAndClose() }
        }
    }
}
