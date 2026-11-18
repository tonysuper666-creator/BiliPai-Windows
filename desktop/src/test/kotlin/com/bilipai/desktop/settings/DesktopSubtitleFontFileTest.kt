package com.bilipai.desktop.settings

import android.app.Application
import android.net.Uri
import com.android.purebilibili.feature.video.subtitle.importSubtitleFont
import com.android.purebilibili.feature.video.subtitle.loadSubtitleFontFamily
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopSubtitleFontFileTest {
    private fun fontFixture(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/src/main/res/font/piliplus_custom_icons.ttf") }
        .first { it.isFile }

    @Test fun realFontImportsAndLoadsThroughThePageAndWrappedApplicationWithoutPreferenceWrites() = runBlocking {
        val root = Files.createTempDirectory("bp-subtitle-font-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            val page = DesktopOriginalPlayerSettingsContext(plugin, { true }, {
                error("Font file import must not write settings preferences")
            })
            val before = plugin.store.preferences("settings")
            val source = fontFixture()
            for (context in listOf(page.androidContext, Application(Application(page.androidContext)))) {
                val (storedName, displayName) = importSubtitleFont(context, Uri.fromFile(source))
                assertEquals(source.name, displayName)
                assertTrue(storedName.matches(Regex("[0-9a-f-]{36}\\.ttf")))
                assertContentEquals(source.readBytes(), File(context.filesDir, "subtitle_fonts/$storedName").readBytes())
                assertNotNull(loadSubtitleFontFamily(context, storedName))
                assertEquals(before, DesktopPluginStore(root).preferences("settings"))
            }
            assertEquals(2, File(page.androidContext.filesDir, "subtitle_fonts").listFiles()!!.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun rejectedImportsCleanTemporaryFilesAndPreserveThePreviouslyImportedFont() = runBlocking {
        val root = Files.createTempDirectory("bp-subtitle-font-reject-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            val page = DesktopOriginalPlayerSettingsContext(plugin, { true }, {
                error("Rejected fonts must not write settings preferences")
            })
            val context = Application(Application(page.androidContext))
            val before = plugin.store.preferences("settings")
            val (storedName, _) = importSubtitleFont(context, Uri.fromFile(fontFixture()))
            val directory = File(context.filesDir, "subtitle_fonts")
            val savedBytes = File(directory, storedName).readBytes()
            val inputs = root.resolve("inputs").toFile().apply { mkdirs() }
            val bad = File(inputs, "invalid.ttf").apply { writeText("not a font") }
            val empty = File(inputs, "empty.otf").apply { writeBytes(byteArrayOf()) }
            val wrongType = File(inputs, "font.txt").apply { writeBytes(savedBytes) }
            val oversized = File(inputs, "large.ttc").apply {
                RandomAccessFile(this, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
            }
            for (source in listOf(bad, empty, wrongType, oversized)) {
                assertFailsWith<Exception>(source.name) { importSubtitleFont(context, Uri.fromFile(source)) }
                assertEquals(setOf(storedName), directory.listFiles()!!.map { it.name }.toSet(), source.name)
                assertContentEquals(savedBytes, File(directory, storedName).readBytes())
                assertNotNull(loadSubtitleFontFamily(context, storedName))
                assertEquals(before, DesktopPluginStore(root).preferences("settings"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun loaderRejectsMissingMalformedAndEscapingNamesAndRemoteImportFailsExplicitly() = runBlocking {
        val root = Files.createTempDirectory("bp-subtitle-font-paths-")
        try {
            val plugin = DesktopPluginContext(DesktopPluginStore(root))
            val page = DesktopOriginalPlayerSettingsContext(plugin, { true }, {
                error("Rejected font paths must not write preferences")
            })
            val context = page.androidContext
            val before = plugin.store.preferences("settings")
            val directory = File(context.filesDir, "subtitle_fonts").apply { mkdirs() }
            File(directory, "invalid.ttf").writeText("not a font")
            File(context.filesDir, "outside.ttf").writeBytes(fontFixture().readBytes())
            for (name in listOf("", " ", "missing.ttf", "invalid.ttf", "../outside.ttf")) {
                assertNull(loadSubtitleFontFamily(context, name), name)
            }
            assertFailsWith<UnsupportedDesktopCapability> {
                importSubtitleFont(context, Uri.parse("content://documents/font.ttf"))
            }
            assertEquals(before, DesktopPluginStore(root).preferences("settings"))
            assertEquals(setOf("invalid.ttf"), directory.listFiles()!!.map { it.name }.toSet())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
