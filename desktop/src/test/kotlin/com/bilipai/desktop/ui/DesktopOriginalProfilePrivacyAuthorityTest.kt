package com.bilipai.desktop.ui

import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.reportDesktopPlaybackHeartbeat
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopSettingsSearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** The Profile privacy pill and Settings privacy switch are one authority: the search store that
 * DesktopLibrary, the heartbeat reporter and the search/settings-search history read. */
class DesktopOriginalProfilePrivacyAuthorityTest {
    private fun card(id: String) = VideoCard(id, "Fixture $id", "", "Fixture creator", 1, 120)
    private fun profileFor(rootStore: DesktopPluginStore, search: DesktopSearchPreferences,
        owns: () -> Boolean = { true }, admission: (() -> Unit) -> Boolean = { block -> block(); true }) =
        DesktopOriginalProfilePreferences(rootStore, search, owns, admission) { error("Theme is outside the privacy authority") }
    private fun rootFile(root: Path) = root.resolve("plugin-settings.json")
    private fun searchDocument(root: Path) =
        Json.parseToJsonElement(Files.readString(root.resolve("search").resolve("plugin-settings.json"))).jsonObject

    @Test fun `the Profile switch writes the store every history consumer reads`(): Unit = runBlocking {
        directory { root ->
            val rootStore = DesktopPluginStore(root)
            rootStore.update("settings", mapOf("profile_bg_uri" to JsonPrimitive("fixture-background")))
            val search = DesktopSearchPreferences(root)
            val profile = profileFor(rootStore, search)
            val library = DesktopLibrary(root.resolve("accounts").resolve("7"), search::isPrivacyModeEnabledSync)
            val settingsHistory = DesktopSettingsSearchRepository(
                DesktopPluginContext(DesktopPluginStore(root.resolve("settings-history"))), search::isPrivacyModeEnabledSync)
            var sends = 0
            suspend fun heartbeat() = reportDesktopPlaybackHeartbeat(search::isPrivacyModeEnabledSync, 8L, { 8L }, { 7L },
                { "fixture-csrf" }, "BV-fixture", 123L, 17L, 13L, 100L) { sends++; 0 }
            library.record(card("BV-before")); search.record(7L, "search before"); settingsHistory.record("settings before")
            assertTrue(heartbeat()); assertEquals(1, sends)
            val rootBytes = Files.readAllBytes(rootFile(root))

            profile.setPrivacyModeEnabled(true)
            assertTrue(DesktopSearchPreferences.readPrivacyModeEnabledSync(root))
            library.record(card("BV-private")); search.record(7L, "search private"); settingsHistory.record("settings private")
            assertTrue(heartbeat()); assertEquals(1, sends)
            assertEquals(listOf("BV-before"), library.history().map { it.bvid })
            assertEquals(listOf("search before"), search.history(7L).value.map { it.keyword })
            assertEquals(listOf("settings before"), settingsHistory.history.first())

            profile.setPrivacyModeEnabled(false)
            library.record(card("BV-after")); search.record(7L, "search after"); settingsHistory.record("settings after")
            assertTrue(heartbeat()); assertEquals(2, sends)
            assertEquals(listOf("BV-after", "BV-before"), library.history().map { it.bvid })
            assertEquals(listOf("search after", "search before"), search.history(7L).value.map { it.keyword })
            assertEquals(listOf("settings after", "settings before"), settingsHistory.history.first())
            // The Profile switch no longer writes the Root copy at all.
            assertContentEquals(rootBytes, Files.readAllBytes(rootFile(root)))
        }
    }

    @Test fun `Settings and Profile switches mirror each other through one flow and one file`(): Unit = runBlocking {
        directory { root ->
            val rootStore = DesktopPluginStore(root)
            val search = DesktopSearchPreferences(root)
            val profile = profileFor(rootStore, search)
            // The Home settings page builds a second Profile preferences object over the same two stores.
            val homeSettingsProfile = profileFor(rootStore, search)
            assertFalse(profile.isPrivacyModeEnabledSync()); assertFalse(profile.getPrivacyModeEnabled().first())

            search.setPrivacyMode(true) // DesktopPrivacySectionBindings.setPrivacyMode delegates to exactly this call
            for (view in listOf(profile, homeSettingsProfile)) {
                assertTrue(view.isPrivacyModeEnabledSync()); assertTrue(view.getPrivacyModeEnabled().first())
            }
            homeSettingsProfile.setPrivacyModeEnabled(false)
            assertFalse(search.privacyMode.value); assertFalse(search.isPrivacyModeEnabledSync())
            assertFalse(profile.isPrivacyModeEnabledSync()); assertFalse(profile.getPrivacyModeEnabled().first())

            profile.setPrivacyModeEnabled(true)
            assertTrue(search.privacyMode.value); assertTrue(search.isPrivacyModeEnabledSync())
            assertTrue(homeSettingsProfile.isPrivacyModeEnabledSync()); assertTrue(homeSettingsProfile.getPrivacyModeEnabled().first())
            assertTrue(searchDocument(root)["privacy_mode"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertTrue(DesktopSearchPreferences(root).privacyMode.value)
            assertFalse(Files.exists(rootFile(root)))
        }
    }

    @Test fun `a stale Root copy from older builds is neither adopted nor rewritten`(): Unit = runBlocking {
        directory { root ->
            val rootStore = DesktopPluginStore(root)
            rootStore.update("settings", mapOf("privacy_mode_enabled" to JsonPrimitive(true)))
            rootStore.update("privacy_mode", mapOf("enabled" to JsonPrimitive(true)))
            val staleBytes = Files.readAllBytes(rootFile(root))
            val search = DesktopSearchPreferences(root)
            val profile = profileFor(rootStore, search)
            val library = DesktopLibrary(root.resolve("accounts").resolve("7"), search::isPrivacyModeEnabledSync)
            assertFalse(profile.isPrivacyModeEnabledSync()); assertFalse(profile.getPrivacyModeEnabled().first())
            library.record(card("BV-recorded"))
            assertEquals(listOf("BV-recorded"), library.history().map { it.bvid })
            profile.setPrivacyModeEnabled(true); profile.setPrivacyModeEnabled(false)
            assertContentEquals(staleBytes, Files.readAllBytes(rootFile(root)))
        }
    }

    @Test fun `only the settings and splash_prefs Root namespaces gate Profile construction`(): Unit = runBlocking {
        directory { root ->
            Files.writeString(rootFile(root), """{"privacy_mode":true}""")
            val rootStore = DesktopPluginStore(root)
            val search = DesktopSearchPreferences(root)
            profileFor(rootStore, search).setPrivacyModeEnabled(true)
            assertTrue(search.isPrivacyModeEnabledSync())
        }
        directory { root ->
            Files.writeString(rootFile(root), """{"settings":true}""")
            assertFailsWith<IllegalArgumentException> { profileFor(DesktopPluginStore(root), DesktopSearchPreferences(root)) }
        }
        directory { root ->
            Files.writeString(rootFile(root), """{"splash_prefs":true}""")
            assertFailsWith<IllegalArgumentException> { profileFor(DesktopPluginStore(root), DesktopSearchPreferences(root)) }
        }
    }

    @Test fun `a retired owner a rejecting admission or a restore freeze leaves both stores untouched`(): Unit = runBlocking {
        directory { root ->
            val rootStore = DesktopPluginStore(root)
            val search = DesktopSearchPreferences(root)
            search.setSuggestionsEnabled(false) // makes the search file exist, so unchanged bytes mean something
            val before = files(root)
            assertFailsWith<CancellationException> { profileFor(rootStore, search, owns = { false }).setPrivacyModeEnabled(true) }
            assertFailsWith<CancellationException> { profileFor(rootStore, search, admission = { false }).setPrivacyModeEnabled(true) }
            assertEquals(before, files(root))
            assertFalse(search.privacyMode.value); assertFalse(search.isPrivacyModeEnabledSync())

            search.freezeWritesForRestore()
            assertFailsWith<IllegalStateException> { profileFor(rootStore, search).setPrivacyModeEnabled(true) }
            assertEquals(before, files(root))
            assertFalse(search.privacyMode.value); assertFalse(search.isPrivacyModeEnabledSync())
            assertFalse(Files.exists(rootFile(root)))
        }
    }

    private fun files(root: Path): Map<String, List<Byte>> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() }
    }
    private suspend fun directory(block: suspend (Path) -> Unit) {
        val root = Files.createTempDirectory("bilipai-profile-privacy-authority-").toAbsolutePath().normalize()
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
