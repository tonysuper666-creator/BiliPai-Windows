package com.bilipai.desktop.plugins.js

import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.js.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.writeDesktopPluginDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale

data class DesktopJsPluginPreview(val manifest: BiliPaiJsPluginManifest, val scriptSha256: String,
    val sourceUrl: String? = null, internal val script: String)
data class DesktopJsInstalledState(val installed: InstalledBiliPaiJsPlugin, val authorizationMatches: Boolean)
data class DesktopJsPluginState(val plugins: List<DesktopJsInstalledState> = emptyList(), val error: String? = null)
@Serializable private data class DesktopJsApprovalRecord(val pluginId: String, val scriptSha256: String,
    val manifestSha256: String, val installedAtMillis: Long, val grantedCapabilities: Set<PluginCapability>)

/** The original install store persists packages; only Windows paths and exact-script grants are added. */
class DesktopJsPluginRepository(val context: DesktopPluginContext, val host: DesktopJsPluginHost,
    private val publicHttp: DesktopJsPublicHttp = DesktopJsPublicHttp()) {
    private val mutex = Mutex()
    private val store = BiliPaiJsPluginInstallStore.createDefault(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val manifestJson = Json { encodeDefaults = true }
    private val stateFlow = MutableStateFlow(DesktopJsPluginState())
    val state = stateFlow.asStateFlow()
    private var accountEpoch = 0L
    @Volatile private var stopped = false
    private val root get() = context.filesDir.toPath().toAbsolutePath().normalize()

    suspend fun load(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        checkOpen()
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            host.replaceAuthorizations(emptyList(), accountEpoch)
            stateFlow.value = stateFlow.value.copy(error = failure.message ?: "JS 插件安装记录无法读取"); throw failure
        }
    } }
    suspend fun preview(path: Path): DesktopJsPluginPreview = withContext(Dispatchers.IO) {
        checkOpen()
        require(Files.isRegularFile(path) && Files.size(path) <= 1_048_576) { "JS 插件文件无效或超过 1 MiB" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576)
        previewScript(decodeUtf8(bytes))
    }
    suspend fun previewScript(script: String, sourceUrl: String? = null): DesktopJsPluginPreview {
        checkOpen()
        val manifest = host.previewManifest(script)
        return DesktopJsPluginPreview(manifest, DesktopJsPluginHost.scriptSha256(script), sourceUrl, script)
    }
    suspend fun previewRemote(rawUrl: String): DesktopJsPluginPreview {
        checkOpen()
        val script = publicHttp.downloadScript(rawUrl)
        checkOpen()
        return previewScript(script, rawUrl.trim())
    }
    suspend fun mediaImage(pluginId: String, url: String): ByteArray = withContext(Dispatchers.IO) {
        val (revision, epoch, snapshot) = mutex.withLock {
            checkOpen(); validateInstalledTree(); validateId(pluginId)
            val installed = store.listInstalledPlugins().singleOrNull { it.manifest.id == pluginId }
                ?: error("JS 插件不存在")
            validateRecord(installed)
            require(installed.enabled && authorization(installed) != null &&
                PluginCapability.NETWORK in installed.grantedCapabilities) { "JS 媒体图片需要当前插件的网络权限" }
            val revision = host.executionRevision.value
            host.requireCurrentContext(revision, accountEpoch)
            Triple(revision, accountEpoch, installed)
        }
        val bytes = publicHttp.downloadImage(url)
        mutex.withLock {
            checkOpen()
            host.requireCurrentContext(revision, epoch)
            validateInstalledTree()
            val current = store.listInstalledPlugins().singleOrNull { it.manifest.id == pluginId }
            require(current == snapshot && authorization(snapshot) != null) { "JS 媒体图片所属插件批准记录已变化" }
        }
        bytes
    }

    /** Original .bplayout schema and namespace, written through this same approved plugin/account. */
    suspend fun importLayoutPreset(path: Path): Unit = withContext(Dispatchers.IO) {
        checkOpen()
        require(Files.isRegularFile(path) && Files.size(path) <= 1_048_576) { ".bplayout 文件无效或过大" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576)
        val preset = com.android.purebilibili.feature.plugin.js.BiliPaiJsLayoutPresetStore.parsePreset(decodeUtf8(bytes)).getOrThrow()
        mutex.withLock {
            checkOpen(); refresh()
            val installed = store.listInstalledPlugins().singleOrNull { it.manifest.id == preset.pluginId }
                ?: error("布局所需 JS 插件尚未安装")
            require(installed.manifest.modules.any { it.id.ifBlank { it.functionName } == preset.moduleId }) { "布局所需 JS 模块不存在" }
            val revision = host.executionRevision.value
            host.withOriginalPluginAdmission(installed, revision, allowDisabled = true) {
                com.android.purebilibili.feature.plugin.js.BiliPaiJsLayoutPresetStore.savePreset(context, preset)
            }
        }
    }

    suspend fun install(preview: DesktopJsPluginPreview, grants: Set<PluginCapability>): InstalledBiliPaiJsPlugin = withContext(Dispatchers.IO) {
        require(grants.all { it in preview.manifest.permissions }) { "JS 插件授权包含未声明的权限" }
        require(DesktopJsPluginHost.scriptSha256(preview.script) == preview.scriptSha256) { "JS 插件预览摘要不一致" }
        // Re-run the immutable bytes with no grants; the manifest must still equal what the user approved.
        require(host.previewManifest(preview.script) == preview.manifest) { "JS 插件声明与预览不一致" }
        mutex.withLock {
            checkOpen(); validateInstalledTree(); validateId(preview.manifest.id)
            require(store.listInstalledPlugins().none { it.manifest.id != preview.manifest.id && it.manifest.id.equals(preview.manifest.id, ignoreCase = true) }) { "JS 插件 ID 与 Windows 文件名冲突" }
            managed(root.resolve("bilipai_js_plugins/packages/${preview.manifest.id}/plugin.js"))
            managed(root.resolve("bilipai_js_plugins/installed/${preview.manifest.id}.json"))
            val authorizationFile = authorizationPath(preview.manifest.id)
            host.replaceAuthorizations(authorizations().filter { it.installed.manifest.id != preview.manifest.id }, accountEpoch)
            // Remove previous approval before replacing a script: an interrupted install stays unapproved.
            Files.deleteIfExists(authorizationFile)
            val installed = try { store.installPlugin(preview.manifest, preview.script, preview.sourceUrl, grants).getOrThrow() }
            catch (failure: Throwable) { refresh(); throw failure }
            validateRecord(installed)
            val approval = DesktopJsApprovalRecord(installed.manifest.id, preview.scriptSha256, manifestDigest(installed.manifest), installed.installedAtMillis,
                installed.grantedCapabilities)
            writeDesktopPluginDocument(authorizationFile.toFile(), json.encodeToString(DesktopJsApprovalRecord.serializer(), approval))
            refresh(); installed
        }
    }
    suspend fun setEnabled(pluginId: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        checkOpen(); validateInstalledTree(); validateId(pluginId)
        val record = store.listInstalledPlugins().singleOrNull { it.manifest.id == pluginId } ?: error("JS 插件不存在")
        validateRecord(record)
        if (enabled) require(authorization(record) != null) { "此 JS 插件需要重新预览并批准当前脚本权限" }
        check(store.setEnabled(pluginId, enabled) != null); refresh()
    } }
    suspend fun remove(pluginId: String): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        checkOpen(); validateInstalledTree(); validateId(pluginId)
        val record = store.listInstalledPlugins().singleOrNull { it.manifest.id == pluginId } ?: return@withLock
        validateRecord(record)
        val directory = managed(root.resolve("bilipai_js_plugins/packages/$pluginId"))
        if (Files.exists(directory, NOFOLLOW_LINKS)) Files.list(directory).use { paths ->
            val entries = paths.limit(17).toList()
            require(entries.size <= 16) { "JS 插件包目录含过多文件，无法删除" }
            entries.forEach { require(Files.isRegularFile(managed(it), NOFOLLOW_LINKS)) { "JS 插件包目录含非普通文件，无法删除" } }
        }
        // Stop owners and revoke approval before the original recursive removal touches this verified leaf.
        Files.deleteIfExists(authorizationPath(pluginId))
        host.replaceAuthorizations(authorizations().filter { it.installed.manifest.id != pluginId }, accountEpoch)
        store.removePlugin(pluginId); refresh()
    } }
    suspend fun accountChanged(epoch: Long): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        checkOpen()
        if (accountEpoch != epoch) { accountEpoch = epoch; refresh() }
    } }
    suspend fun approvedFeedModuleIds(): Pair<Long, Set<String>> = withContext(Dispatchers.IO) { mutex.withLock {
        checkOpen(); refresh()
        host.executionRevision.value to stateFlow.value.plugins.filter {
            it.authorizationMatches && it.installed.enabled &&
                PluginCapability.FEED_SOURCE in it.installed.grantedCapabilities &&
                PluginCapability.NETWORK in it.installed.grantedCapabilities
        }.flatMap { state -> state.installed.manifest.modules.filter { it.kind.equals("feed", true) }
            .map { "js:${state.installed.manifest.id}:${it.id.ifBlank { it.functionName }}" } }.toSet()
    } }
    suspend fun shutdownForRestore(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock { stopped = true }
        publicHttp.shutdownForRestore()
        host.shutdownForRestore()
    }

    private fun refresh() {
        validateInstalledTree()
        val records = store.listInstalledPlugins().onEach(::validateRecord)
        require(records.map { it.manifest.id.lowercase(Locale.ROOT) }.distinct().size == records.size) { "JS 插件 ID 存在 Windows 文件名冲突" }
        val authorizations = records.mapNotNull(::authorization)
        host.replaceAuthorizations(authorizations, accountEpoch)
        stateFlow.value = DesktopJsPluginState(records.map { record ->
            DesktopJsInstalledState(record, authorizations.any { it.installed == record })
        })
    }
    private fun authorizations(): List<DesktopJsAuthorizedPlugin> = store.listInstalledPlugins().mapNotNull(::authorization)
    private fun authorization(record: InstalledBiliPaiJsPlugin): DesktopJsAuthorizedPlugin? {
        val path = authorizationPath(record.manifest.id)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= 16_384) { "JS 插件授权文件无效" }
        val approval = json.decodeFromString(DesktopJsApprovalRecord.serializer(), decodeUtf8(Files.readAllBytes(path)))
        if (approval.pluginId != record.manifest.id || approval.installedAtMillis != record.installedAtMillis ||
            approval.grantedCapabilities != record.grantedCapabilities || approval.manifestSha256 != manifestDigest(record.manifest) ||
            !approval.scriptSha256.matches(Regex("[0-9a-f]{64}"))) return null
        val source = managed(Path.of(record.scriptPath))
        val bytes = Files.newInputStream(source).use { it.readNBytes(1_048_577) }
        if (bytes.size > 1_048_576 || DesktopJsPluginHost.scriptSha256(decodeUtf8(bytes)) != approval.scriptSha256) return null
        return DesktopJsAuthorizedPlugin(record, approval.scriptSha256)
    }
    private fun validateInstalledTree() {
        for (kind in listOf("packages", "installed", "authorizations")) managed(root.resolve("bilipai_js_plugins/$kind"))
        val metadata = managed(root.resolve("bilipai_js_plugins/installed"))
        if (!Files.exists(metadata, NOFOLLOW_LINKS)) return
        val files = Files.list(metadata).use { it.toList() }
        require(files.size <= 8192)
        files.forEach { path ->
            managed(path)
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.fileName.toString().endsWith(".json") && Files.size(path) <= 1_048_576) { "JS 插件安装目录无效" }
            val record = json.decodeFromString(InstalledBiliPaiJsPlugin.serializer(), decodeUtf8(Files.readAllBytes(path)))
            validateRecord(record)
            require(path.fileName.toString() == "${record.manifest.id}.json") { "JS 插件安装文件名与记录不符" }
        }
    }
    private fun validateRecord(record: InstalledBiliPaiJsPlugin) {
        validateId(record.manifest.id)
        validateBiliPaiJsPluginManifest(record.manifest)?.let { error(it) }
        require(record.grantedCapabilities.all { it in record.manifest.permissions }) { "JS 插件安装记录含未声明授权" }
        val expected = managed(root.resolve("bilipai_js_plugins/packages/${record.manifest.id}/plugin.js"))
        require(Path.of(record.scriptPath).toAbsolutePath().normalize() == expected && Files.isRegularFile(expected, NOFOLLOW_LINKS) && Files.size(expected) <= 1_048_576) { "JS 插件安装脚本路径无效" }
    }
    private fun authorizationPath(id: String): Path { validateId(id); return managed(root.resolve("bilipai_js_plugins/authorizations/$id.json")) }
    private fun managed(candidate: Path): Path {
        val target = candidate.toAbsolutePath().normalize()
        require(target.startsWith(root)) { "JS 插件路径越界" }
        var cursor = target
        while (cursor.startsWith(root)) {
            require(!Files.isSymbolicLink(cursor)) { "JS 插件路径为符号链接" }
            if (Files.exists(cursor, NOFOLLOW_LINKS)) require(cursor.toRealPath().startsWith(root.toRealPath())) { "JS 插件路径为外部 Windows 联接" }
            if (cursor == root) break
            cursor = cursor.parent
        }
        return target
    }
    private fun checkOpen() = check(!stopped) { "JS 插件服务已停止" }
    private fun manifestDigest(manifest: BiliPaiJsPluginManifest) = DesktopJsPluginHost.scriptSha256(manifestJson.encodeToString(BiliPaiJsPluginManifest.serializer(), manifest))
    private fun validateId(id: String) {
        require(id.matches(Regex("^[A-Za-z0-9_.-]{1,64}$")) && id != "." && id != ".." && !id.endsWith('.')) { "JS 插件 Windows ID 无效" }
        val base = id.substringBefore('.').uppercase(Locale.ROOT)
        require(base !in setOf("CON", "PRN", "AUX", "NUL") && !base.matches(Regex("(?:COM|LPT)[1-9]"))) { "JS 插件 Windows ID 被系统保留" }
    }
    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
