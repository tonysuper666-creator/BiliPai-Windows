package com.bilipai.desktop.player

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Immutable projection of the selected loaded file, not a player/driver/state owner. */
internal data class DesktopMpvRuntimePatchIdentity(val nativeResolutionPatchAvailable: Boolean = false)
internal data class DesktopLoadedMpvNative(val api: MpvNative, val identity: DesktopMpvRuntimePatchIdentity)

/** Source/binary provenance licenses an attempt only. No GPU or effect is detected here. */
internal fun readDesktopMpvRuntimePatchIdentity(dll: File): DesktopMpvRuntimePatchIdentity {
    val unknown = DesktopMpvRuntimePatchIdentity()
    return try {
        val provenance = File(dll.parentFile, "provenance.json")
        if (!provenance.isFile) return unknown
        val bytes = provenance.inputStream().use { it.readNBytes(65537) }
        if (bytes.size > 65536) return unknown
        val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")) as? JsonObject ?: return unknown
        fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((json["schema"] as? JsonPrimitive)?.intOrNull != 1 ||
            text("architecture") != "windows-x64" || text("variant") != "bilipai-nvidia-native-v1" ||
            text("sourceCommit") != "69e63f425a531f814431fba12750bdb3721357f2" ||
            text("nativePatchSha256") != "e3599ec5fe4326a6713093e9834514f7c2b001b41f30c03fc26fc763b3345d30" ||
            text("originalNativeSourceSha256") != "9514d40109894e0bee4e4a2aa343a4e4e4d2368aee3729959180bf5f92d486c3" ||
            text("patchedNativeSourceSha256") != "1669c96fc95cfd7276a3149aa2d76058d29b2cc2dd848c77b949d434831c6d2f") return unknown
        val digests = listOf("dllSha256", "archiveSha256", "runtimeDescriptorSha256", "buildReceiptSha256", "sourceBundleSha256")
        if (digests.any { text(it)?.matches(Regex("[0-9a-f]{64}")) != true }) return unknown
        val expected = text("dllSha256") ?: return unknown
        if (expected == "673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4" ||
            text("archiveSha256") == "fac135c68a35b7639e39d72c0c365104edbaebdea39a0dfdd8c36e8c8e80faef") return unknown
        val digest = MessageDigest.getInstance("SHA-256")
        dll.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        DesktopMpvRuntimePatchIdentity(actual == expected)
    } catch (_: Exception) { unknown }
}
