package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.store.TokenManager
import java.security.MessageDigest

fun videoMetadataAccountScope(): String = buildVideoRequestAccountScope(
    listOf(TokenManager.sessDataCache, TokenManager.midCache?.toString())
)

fun videoPlaybackAccountScope(): String {
    val account = NetworkModule.playbackAccount()
    return buildVideoRequestAccountScope(
        listOf(
            account?.sessData ?: TokenManager.sessDataCache,
            account?.mid?.toString() ?: TokenManager.midCache?.toString(),
            account?.accessToken?.takeIf { it.isNotBlank() } ?: TokenManager.accessTokenCache,
            account?.accessTokenPlatform ?: TokenManager.accessTokenPlatformCache,
            (account?.isVip ?: TokenManager.isVipCache).toString()
        )
    )
}

internal fun buildVideoRequestAccountScope(parts: List<String?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.orEmpty().toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
