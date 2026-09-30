package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.core.plugin.kotlinpkg.*
import com.android.purebilibili.core.plugin.skin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale

data class DesktopKotlinPackagePreview(val original: ExternalKotlinPluginPackagePreview,
    val decision: ExternalPluginInstallDecision, internal val bytes: ByteArray)
data class DesktopSkinPackagePreview(val original: UiSkinPackagePreview, internal val bytes: ByteArray,
    val previewAssets: Map<String, String>)
data class DesktopPackageState(val kotlinPackages: List<InstalledExternalPluginPackage> = emptyList(),
    val skins: List<InstalledUiSkinPackage> = emptyList(), val skin: UiSkinState = UiSkinState(), val error: String? = null)

/** Original package protocols are reused; the adapter validates Windows paths before every mutation. */
class DesktopPackageRepository(val context: DesktopPluginContext) {
    private val mutex = Mutex()
    @Volatile private var stopped = false
    private val kotlinStore = ExternalKotlinPluginInstallStore.createDefault(context)
    private val skinStore = UiSkinInstallStore.createDefault(context)
    private val _state = MutableStateFlow(DesktopPackageState())
    val state: StateFlow<DesktopPackageState> = _state.asStateFlow()

    suspend fun load(): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        checkOpen()
        try { refresh() } catch (failure: Exception) { _state.value = DesktopPackageState(error = failure.message ?: "插件包安装记录无法读取"); throw failure }
    } }
    suspend fun previewKotlin(path: Path): DesktopKotlinPackagePreview = withContext(Dispatchers.IO) {
        checkOpen()
        val bytes = readLimited(path, 32 * 1024 * 1024)
        val preview = kotlinStore.previewPackage(bytes).getOrThrow()
        validateId(preview.descriptor.manifest.pluginId)
        DesktopKotlinPackagePreview(preview, evaluateExternalPluginInstall(preview.descriptor, emptySet()), bytes)
    }
    /** This exactly follows upstream's save-only Kotlin package boundary; no code is loaded. */
    suspend fun installKotlin(preview: DesktopKotlinPackagePreview, granted: Set<PluginCapability>): InstalledExternalPluginPackage = mutate {
        val original = preview.original
        validateId(original.descriptor.manifest.pluginId)
        require(preview.decision is ExternalPluginInstallDecision.RequiresUserApproval) { "插件包不能安装" }
        require(granted.all { it in original.descriptor.manifest.capabilities }) { "授权包含未声明的权限" }
        val verified = kotlinStore.previewPackage(preview.bytes).getOrThrow()
        require(verified == original) { "插件包与预览不一致" }
        require(evaluateExternalPluginInstall(verified.descriptor, emptySet()) == preview.decision) { "插件包授权决策与预览不一致" }
        require(kotlinStore.listInstalledPackages().none { it.manifest.pluginId != original.descriptor.manifest.pluginId &&
            safeId(it.manifest.pluginId).equals(safeId(original.descriptor.manifest.pluginId), ignoreCase = true) }) { "插件标识与已安装包的 Windows 文件名冲突" }
        validatePackageRoots("external_kotlin_plugins", original.descriptor.manifest.pluginId, original.descriptor.packageSha256)
        kotlinStore.installPreview(original, preview.bytes, granted).getOrThrow()
    }
    suspend fun removeKotlin(pluginId: String): Boolean = mutate {
        validateId(pluginId)
        validateMetadata("external_kotlin_plugins")
        val installed = kotlinStore.listInstalledPackages().firstOrNull { it.manifest.pluginId == pluginId } ?: return@mutate false
        validateKotlinRecord(installed)
        kotlinStore.removeInstalledPackage(pluginId)
    }
    suspend fun revokeKotlinAuthorization(pluginId: String, packageHash: String): Boolean = mutate {
        validateId(pluginId); validateHash(packageHash)
        validatePackageRoots("external_kotlin_plugins", pluginId, packageHash)
        kotlinStore.revokeAuthorization(pluginId, packageHash)
    }

    suspend fun previewSkin(path: Path, mode: UiSkinImportMode = UiSkinImportMode.FULL_SKIN): DesktopSkinPackagePreview = withContext(Dispatchers.IO) {
        previewSkinBytes(readLimited(path, 128 * 1024 * 1024), mode)
    }

    suspend fun previewSkinBytes(input: ByteArray, mode: UiSkinImportMode = UiSkinImportMode.FULL_SKIN): DesktopSkinPackagePreview = withContext(Dispatchers.IO) {
        checkOpen()
        require(input.size <= 128 * 1024 * 1024) { "皮肤包超过 128 MiB" }
        val job = currentCoroutineContext()[Job]
        val resolved = UiSkinImportPackageResolver.resolve(input) { url -> downloadPublicAsset(url, job) }.getOrThrow()
        val bytes = if (mode == UiSkinImportMode.PERSONAL_BACKGROUND_ONLY)
            UiSkinImportPackageResolver.restrictToPersonalBackground(resolved.packageBytes).getOrThrow() else resolved.packageBytes
        val original = skinStore.previewPackage(bytes).getOrThrow()
        validateSkinPreview(original)
        mutex.withLock {
            checkOpen()
            validateManagedTree(context.store.root.resolve("ui_skins/preview_assets/${original.packageSha256}"))
            val files = skinStore.extractPreviewAssetFiles(original, bytes).getOrThrow()
            files.values.forEach { ensureInside("ui_skins", File(it).toPath()) }
            DesktopSkinPackagePreview(original, bytes, files)
        }
    }
    suspend fun installSkin(preview: DesktopSkinPackagePreview, activate: Boolean = true): InstalledUiSkinPackage = mutate {
        val verified = skinStore.previewPackage(preview.bytes).getOrThrow()
        require(verified == preview.original) { "皮肤包与预览不一致" }
        validateSkinPreview(verified)
        validateMetadata("ui_skins")
        require(skinStore.listInstalledPackages().none { it.skinId != verified.manifest.skinId &&
            safeId(it.skinId).equals(safeId(verified.manifest.skinId), ignoreCase = true) }) { "皮肤标识与已安装包的 Windows 文件名冲突" }
        validateManagedTree(context.store.root.resolve("ui_skins/assets/${safeId(verified.manifest.skinId)}/${verified.packageSha256}"))
        val installed = skinStore.installPreview(verified, preview.bytes).getOrThrow()
        validateSkinRecord(installed)
        if (activate) UiSkinSettingsStore.setSelection(context, UiSkinSelection(true, installed.skinId, installed.installId))
        installed
    }
    suspend fun selectSkin(installId: String?): Unit = mutate {
        validateMetadata("ui_skins")
        val installed = installId?.let { id -> skinStore.listInstalledPackages().firstOrNull { it.installId == id } ?: error("皮肤不存在") }
        installed?.let(::validateSkinRecord)
        UiSkinSettingsStore.setSelection(context, UiSkinSelection(installed != null, installed?.skinId, installed?.installId))
    }
    suspend fun deleteSkin(installId: String): Boolean = mutate {
        validateMetadata("ui_skins")
        val skin = skinStore.listInstalledPackages().firstOrNull { it.installId == installId } ?: return@mutate false
        validateSkinRecord(skin)
        validateManagedTree(context.store.root.resolve("ui_skins/assets/${safeId(skin.skinId)}/${skin.packageSha256}"))
        if (UiSkinSettingsStore.readState(context).activeSkin?.installId == installId)
            UiSkinSettingsStore.setSelection(context, UiSkinSelection())
        skinStore.deleteInstalledPackage(installId).getOrThrow()
    }
    suspend fun catalog(): SkinCatalog = withContext(Dispatchers.IO) { checkOpen(); SkinCatalogLoader.load(context).getOrThrow() }
    suspend fun previewCatalog(entry: SkinCatalogEntry, mode: UiSkinImportMode = UiSkinImportMode.FULL_SKIN): DesktopSkinPackagePreview = withContext(Dispatchers.IO) {
        val url = entry.preferredPackageUrl() ?: error("此装扮缺少下载包地址")
        previewSkinBytes(downloadPublicAsset(url, currentCoroutineContext()[Job]), mode)
    }

    private fun refresh() {
        listOf("external_kotlin_plugins", "ui_skins").forEach { kind ->
            listOf("packages", "installed", "authorizations", "assets", "preview_assets").forEach { folder -> ensureInside(kind, context.store.root.resolve("$kind/$folder")) }
        }
        validateMetadata("external_kotlin_plugins")
        validateMetadata("ui_skins")
        val packages = kotlinStore.listInstalledPackages().also { it.forEach(::validateKotlinRecord) }
        val skins = skinStore.listInstalledPackages().also { it.forEach(::validateSkinRecord) }
        _state.value = DesktopPackageState(packages, skins, UiSkinSettingsStore.readState(context))
    }
    private suspend fun <T> mutate(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { checkOpen(); refresh(); block().also { refresh() } }
    }
    private fun checkOpen() = check(!stopped) { "插件包服务已停止" }
    internal suspend fun shutdownForRestore() { stopped = true; mutex.withLock { } }

    private fun validateSkinPreview(preview: UiSkinPackagePreview) {
        validateId(preview.manifest.skinId); validateHash(preview.packageSha256)
        validatePackageRoots("ui_skins", preview.manifest.skinId, preview.packageSha256)
        require(preview.assetEntries.map { it.path.lowercase(Locale.ROOT) }.distinct().size == preview.assetEntries.size) { "皮肤资源路径在 Windows 上发生重名" }
        preview.assetEntries.forEach { asset ->
            require(asset.path.startsWith("assets/")) { "皮肤资源路径无效" }
            validateWindowsRelative(asset.path.removePrefix("assets/"))
            ensureInside("ui_skins", context.store.root.resolve("ui_skins/preview_assets/${preview.packageSha256}").resolve(asset.path.removePrefix("assets/")))
        }
    }
    private fun validateKotlinRecord(record: InstalledExternalPluginPackage) {
        validateId(record.manifest.pluginId); validateHash(record.packageSha256)
        validatePackageRoots("external_kotlin_plugins", record.manifest.pluginId, record.packageSha256)
        val expected = context.store.root.resolve("external_kotlin_plugins/packages/${safeId(record.manifest.pluginId)}/${record.packageSha256}.bpplugin")
        require(File(record.packagePath).toPath().toAbsolutePath().normalize() == expected.toAbsolutePath().normalize()) { "插件包路径不属于当前安装目录" }
        ensureInside("external_kotlin_plugins", expected)
    }
    private fun validateSkinRecord(record: InstalledUiSkinPackage) {
        validateId(record.skinId); validateHash(record.packageSha256)
        validatePackageRoots("ui_skins", record.skinId, record.packageSha256)
        require(record.installId == buildUiSkinInstallId(record.skinId, record.packageSha256)) { "皮肤安装标识无效" }
        val expected = context.store.root.resolve("ui_skins/packages/${safeId(record.skinId)}/${record.packageSha256}.bpskin")
        require(File(record.packagePath).toPath().toAbsolutePath().normalize() == expected.toAbsolutePath().normalize()) { "皮肤包路径不属于当前安装目录" }
        ensureInside("ui_skins", expected)
        record.assetFiles.forEach { (source, file) ->
            require(source in record.manifest.assets.declaredPaths() && source.startsWith("assets/")) { "皮肤资源声明无效" }
            val relative = source.removePrefix("assets/")
            validateWindowsRelative(relative)
            val target = context.store.root.resolve("ui_skins/assets/${safeId(record.skinId)}/${record.packageSha256}/$relative")
            require(File(file).toPath().toAbsolutePath().normalize() == target.toAbsolutePath().normalize()) { "皮肤资源路径不属于当前安装目录" }
            ensureInside("ui_skins", target)
        }
        require(record.assetFiles.keys == record.manifest.assets.declaredPaths().toSet()) { "皮肤资源映射不完整" }
    }
    private fun validatePackageRoots(kind: String, id: String, hash: String) {
        validateId(id); validateHash(hash)
        val base = context.store.root.resolve(kind)
        listOf("packages", "installed", "authorizations", "assets", "preview_assets").forEach { folder ->
            ensureInside(kind, base.resolve(folder))
            ensureInside(kind, base.resolve(folder).resolve(safeId(id)))
            ensureInside(kind, base.resolve(folder).resolve(safeId(id)).resolve(hash))
        }
        val extension = if (kind == "ui_skins") "bpskin" else "bpplugin"
        ensureInside(kind, base.resolve("packages/${safeId(id)}/$hash.$extension"))
        val record = if (kind == "ui_skins") buildUiSkinInstallId(id, hash) else safeId(id)
        ensureInside(kind, base.resolve("installed/$record.json"))
        ensureInside(kind, base.resolve("authorizations/${safeId(id)}/$hash.json"))
    }

    private fun validateMetadata(kind: String) {
        val directory = context.store.root.resolve("$kind/installed")
        ensureInside(kind, directory)
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { stream ->
            val paths = stream.limit(8193).toList()
            require(paths.size <= 8192) { "插件包安装记录数量超过限制" }
            paths.filter { it.fileName.toString().endsWith(".json") }.forEach { file ->
                ensureInside(kind, file)
                require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 1_048_576) { "插件包安装记录无效" }
            }
        }
    }
    /** Validate every pre-existing child before upstream recursively replaces an asset directory. */
    private fun validateManagedTree(directory: Path) {
        ensureInside("ui_skins", directory)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(directory).use { stream ->
            val paths = stream.limit(8193).toList()
            require(paths.size <= 8192) { "皮肤资源数量超过限制" }
            paths.forEach { ensureInside("ui_skins", it) }
        }
    }
    private fun ensureInside(kind: String, target: Path) {
        val root = context.store.root.toAbsolutePath().normalize()
        Files.createDirectories(root)
        val realRoot = root.toRealPath()
        val base = root.resolve(kind)
        val absolute = target.toAbsolutePath().normalize()
        require(absolute.startsWith(base)) { "插件包路径越界" }
        var existing = absolute
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: error("插件包目录无效")
        require(existing.toRealPath() == realRoot.resolve(root.relativize(existing)).normalize()) { "插件包目录不能通过链接指向其他目录" }
    }
    private fun validateId(id: String) {
        require(id.isNotBlank() && id.length <= 128) { "插件包标识无效" }
        validateWindowsRelative(safeId(id))
    }
    private fun safeId(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun validateHash(hash: String) = require(hash.matches(Regex("[0-9a-f]{64}"))) { "插件包 SHA-256 无效" }
    private fun validateWindowsRelative(path: String) {
        require(path.isNotBlank()) { "插件包路径为空" }
        path.replace('\\', '/').split('/').forEach { part ->
            val name = part.substringBefore('.').uppercase(Locale.ROOT)
            require(part.isNotEmpty() && part != "." && part != ".." && !part.endsWith('.') && !part.endsWith(' ') &&
                part.none { it.code < 32 || it in ":<>\"|?*" } && name !in setOf("CON", "PRN", "AUX", "NUL") &&
                !name.matches(Regex("(?:COM|LPT)[0-9¹²³]"))) { "插件包包含 Windows 不支持的路径：$part" }
        }
    }
    private fun readLimited(path: Path, limit: Int): ByteArray {
        require(Files.isRegularFile(path) && Files.size(path) <= limit) { "插件包文件无效或超过大小限制" }
        return Files.newInputStream(path).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) { "插件包超过大小限制" } }
    }
    private fun downloadPublicAsset(address: String, job: Job?): ByteArray {
        val url = address.toHttpUrl()
        require(url.isHttps) { "装扮下载只接受 HTTPS 地址" }
        val call = DesktopPluginNetwork.publicClient.newCall(Request.Builder().url(url).header("User-Agent", "BiliPai Skin").build())
        val registration = job?.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use { response ->
                require(response.isSuccessful) { "装扮下载失败 HTTP ${response.code}" }
                require(response.request.url.isHttps) { "装扮下载重定向不能降级到 HTTP" }
                require(response.body.contentLength() <= 128L * 1024 * 1024) { "装扮包超过大小限制" }
                response.body.byteStream().readNBytes(128 * 1024 * 1024 + 1).also { require(it.size <= 128 * 1024 * 1024) { "装扮包超过大小限制" } }
            }
        } finally { registration?.dispose() }
    }
}

fun writeDesktopPluginDocument(file: File, text: String) {
    val transaction = DesktopPluginAtomicFile(file)
    val stream = transaction.startWrite()
    try { stream.write(text.toByteArray(Charsets.UTF_8)); transaction.finishWrite(stream) }
    catch (error: Throwable) { transaction.failWrite(stream); throw error }
}

object DesktopSkinCatalogResource {
    fun open(name: String): java.io.InputStream {
        require(name == "rovniced-skin-catalog.json")
        val bytes = DesktopSkinCatalogResource::class.java.classLoader.getResourceAsStream(name)?.use { it.readNBytes(1_048_577) }
            ?: error("缺少装扮目录资源")
        require(bytes.size <= 1_048_576) { "装扮目录超过大小限制" }
        val normalized = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") { "%02x".format(it) }
        check(digest == DESKTOP_SKIN_CATALOG_SHA256) { "装扮目录校验失败" }
        return java.io.ByteArrayInputStream(normalized)
    }
}
