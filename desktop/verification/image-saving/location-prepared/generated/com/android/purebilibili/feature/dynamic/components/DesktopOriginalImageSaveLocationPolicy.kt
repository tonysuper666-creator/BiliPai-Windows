// Original alpha.9 ImageSaveLocationPolicy.kt LF_SHA256 320d756b9808f6be10ca5f54697a26f05739f95cfbe2e7c554ff078fba864e3f
package com.android.purebilibili.feature.dynamic.components

internal enum class ImageSaveDestination {
    MEDIA_STORE,
    SAF_TREE
}

internal fun shouldUseImageSaveTreeUri(uri: String?): Boolean =
    !uri.isNullOrBlank() && uri.trim().startsWith("content://")

internal fun resolveImageSaveDestination(uri: String?): ImageSaveDestination {
    return if (shouldUseImageSaveTreeUri(uri)) {
        ImageSaveDestination.SAF_TREE
    } else {
        ImageSaveDestination.MEDIA_STORE
    }
}

internal fun resolveDefaultImageMediaStoreRelativePath(): String = "Pictures/BiliPai"

