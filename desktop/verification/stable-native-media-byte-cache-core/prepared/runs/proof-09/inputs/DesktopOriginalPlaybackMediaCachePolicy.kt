package com.android.purebilibili.core.player

import java.net.URI

internal fun resolvePlaybackMediaCacheMaxBytes(): Long = 128L * 1024L * 1024L

internal fun shouldUsePlaybackMediaCache(rawUri: String): Boolean {
    val scheme = runCatching { URI(rawUri).scheme }.getOrNull()
    return scheme.equals("http", ignoreCase = true) ||
        scheme.equals("https", ignoreCase = true)
}

internal fun buildPlaybackCacheKey(rawUri: String, explicitKey: String?): String {
    if (!explicitKey.isNullOrBlank()) return explicitKey
    val parsed = runCatching { URI(rawUri) }.getOrNull()
    val scheme = parsed?.scheme
    val host = parsed?.host
    val path = parsed?.rawPath
    return if (!scheme.isNullOrBlank() && !host.isNullOrBlank() && !path.isNullOrBlank()) {
        "$scheme://$host$path"
    } else {
        rawUri
    }
}
