package com.bilipai.desktop.data

/**
 * Host gate for Bilibili's default Referer and Origin.
 *
 * Port of upstream `core-data/src/main/java/com/android/purebilibili/core/network/BilibiliRequestHeaderPolicy.kt`
 * at commit `d6bf8b1655ae910999860bf13e65c8fcea5b345b`,
 * blob `7a5e06934fa2a87c5740da575744df7e1fda8a9e`.
 *
 * The shared client also loads RSS articles and images. Bilibili's default
 * Referer/Origin belongs only on its own services and media CDN requests.
 */
internal fun usesDefaultBilibiliHeaders(host: String): Boolean {
    val normalized = host.lowercase()
    return listOf("bilibili.com", "bilibili.tv", "biliapi.net", "biliapi.com", "hdslb.com", "bilivideo.com", "bilivideo.cn")
        .any { normalized == it || normalized.endsWith(".$it") }
}
