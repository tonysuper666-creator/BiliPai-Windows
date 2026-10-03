package com.android.purebilibili.feature.login

import android.content.Context
import android.util.Base64
import com.android.purebilibili.core.plugin.kotlinpkg.ExternalKotlinPluginInstallStore
import com.android.purebilibili.core.plugin.js.BiliPaiJsPluginInstallStore
import com.android.purebilibili.core.plugin.skin.UiSkinInstallStore
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.feature.settings.share.SettingsShareService
import java.io.File

/** Category-level selection of what a transfer carries besides the login session. */
data class BiliPaiTransferContentSelection(
    val settings: Boolean = false,
    val jsonRules: Boolean = false,
    val jsPluginIds: Set<String> = emptySet(),
    val skinIds: Set<String> = emptySet(),
    val kotlinPluginIds: Set<String> = emptySet(),
) {
    fun isNothingSelected(): Boolean = !settings && !jsonRules && jsPluginIds.isEmpty() &&
        skinIds.isEmpty() && kotlinPluginIds.isEmpty()
}

data class BiliPaiTransferContentItem(val id: String, val name: String, val bytes: Long)

data class BiliPaiTransferContentInventory(
    val settingCount: Int,
    val jsPlugins: List<BiliPaiTransferContentItem>,
    val skins: List<BiliPaiTransferContentItem>,
    val kotlinPlugins: List<BiliPaiTransferContentItem>,
    val hasJsonRules: Boolean,
)

data class BiliPaiTransferImportItemResult(val name: String, val success: Boolean, val message: String? = null)

data class BiliPaiTransferImportReport(
    val items: List<BiliPaiTransferImportItemResult>,
)

/**
 * Collects transferable settings/plugins on the sender and applies a payload on the receiver.
 * Session credentials are handled by [BiliPaiTransferSession]; this file only moves content.
 */
object BiliPaiTransferContent {

    fun inventory(context: Context): BiliPaiTransferContentInventory {
        val jsStore = BiliPaiJsPluginInstallStore.createDefault(context)
        val skinStore = UiSkinInstallStore.createDefault(context)
        val kotlinStore = ExternalKotlinPluginInstallStore.createDefault(context)
        return BiliPaiTransferContentInventory(
            settingCount = SettingsManager.getShareableSettingsEntryDefinitions().size,
            jsPlugins = jsStore.listInstalledPlugins().map {
                val bytes = jsStore.readScript(it).getOrNull()?.length?.toLong() ?: 0L
                BiliPaiTransferContentItem(it.manifest.id, it.manifest.title, bytes)
            },
            skins = skinStore.listInstalledPackages().map {
                BiliPaiTransferContentItem(it.skinId, it.displayName, fileBytes(File(it.packagePath)))
            },
            kotlinPlugins = kotlinStore.listInstalledPackages().map {
                BiliPaiTransferContentItem(
                    it.manifest.pluginId, it.manifest.displayName, fileBytes(File(it.packagePath)))
            },
            hasJsonRules = jsonRuleFiles(context).isNotEmpty() || jsonRulePrefsXml(context) != null,
        )
    }

    suspend fun collect(
        context: Context,
        selection: BiliPaiTransferContentSelection,
        session: BiliPaiSessionBundle,
    ): BiliPaiTransferPayload {
        if (selection.isNothingSelected()) return BiliPaiTransferPayload(session = session)
        val jsStore = BiliPaiJsPluginInstallStore.createDefault(context)
        val skinStore = UiSkinInstallStore.createDefault(context)
        val kotlinStore = ExternalKotlinPluginInstallStore.createDefault(context)
        return BiliPaiTransferPayload(
            session = session,
            settingsJson = selection.settings.takeIf { it }
                ?.let { runCatching { transferSettingsBridge(context).exportJson() }.getOrNull() },
            jsonRules = selection.jsonRules.takeIf { it }?.let {
                BiliPaiTransferredJsonRules(
                    sharedPrefsXml = jsonRulePrefsXml(context),
                    files = jsonRuleFiles(context),
                )
            },
            jsPlugins = jsStore.listInstalledPlugins()
                .filter { it.manifest.id in selection.jsPluginIds }
                .mapNotNull { installed ->
                    runCatching {
                        BiliPaiTransferredJsPlugin(
                            manifest = installed.manifest,
                            script = jsStore.readScript(installed).getOrThrow(),
                            grantedCapabilities = installed.grantedCapabilities,
                            enabled = installed.enabled,
                        )
                    }.getOrNull()
                },
            skins = skinStore.listInstalledPackages()
                .filter { it.skinId in selection.skinIds }
                .mapNotNull { installed ->
                    fileBytesOrNull(File(installed.packagePath))?.let { bytes ->
                        BiliPaiTransferredPackage(installed.displayName, encode64(bytes))
                    }
                },
            kotlinPlugins = kotlinStore.listInstalledPackages()
                .filter { it.manifest.pluginId in selection.kotlinPluginIds }
                .mapNotNull { installed ->
                    fileBytesOrNull(File(installed.packagePath))?.let { bytes ->
                        BiliPaiTransferredPackage(installed.manifest.displayName, encode64(bytes))
                    }
                },
        )
    }

    /** Applies everything except the login session; each item fails independently. */
    suspend fun apply(
        context: Context,
        payload: BiliPaiTransferPayload,
    ): BiliPaiTransferImportReport {
        val results = mutableListOf<BiliPaiTransferImportItemResult>()
        payload.settingsJson?.let { json ->
            results += runCatching {
                transferSettingsBridge(context).applyJson(json)
            }.fold(
                onSuccess = { BiliPaiTransferImportItemResult("设置", true) },
                onFailure = { BiliPaiTransferImportItemResult("设置", false, it.message) },
            )
        }
        payload.jsonRules?.let { rules ->
            results += applyJsonRules(context, rules)
        }
        payload.jsPlugins.forEach { plugin ->
            results += runCatching {
                val store = BiliPaiJsPluginInstallStore.createDefault(context)
                val installed = store.installPlugin(
                    plugin.manifest, plugin.script, sourceUrl = null,
                    grantedCapabilities = plugin.grantedCapabilities,
                ).getOrThrow()
                if (plugin.enabled) store.setEnabled(installed.manifest.id, true)
            }.fold(
                onSuccess = { BiliPaiTransferImportItemResult("JS 插件：${plugin.manifest.title}", true) },
                onFailure = { BiliPaiTransferImportItemResult("JS 插件：${plugin.manifest.title}", false, it.message) },
            )
        }
        payload.skins.forEach { skin ->
            results += runCatching {
                val store = UiSkinInstallStore.createDefault(context)
                val bytes = decode64(skin.bytesBase64)
                val preview = store.previewPackage(bytes).getOrThrow()
                store.installPreview(preview, bytes).getOrThrow()
            }.fold(
                onSuccess = { BiliPaiTransferImportItemResult("皮肤：${skin.name}", true) },
                onFailure = { BiliPaiTransferImportItemResult("皮肤：${skin.name}", false, it.message) },
            )
        }
        payload.kotlinPlugins.forEach { plugin ->
            results += runCatching {
                val store = ExternalKotlinPluginInstallStore.createDefault(context)
                val bytes = decode64(plugin.bytesBase64)
                val preview = store.previewPackage(bytes).getOrThrow()
                // Re-grant only what the package manifest declares; user approval stays local.
                store.installPreview(preview, bytes, preview.descriptor.manifest.capabilities).getOrThrow()
            }.fold(
                onSuccess = { BiliPaiTransferImportItemResult("插件包：${plugin.name}", true) },
                onFailure = { BiliPaiTransferImportItemResult("插件包：${plugin.name}", false, it.message) },
            )
        }
        return BiliPaiTransferImportReport(items = results)
    }

    private fun applyJsonRules(
        context: Context,
        rules: BiliPaiTransferredJsonRules,
    ): BiliPaiTransferImportItemResult = runCatching {
        rules.sharedPrefsXml?.let { xml ->
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").also { it.mkdirs() }
            File(prefsDir, "json_plugins.xml").writeText(xml, Charsets.UTF_8)
        }
        val pluginDir = File(context.filesDir, "json_plugins").also { it.mkdirs() }
        rules.files.forEach { file ->
            val name = file.name.substringAfterLast('/')
            require(name.isNotBlank() && !name.contains("..")) { "插件文件名无效" }
            File(pluginDir, name).writeText(file.content, Charsets.UTF_8)
        }
    }.fold(
        onSuccess = { BiliPaiTransferImportItemResult("JSON 规则插件", true) },
        onFailure = { BiliPaiTransferImportItemResult("JSON 规则插件", false, it.message) },
    )

    private fun jsonRulePrefsXml(context: Context): String? =
        File(File(context.applicationInfo.dataDir, "shared_prefs"), "json_plugins.xml")
            .takeIf { it.isFile }?.readText(Charsets.UTF_8)

    private fun jsonRuleFiles(context: Context): List<BiliPaiTransferredFile> =
        File(context.filesDir, "json_plugins")
            .takeIf { it.isDirectory }
            ?.listFiles { file -> file.isFile }
            .orEmpty()
            .map { BiliPaiTransferredFile(it.name, it.readText(Charsets.UTF_8)) }

    private fun transferSettingsBridge(context: Context) = BiliPaiTransferSettingsBridge(context)

    private fun fileBytes(file: File): Long = file.takeIf { it.isFile }?.length() ?: 0L

    private fun fileBytesOrNull(file: File): ByteArray? =
        file.takeIf { it.isFile }?.readBytes()

    private fun encode64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)
}

/**
 * Thin adapter over [SettingsShareService] so transfer stays in-memory: export/import
 * work on raw JSON strings without going through share Uris.
 */
internal class BiliPaiTransferSettingsBridge(private val context: Context) {
    private val service = SettingsShareService(context)

    suspend fun exportJson(): String =
        service.createExportArtifact(profileName = "设备传输", includeDeviceDebug = false).json

    suspend fun applyJson(rawJson: String) {
        val session = service.buildTransferImportSession(rawJson).getOrThrow()
        service.applyImport(session).getOrThrow()
    }
}
