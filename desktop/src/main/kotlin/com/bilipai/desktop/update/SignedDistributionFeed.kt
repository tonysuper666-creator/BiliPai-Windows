package com.bilipai.desktop.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Trust comes from the installed binary, never from the download server. */
internal object DistributionTrust {
    const val PUBLIC_KEY = "MCowBQYDK2VwAyEAvZASuZWE290s1FESqsVkQltjpbpZCcVDst34VjklIc8="
    const val DEFAULT_BASE = "https://learn.tonysuper666.com/bilipai"
    const val REPOSITORY = "tonysuper666-creator/BiliPai-Windows"
    fun verify(bytes: ByteArray, signature: String, domain: String, publicKey: String = PUBLIC_KEY) {
        val raw = Base64.getDecoder().decode(signature)
        require(raw.size == 64) { "更新签名大小无效" }
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))))
        verifier.update("BiliPai-Windows/$domain/v1\n".toByteArray(Charsets.UTF_8))
        verifier.update(bytes)
        require(verifier.verify(raw)) { "更新签名验证失败，已保留当前版本" }
    }

    fun manifestRequestUrl(url: String, base: String, version: String, channel: String, installationId: String): String {
        val request = url.toHttpUrl()
        val owner = base.toHttpUrl()
        if (request.host != owner.host || request.port != owner.port || request.scheme != owner.scheme) return url
        return request.newBuilder().addQueryParameter("version", version).addQueryParameter("channel", channel)
            .addQueryParameter("installationId", installationId).build().toString()
    }

    fun validBase(base: String): Boolean = runCatching {
        val u = URI(base)
        u.scheme == "https" && u.host != null && u.userInfo == null && u.port in setOf(-1, 443) &&
            u.query == null && u.fragment == null && !base.endsWith('/') &&
            u.rawPath.split('/').none { it == "." || it == ".." || '%' in it || '\\' in it }
    }.getOrDefault(false)

    fun assetUrl(url: String, base: String): Boolean = runCatching {
        val u = URI(url)
        validBase(base) && u.scheme == "https" && u.userInfo == null && u.port in setOf(-1, 443) &&
            u.query == null && u.fragment == null &&
            u.rawPath.split('/').none { it == "." || it == ".." || '%' in it || '\\' in it } &&
            (url.startsWith("$base/artifacts/") || DesktopUpdater.trustedAssetUrl(url, REPOSITORY))
    }.getOrDefault(false)
}

@Serializable internal data class DistributionRemotePolicy(
    val automaticUpdatesEnabled: Boolean = true,
    val checkIntervalHours: Int = 6,
    val announcement: String = "",
) {
    fun validate() {
        require(checkIntervalHours in 1..24 && announcement.length <= 1000)
        require(announcement.none { it < ' ' && it != '\n' })
    }
}

@Serializable internal data class DistributionRelease(
    val version: String, val releaseId: Long, val assetId: Long, val assetName: String,
    val size: Long, val downloadUrl: String, val sha256: String, val archiveSignature: String,
) {
    fun update(envelope: String) = WindowsUpdate(version, releaseId, assetId, assetName, size,
        downloadUrl, downloadUrl + ".sha256", "https://github.com/${DistributionTrust.REPOSITORY}", envelope)
}

@Serializable internal data class DistributionInstaller(val name: String, val size: Long, val sha256: String)

@Serializable internal data class DistributionManifest(
    val schema: Int, val kind: String, val channel: String, val sequence: Long,
    val issuedAt: Long, val expiresAt: Long, val rolloutPercent: Int,
    val withdrawn: List<String> = emptyList(), val release: DistributionRelease? = null,
    val installer: DistributionInstaller? = null, val stableApproved: Boolean = false,
)

@Serializable internal data class DistributionConfig(
    val schema: Int, val kind: String, val channel: String, val sequence: Long,
    val issuedAt: Long, val expiresAt: Long, val policy: DistributionRemotePolicy,
)

internal object SignedDistributionFeed {
    private val json = Json { ignoreUnknownKeys = false }
    @Serializable private data class Envelope(val payload: String, val signature: String)

    private fun payload(raw: String, kind: String, publicKey: String): String {
        require(raw.toByteArray(Charsets.UTF_8).size in 1..128 * 1024)
        val envelope = json.decodeFromString<Envelope>(raw)
        val bytes = Base64.getDecoder().decode(envelope.payload)
        require(bytes.size in 1..64 * 1024)
        DistributionTrust.verify(bytes, envelope.signature, kind, publicKey)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun validateHeader(schema: Int, kind: String, expectedKind: String, channel: String,
        expectedChannel: String, sequence: Long, issuedAt: Long, expiresAt: Long, now: Long) {
        require(schema == 1 && kind == expectedKind && channel == expectedChannel && channel in setOf("dev", "stable"))
        require(sequence > 0 && issuedAt <= now + 300 && expiresAt > now && expiresAt > issuedAt &&
            expiresAt - issuedAt <= 7 * 24 * 3600) { "更新元数据已过期或时间无效" }
    }

    fun manifest(raw: String, channel: String, base: String, now: Long = System.currentTimeMillis() / 1000,
        publicKey: String = DistributionTrust.PUBLIC_KEY): DistributionManifest {
        require(DistributionTrust.validBase(base))
        val m = json.decodeFromString<DistributionManifest>(payload(raw, "manifest", publicKey))
        validateHeader(m.schema, m.kind, "manifest", m.channel, channel, m.sequence, m.issuedAt, m.expiresAt, now)
        require(m.channel != "stable" || m.stableApproved) { "Stable 发布批准必须包含在签名中" }
        require(m.rolloutPercent in 0..100 && m.withdrawn.size <= 128 && m.withdrawn.distinct().size == m.withdrawn.size &&
            m.withdrawn.all { DesktopVersion.parse(it) != null })
        m.installer?.let { installer ->
            require(Regex("[A-Za-z0-9_.+ -]+\\.(?:msi|exe)").matches(installer.name) &&
                installer.size > 0 && Regex("[0-9a-f]{64}").matches(installer.sha256))
        }
        m.release?.let { r ->
            require(DesktopVersion.parse(r.version) != null && r.releaseId > 0 && r.assetId > 0 &&
                DesktopUpdater.isWindowsZip(r.assetName) && r.size in 1..DesktopUpdater.MAX_DOWNLOAD_BYTES &&
                DistributionTrust.assetUrl(r.downloadUrl, base) && Regex("[0-9a-f]{64}").matches(r.sha256))
            DistributionTrust.verify(r.sha256.toByteArray(Charsets.US_ASCII), r.archiveSignature, "archive", publicKey)
        }
        return m
    }

    fun config(raw: String, channel: String, now: Long = System.currentTimeMillis() / 1000,
        publicKey: String = DistributionTrust.PUBLIC_KEY): DistributionConfig {
        val c = json.decodeFromString<DistributionConfig>(payload(raw, "config", publicKey))
        validateHeader(c.schema, c.kind, "config", c.channel, channel, c.sequence, c.issuedAt, c.expiresAt, now)
        c.policy.validate()
        return c
    }

    fun inRollout(installationId: String, version: String, percentage: Int): Boolean {
        require(percentage in 0..100)
        val hash = MessageDigest.getInstance("SHA-256").digest("$installationId\n$version".toByteArray(Charsets.UTF_8))
        val bucket = ByteBuffer.wrap(hash, 0, 4).int.toLong() and 0xffffffffL
        return bucket % 100 < percentage
    }
}
