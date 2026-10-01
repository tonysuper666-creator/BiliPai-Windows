// Selected original alpha.9 ImagePreviewDialog.kt LF_SHA256 8ab6d642e5085483ffa5fbe684cb962468c6b93daec46c1eb768e98f8b3fe0b0
// Original predicates/format/naming assignments; function seams and return statements are declared extraction adapters.
package com.android.purebilibili.feature.dynamic.components

internal fun desktopOriginalStaticGalleryKeepBytes(imageUrl: String): Boolean {
    val isGif = imageUrl.contains(".gif", ignoreCase = true)
    val isWebp = imageUrl.contains(".webp", ignoreCase = true)
    return isGif || isWebp
}

internal fun desktopOriginalStaticGalleryExtension(imageUrl: String): String {
    val isGif = imageUrl.contains(".gif", ignoreCase = true)
    val isWebp = imageUrl.contains(".webp", ignoreCase = true)
    val isPng = imageUrl.contains(".png", ignoreCase = true)
    if (isGif || isWebp) {
        val extension = when {
            isGif -> "gif"
            isWebp -> "webp"
            else -> "jpg"
        }
        return extension
    }
    val extension = if (isPng) "png" else "jpg"
    return extension
}

internal fun desktopOriginalStaticGalleryMimeType(imageUrl: String): String {
    val isGif = imageUrl.contains(".gif", ignoreCase = true)
    val isWebp = imageUrl.contains(".webp", ignoreCase = true)
    val isPng = imageUrl.contains(".png", ignoreCase = true)
    if (isGif || isWebp) {
        val mimeType = when {
            isGif -> "image/gif"
            isWebp -> "image/webp"
            else -> "image/jpeg"
        }
        return mimeType
    }
    val mimeType = if (isPng) "image/png" else "image/jpeg"
    return mimeType
}

internal fun desktopOriginalStaticGalleryFileName(imageUrl: String): String {
    val extension = desktopOriginalStaticGalleryExtension(imageUrl)
    val fileName = "BiliPai_${System.currentTimeMillis()}.$extension"
    return fileName
}
