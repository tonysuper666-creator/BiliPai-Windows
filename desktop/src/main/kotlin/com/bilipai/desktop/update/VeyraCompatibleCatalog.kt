package com.bilipai.desktop.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** A signed catalog selects an exact own-repository full Windows application ZIP.
 * It is not a receipt that a Veyra engine has been installed or validated locally. */
internal class VerifiedVeyraCompatibleOffer private constructor(
    val update: WindowsUpdate, val sourceCommit: String, val adapterBuildId: String,
    val zipSha256: String, val engineProtocolMajor: Int,
) {
    companion object {
        fun verify(raw: String, trustedKeys: Map<String, ByteArray>, expectedOwnRepository: String,
            target: WindowsUpdate): VerifiedVeyraCompatibleOffer {
            require(DesktopUpdater.validRepository(expectedOwnRepository) && raw.length <= 256 * 1024)
            val json = Json { ignoreUnknownKeys = false }
            val envelope = json.decodeFromString(Envelope.serializer(), raw)
            require(envelope.schema == 1)
            val key = requireNotNull(trustedKeys[envelope.keyId]) { "Veyra compatibility signing key is not trusted" }
            val payload = Base64.getDecoder().decode(envelope.payloadBase64)
            require(payload.size in 1..128 * 1024)
            val signature = Base64.getDecoder().decode(envelope.signatureBase64)
            require(signature.size == 64)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key)))
            verifier.update(payload)
            require(verifier.verify(signature)) { "Veyra compatibility catalog signature invalid" }
            val manifest = json.decodeFromString(Manifest.serializer(), payload.toString(Charsets.UTF_8))
            require(manifest.schema == 1 && manifest.sourceRepository == "Likely7/Veyra-NRVideo" &&
                Regex("[0-9a-f]{40}").matches(manifest.sourceCommit) && manifest.adapterBuildId.isNotBlank() &&
                manifest.engineProtocolMajor > 0 && manifest.compatibilityStatus == "VALIDATED_FULL_APPLICATION_BUNDLE")
            require(manifest.repository == expectedOwnRepository && manifest.version == target.version &&
                manifest.releaseId == target.releaseId && manifest.assetId == target.assetId &&
                manifest.assetName == target.assetName && manifest.size == target.size &&
                manifest.downloadUrl == target.downloadUrl && Regex("[0-9a-f]{64}").matches(manifest.sha256) &&
                DesktopUpdater.isWindowsZip(target.assetName) && target.size in 1..DesktopUpdater.MAX_DOWNLOAD_BYTES &&
                DesktopUpdater.trustedAssetUrl(target.downloadUrl, expectedOwnRepository) &&
                DesktopUpdater.trustedAssetUrl(target.checksumUrl, expectedOwnRepository))
            return VerifiedVeyraCompatibleOffer(target, manifest.sourceCommit, manifest.adapterBuildId,
                manifest.sha256, manifest.engineProtocolMajor)
        }
    }
    @Serializable private data class Envelope(val schema: Int, val keyId: String,
        val payloadBase64: String, val signatureBase64: String)
    @Serializable private data class Manifest(val schema: Int, val repository: String, val version: String,
        val releaseId: Long, val assetId: Long, val assetName: String, val size: Long,
        val downloadUrl: String, val sha256: String, val sourceRepository: String,
        val sourceCommit: String, val adapterBuildId: String, val engineProtocolMajor: Int,
        val compatibilityStatus: String)
}
