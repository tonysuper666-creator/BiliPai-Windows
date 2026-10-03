package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class DesktopOriginalAboutPreferencesTest {
    @Test fun originalKeysDefaultsRemainUnwritten(): Unit = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bp-about-defaults-")))
        val prefs = DesktopOriginalAboutPreferences(context, { true }, { it(); true })
        assertTrue(prefs.autoCheck.first())
        assertTrue(prefs.easterEgg.first())
        assertEquals(DesktopOriginalAboutSettings.AppUpdateChannel.STABLE, prefs.channel.first())
        assertFalse(Files.exists(context.store.root.resolve("plugin-settings.json")))
    }
    @Test fun originalKeysAndEasterMirrorSurviveFreshStore(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-about-cold-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        context.store.update("settings", mapOf("foreign" to JsonPrimitive("keep")))
        context.store.update("easter_egg", mapOf("foreign" to JsonPrimitive("mirror")))
        val prefs = DesktopOriginalAboutPreferences(context, { true }, { it(); true })
        prefs.setAutoCheck(false); prefs.setChannel(DesktopOriginalAboutSettings.AppUpdateChannel.BETA); prefs.setEasterEgg(false)
        val fresh = DesktopOriginalAboutPreferences(DesktopPluginContext(DesktopPluginStore(root)), { true }, { it(); true })
        assertFalse(fresh.autoCheck.first()); assertFalse(fresh.easterEgg.first())
        assertEquals(DesktopOriginalAboutSettings.AppUpdateChannel.BETA, fresh.channel.first())
        val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(JsonPrimitive(false), disk["settings"]!!.jsonObject["easter_egg_enabled"])
        assertEquals(disk["settings"]!!.jsonObject["easter_egg_enabled"], disk["easter_egg"]!!.jsonObject["enabled"])
        assertEquals(JsonPrimitive("keep"), disk["settings"]!!.jsonObject["foreign"])
        assertEquals(JsonPrimitive("mirror"), disk["easter_egg"]!!.jsonObject["foreign"])
    }
    @Test fun retiredPageAndRejectedPermitDoNotWriteAnyNamespace(): Unit = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bp-about-retired-")))
        val owned = AtomicBoolean(true)
        val retired = DesktopOriginalAboutPreferences(context, owned::get) { action -> owned.set(false); action(); true }
        assertThrows(CancellationException::class.java) { runBlocking { retired.setEasterEgg(false) } }
        assertTrue(context.store.preferences("settings").isEmpty()); assertTrue(context.store.preferences("easter_egg").isEmpty())
        val rejected = DesktopOriginalAboutPreferences(context, { true }, { false })
        assertThrows(CancellationException::class.java) { runBlocking { rejected.setAutoCheck(false) } }
        assertFalse(Files.exists(context.store.root.resolve("plugin-settings.json")))
    }
    @Test fun originalReleaseNotesParsingAndExactCommunityTargetsRemainComplete() {
        assertEquals("https://github.com/jay3-yy/BiliPai/", OFFICIAL_GITHUB_URL)
        assertEquals("https://t.me/bilipai666", OFFICIAL_TELEGRAM_CHANNEL_URL)
        assertEquals("https://t.me/bilipai888/1", OFFICIAL_TELEGRAM_GROUP_URL)
        assertEquals(12, AboutContributors.size)
        assertEquals(12, AboutContributors.map { it.githubLogin }.distinct().size)
        assertEquals(listOf("jay3-yy", "Piracola", "chenx-dust", "usontong", "lekoOwO", "TanakaLun", "mvanhorn", "qyo123oyq", "maxzrb", "xiaoniao427", "zensu357", "Kurarion"), AboutContributors.map { it.githubLogin })
        val parsed = parseUpdateReleaseNotes("# v0.2.5\n\n- 添加表情\n- 修复刷新\n\n---\n\n保留正文")
        assertTrue(parsed.any { it is AppUpdateReleaseNotesBlock.Heading })
        assertEquals(2, parsed.count { it is AppUpdateReleaseNotesBlock.Bullet })
        assertTrue(parsed.any { it is AppUpdateReleaseNotesBlock.Divider })
        assertTrue(parsed.any { it is AppUpdateReleaseNotesBlock.Paragraph && it.text == "保留正文" })
    }
    @Test fun originalActionSearchRemainsLandingWithoutInvokingAction() {
        for (target in listOf(SettingsSearchTarget.OPEN_SOURCE_HOME, SettingsSearchTarget.CHECK_UPDATE,
            SettingsSearchTarget.VIEW_RELEASE_NOTES, SettingsSearchTarget.REPLAY_ONBOARDING, SettingsSearchTarget.OPEN_LINKS,
            SettingsSearchTarget.TELEGRAM, SettingsSearchTarget.TWITTER, SettingsSearchTarget.DISCLAIMER, SettingsSearchTarget.DONATE)) {
            val entry = resolveSettingsSearchResults(settingsDestinationCopy(target).title).first { it.target == target }
            assertEquals(com.android.purebilibili.navigation3.BiliPaiNavKey.SettingsCategory(SettingsRootCategory.SYSTEM_ABOUT), resolveSettingsSearchNavigation(entry))
        }
    }
    @Test fun metadataAdmissionRejectionKeepsLastEvidenceAndResetsBusy(): Unit = runBlocking {
        val metadata = DesktopOriginalAboutReleaseMetadata(OkHttpClient())
        assertThrows(CancellationException::class.java) { runBlocking {
            metadata.check("0.2.5", 0, false, { true }, { false }, true)
        } }
        assertNull(metadata.state.value.result)
        assertFalse(metadata.state.value.checking)
        assertEquals("点击检查", metadata.state.value.status)
    }
}
