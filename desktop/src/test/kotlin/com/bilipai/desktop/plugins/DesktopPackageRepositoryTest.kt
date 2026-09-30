package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.core.plugin.kotlinpkg.ExternalKotlinPluginInstallStore
import com.android.purebilibili.core.plugin.skin.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DesktopPackageRepositoryTest {
    @Test fun `actual kotlin package grants persist per hash without execution`(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-kotlin-package-")
        val repository = DesktopPackageRepository(DesktopPluginContext(DesktopPluginStore(root)))
        val file = root.resolve("fixture.bpplugin")
        Files.write(file, kotlinPackage("dev.fixture.saved"))
        val preview = repository.previewKotlin(file)
        val installed = repository.installKotlin(preview, setOf(PluginCapability.NETWORK))
        assertFalse(installed.enabled)
        assertEquals(setOf(PluginCapability.NETWORK), installed.grantedCapabilities)
        val store = ExternalKotlinPluginInstallStore(root.resolve("external_kotlin_plugins").toFile())
        assertEquals(installed.grantedCapabilities, store.getAuthorization(installed.manifest.pluginId, installed.packageSha256)?.grantedCapabilities)
        val reopened = DesktopPackageRepository(DesktopPluginContext(DesktopPluginStore(root)))
        reopened.load()
        assertEquals(listOf(installed), reopened.state.value.kotlinPackages)
        assertTrue(reopened.revokeKotlinAuthorization(installed.manifest.pluginId, installed.packageSha256))
        assertNull(store.getAuthorization(installed.manifest.pluginId, installed.packageSha256))
        assertTrue(reopened.removeKotlin(installed.manifest.pluginId))
        assertFalse(Files.exists(java.nio.file.Path.of(installed.packagePath)))
    }

    @Test fun `actual skin selection persists assets and clears nullable preferences on default`(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-skin-package-")
        val repository = DesktopPackageRepository(DesktopPluginContext(DesktopPluginStore(root)))
        val preview = repository.previewSkinBytes(skinPackage("dev.fixture.skin"))
        val installed = repository.installSkin(preview)
        assertEquals(installed.installId, repository.state.value.skin.activeSkin?.installId)
        val reopened = DesktopPackageRepository(DesktopPluginContext(DesktopPluginStore(root)))
        reopened.load()
        assertEquals(installed.installId, reopened.state.value.skin.activeSkin?.installId)
        assertContentEquals(PNG_HEADER, Files.readAllBytes(java.nio.file.Path.of(installed.assetFiles.getValue("assets/trim.png"))))
        reopened.selectSkin(null)
        assertFalse(reopened.state.value.skin.enabled)
        assertNull(reopened.context.getSharedPreferences("ui_skin_settings", 0).getString("selected_install_id", null))
        assertFalse(Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject.getValue("ui_skin_settings").jsonObject.containsKey("selected_install_id"))
        assertTrue(reopened.deleteSkin(installed.installId))
        assertFalse(Files.exists(java.nio.file.Path.of(installed.packagePath)))
        assertFalse(Files.exists(java.nio.file.Path.of(installed.assetFiles.getValue("assets/trim.png"))))
    }

    @Test fun `windows device names traversal and alternate data streams cannot reach original stores`(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-skin-boundary-")
        val repository = DesktopPackageRepository(DesktopPluginContext(DesktopPluginStore(root)))
        for (id in listOf("..", "CON", "NUL.txt", "COM1", "unsafe.")) {
            assertFailsWith<IllegalArgumentException> { repository.previewSkinBytes(skinPackage(id)) }
            val file = root.resolve("fixture.bpplugin")
            Files.write(file, kotlinPackage(id))
            assertFailsWith<IllegalArgumentException> { repository.previewKotlin(file) }
        }
        assertFailsWith<IllegalArgumentException> { repository.previewSkinBytes(skinPackage("valid", "assets/trim:stream.png")) }
        assertFalse(Files.exists(root.resolve("ui_skins/packages")))
        assertFalse(Files.exists(root.resolve("external_kotlin_plugins/packages")))
    }

    @Test fun `foreign absolute installed path is rejected before deletion`(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-skin-foreign-")
        val outside = Files.createTempFile("preserved-user-document-", ".txt")
        Files.writeString(outside, "keep")
        val repository = DesktopPackageRepository(DesktopPluginContext(DesktopPluginStore(root)))
        val installed = repository.installSkin(repository.previewSkinBytes(skinPackage("dev.fixture.skin")))
        val metadata = root.resolve("ui_skins/installed/${installed.installId}.json")
        Files.writeString(metadata, Json.encodeToString(installed.copy(packagePath = outside.toString())))
        assertFailsWith<IllegalArgumentException> { repository.deleteSkin(installed.installId) }
        assertEquals("keep", Files.readString(outside))
        assertTrue(Files.exists(metadata))
        assertTrue(Files.exists(java.nio.file.Path.of(installed.packagePath)))
    }

    @Test fun `windows case-insensitive package ids cannot silently overwrite another identity`(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-package-case-")
        val repository = DesktopPackageRepository(DesktopPluginContext(DesktopPluginStore(root)))
        val first = repository.installSkin(repository.previewSkinBytes(skinPackage("dev.fixture.Skin")))
        val conflicting = repository.previewSkinBytes(skinPackage("dev.fixture.skin"))
        assertFailsWith<IllegalArgumentException> { repository.installSkin(conflicting) }
        assertEquals(listOf(first), repository.state.value.skins)
        val file = root.resolve("fixture.bpplugin")
        Files.write(file, kotlinPackage("dev.fixture.Plugin"))
        val installed = repository.installKotlin(repository.previewKotlin(file), emptySet())
        Files.write(file, kotlinPackage("dev.fixture.plugin"))
        assertFailsWith<IllegalArgumentException> { repository.installKotlin(repository.previewKotlin(file), emptySet()) }
        assertEquals(listOf(installed), repository.state.value.kotlinPackages)
    }

    @Test fun `package shutdown prevents late writes and rejects undeclared capability grants`(): Unit = runBlocking {
        val root = Files.createTempDirectory("desktop-package-restore-")
        val repository = DesktopPackageRepository(DesktopPluginContext(DesktopPluginStore(root)))
        val file = root.resolve("fixture.bpplugin")
        Files.write(file, kotlinPackage("dev.fixture.saved"))
        val preview = repository.previewKotlin(file)
        assertFailsWith<IllegalArgumentException> { repository.installKotlin(preview, setOf(PluginCapability.PLAYER_CONTROL)) }
        val skin = repository.previewSkinBytes(skinPackage("dev.fixture.skin"))
        repository.shutdownForRestore()
        assertFailsWith<IllegalStateException> { repository.installSkin(skin) }
        assertFailsWith<IllegalStateException> { repository.installKotlin(preview, emptySet()) }
        assertFalse(Files.exists(root.resolve("ui_skins/packages/dev.fixture.skin")))
    }

    companion object {
        private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private fun kotlinPackage(id: String) = archive("plugin-manifest.json" to Json.encodeToString(PluginCapabilityManifest(
            id, "Fixture", "1", 1, "dev.fixture.Plugin", setOf(PluginCapability.NETWORK))).toByteArray(), "classes.jar" to byteArrayOf(0x50, 0x4b))
        private fun skinPackage(id: String, asset: String = "assets/trim.png") = archive("skin-manifest.json" to Json.encodeToString(UiSkinManifest(
            1, id, "Fixture Skin", "1", 1, surfaces = setOf(UiSkinSurface.HOME_BOTTOM_BAR), assets = UiSkinAssets(bottomBarTrim = asset))).toByteArray(), asset to PNG_HEADER)
        private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
            bytes.toByteArray()
        }
    }
}
