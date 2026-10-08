package com.bilipai.desktop.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.URI
import java.util.Base64

/** Public keys are source-pinned. Release metadata is never a source of trust keys. */
internal object DesktopVeyraCatalogTrust {
    const val REPOSITORY = "tonysuper666-creator/BiliPai-Windows"
    const val KEY_ID = "veyra-compatible-v1-102b3faa4e9e82a5"
    private const val PUBLIC_SPKI = "MCowBQYDK2VwAyEAxA5zYD641A+LJEOBdlc5Dg9I6szOsSArcBjLEapZ6Cc="
    fun keys(): Map<String, ByteArray> = mapOf(KEY_ID to Base64.getDecoder().decode(PUBLIC_SPKI))
}

/** One exact application's asset is optional only when its catalog asset is absent.
 * HTTP, malformed metadata, unknown keys and invalid signatures are errors, not absence. */
internal class DesktopVeyraCatalogResolver(
    private val fetch: suspend (url: String, byteLimit: Int, asset: Boolean) -> String,
    trustedKeys: Map<String, ByteArray> = DesktopVeyraCatalogTrust.keys(),
) {
    private val keys = trustedKeys.mapValues { (_, value) -> value.copyOf() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(target: WindowsUpdate): VerifiedVeyraCompatibleOffer? {
        currentCoroutineContext().ensureActive()
        require(target.releaseId > 0 && target.assetId > 0 && DesktopUpdater.isWindowsZip(target.assetName))
        val repository = DesktopVeyraCatalogTrust.REPOSITORY
        val releaseRaw = fetch("https://api.github.com/repos/$repository/releases/${target.releaseId}", RELEASE_LIMIT, false)
        require(releaseRaw.toByteArray(Charsets.UTF_8).size <= RELEASE_LIMIT) { "Windows 发布元数据超过大小限制" }
        val release = json.decodeFromString(Release.serializer(), releaseRaw)
        require(!release.draft && release.id == target.releaseId && release.tag == target.version &&
            release.url == target.releaseUrl && ownReleaseUrl(target.releaseUrl, target.version)) {
            "兼容目录所属 Windows 版本已改变"
        }
        val archive = requireNotNull(release.assets.singleOrNull { it.id == target.assetId })
        require(release.assets.count { it.name.equals(target.assetName, ignoreCase = true) } == 1 &&
            archive.name == target.assetName && archive.size == target.size &&
            archive.size in 1..DesktopUpdater.MAX_DOWNLOAD_BYTES && archive.state == "uploaded" &&
            archive.url == target.downloadUrl && ownReleaseUrl(archive.url, target.version, target.assetName)) {
            "兼容目录与 Windows 更新包不匹配"
        }
        val checksum = requireNotNull(release.assets.singleOrNull { it.name == target.assetName + ".sha256" })
        require(checksum.id > 0 && checksum.size in 1..128 * 1024L && checksum.state == "uploaded" &&
            checksum.url == target.checksumUrl && ownReleaseUrl(checksum.url, target.version, checksum.name))
        val name = target.assetName + CATALOG_SUFFIX
        val matching = release.assets.filter { it.name.equals(name, ignoreCase = true) }
        if (matching.isEmpty()) return null
        val catalog = requireNotNull(matching.singleOrNull()) { "Windows 兼容目录资产不唯一" }
        require(catalog.id > 0 && catalog.name == name && catalog.state == "uploaded" &&
            catalog.size in 1..CATALOG_LIMIT.toLong() && ownReleaseUrl(catalog.url, target.version, name)) {
            "Windows 兼容目录资产无效"
        }
        val raw = fetch(catalog.url, CATALOG_LIMIT, true)
        currentCoroutineContext().ensureActive()
        require(raw.toByteArray(Charsets.UTF_8).size.toLong() == catalog.size) { "Windows 兼容目录大小不一致" }
        return VerifiedVeyraCompatibleOffer.verify(raw, keys, repository, target)
    }

    @Serializable private data class Release(val id: Long, @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val url: String, val draft: Boolean, val assets: List<Asset>)
    @Serializable private data class Asset(val id: Long, val name: String, val size: Long,
        val state: String, @SerialName("browser_download_url") val url: String)

    companion object {
        const val CATALOG_SUFFIX = ".veyra-compatible.json"
        const val CATALOG_LIMIT = 256 * 1024
        private const val RELEASE_LIMIT = 2 * 1024 * 1024
        private fun ownReleaseUrl(raw: String, tag: String, asset: String? = null): Boolean {
            val url = raw.toHttpUrlOrNull() ?: return false
            val repository = DesktopVeyraCatalogTrust.REPOSITORY.split('/')
            val expected = repository + listOf("releases", if (asset == null) "tag" else "download", tag) +
                listOfNotNull(asset)
            return url.scheme == "https" && url.host == "github.com" && url.port == 443 &&
                url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                url.pathSegments == expected
        }
    }
}

/** The updater's existing client checks every tagged redirected network request before sending. */
internal class VeyraCatalogHttpScope(private val initialUrl: String, private val asset: Boolean) {
    fun requireAllowed(request: Request) {
        require(request.header("Cookie") == null && request.header("Authorization") == null &&
            allowedUrl(request.url.toString())) { "兼容目录重定向或认证信息被拒绝" }
    }

    private fun allowedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.fragment == null &&
            (url == initialUrl || asset && uri.host in setOf("release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
    }.getOrDefault(false)
}
