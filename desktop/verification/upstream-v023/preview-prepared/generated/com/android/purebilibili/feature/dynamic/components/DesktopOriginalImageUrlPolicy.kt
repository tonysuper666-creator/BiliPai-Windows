// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt; do not edit.
// LF-normalized SHA-256: 93c3ff348146db952bc8b8c9252a0d03a4cef7fe173835e884767b3efd3132cb
package com.android.purebilibili.feature.dynamic.components
internal fun normalizeImageUrl(rawSrc: String): String {
    val trimmed = rawSrc.trim()
    var result = when {
        trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("http://") -> trimmed.replace("http://", "https://")
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.isNotEmpty() -> "https://$trimmed"
        else -> ""
    }

    //  移除 Bilibili 图片尺寸参数（例如 @640w_400h.webp）以获取最高质量
    if (result.contains("@")) {
        result = result.substringBefore("@")
    }

    return result
}
internal fun resolveImagePreviewPlaceholderCacheKey(rawSrc: String): String? {
    val trimmed = rawSrc.trim()
    val normalized = when {
        trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("http://") -> trimmed.replace("http://", "https://")
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.isNotEmpty() -> "https://$trimmed"
        else -> ""
    }
    return normalized.takeIf { it.isNotEmpty() }
}
internal fun resolveImageShareMimeType(imageUrl: String): String {
    val normalizedUrl = imageUrl.substringBefore('?').substringBefore('@').lowercase()
    return when {
        normalizedUrl.endsWith(".gif") -> "image/gif"
        normalizedUrl.endsWith(".webp") -> "image/webp"
        normalizedUrl.endsWith(".png") -> "image/png"
        else -> "image/jpeg"
    }
}
