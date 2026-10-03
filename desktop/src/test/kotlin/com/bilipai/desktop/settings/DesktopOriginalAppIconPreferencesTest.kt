package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.settings.getIconGroups
import com.android.purebilibili.feature.settings.resolveIconOptionPreviewRes
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import javax.imageio.ImageIO

class DesktopOriginalAppIconPreferencesTest {
    @Test fun originalDefaultsAndAliasesRemainCanonical(): Unit = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bp-icon-defaults-")))
        val preferences = DesktopOriginalAppIconPreferences(context, { true }, { it(); true })
        assertEquals("icon_blue_snow_maid", preferences.state.first().appIcon)
        assertEquals(AppIconAppearance.FOLLOW_SYSTEM, preferences.appearance.first())
        assertEquals("icon_bilipai_pink", normalizeAppIconKey(" BiliPai 粉 "))
        assertEquals("icon_blue_snow_maid", normalizeAppIconKey("unknown"))
        assertFalse(Files.exists(context.store.root.resolve("plugin-settings.json")))
    }

    @Test fun selectedIconAndAppearanceReachSameStoreAndBothOriginalMirrors(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-icon-persistence-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        context.store.update("settings", mapOf("foreign_key" to JsonPrimitive("keep")))
        context.store.update("app_icon_cache", mapOf("foreign_mirror" to JsonPrimitive("keep")))
        val preferences = DesktopOriginalAppIconPreferences(context, { true }, { it(); true })
        preferences.setAppIcon("Blue Snow Maid Front")
        preferences.setAppIconAppearance(AppIconAppearance.DARK)
        val second = DesktopOriginalAppIconPreferences(DesktopPluginContext(DesktopPluginStore(root)), { true }, { it(); true })
        assertEquals("icon_blue_snow_maid_front", second.state.first().appIcon)
        assertEquals(AppIconAppearance.DARK, second.appearance.first())
        val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        val settings = disk["settings"]!!.jsonObject
        val mirror = disk["app_icon_cache"]!!.jsonObject
        assertEquals("icon_blue_snow_maid_front", settings["app_icon_key"]!!.jsonPrimitive.content)
        assertEquals(2, settings["app_icon_appearance"]!!.jsonPrimitive.int)
        assertEquals(settings["app_icon_key"], mirror["current_icon"])
        assertEquals(settings["app_icon_appearance"], mirror["appearance"])
        assertEquals("keep", settings["foreign_key"]!!.jsonPrimitive.content)
        assertEquals("keep", mirror["foreign_mirror"]!!.jsonPrimitive.content)
    }

    @Test fun retiredOwnerAndAdmissionRecheckCannotPublishEitherNamespace(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-icon-retired-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        var owned = true
        val preferences = DesktopOriginalAppIconPreferences(context, { owned }, { action -> owned = false; action(); true })
        assertThrows(CancellationException::class.java) { runBlocking { preferences.setAppIcon("icon_3d") } }
        assertEquals("icon_blue_snow_maid", preferences.state.first().appIcon)
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
        assertTrue(context.store.preferences("app_icon_cache").isEmpty())
        val rejected = DesktopOriginalAppIconPreferences(context, { true }, { false })
        assertThrows(CancellationException::class.java) { runBlocking { rejected.setAppIconAppearance(AppIconAppearance.LIGHT) } }
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }

    @Test fun everyOriginalOptionAndAppearanceHasAnActualUntouchedPackagedPng() {
        val groups = getIconGroups()
        assertEquals(listOf("icon_blue_snow_maid", "icon_blue_snow_maid_announcement", "icon_blue_snow_maid_front",
            "icon_3d", "icon_bilipai", "icon_bilipai_pink", "icon_bilipai_white", "icon_bilipai_monet"), groups.flatMap { it.icons }.map { it.key })
        for (option in groups.flatMap { it.icons }) for (appearance in AppIconAppearance.entries) for (dark in listOf(false, true)) {
            val preview = resolveDesktopOriginalIconResource(resolveIconOptionPreviewRes(option.key, appearance), dark)
            val launcher = resolveDesktopOriginalLauncherIconResource(option.key, appearance, dark)
            for (path in setOf(preview, launcher)) javaClass.getResourceAsStream(path).use { input ->
                assertNotNull(input, path)
                val image = ImageIO.read(input!!)
                assertNotNull(image, path)
                assertTrue(image.width >= 64 && image.height >= 64, path)
            }
            if (option.key.startsWith("icon_blue_snow_maid") && appearance == AppIconAppearance.DARK)
                assertTrue(preview.contains("_dark_round.png"))
            if (option.key.startsWith("icon_blue_snow_maid") && appearance == AppIconAppearance.LIGHT)
                assertTrue(preview.contains("_light_round.png"))
        }
    }
}
