// GENERATED original gallery ordered distinct/take and MIME policy.
// Original: app/src/main/java/com/android/purebilibili/core/util/GalleryVisualMediaContracts.kt
// LF SHA-256: ec395620e07c73be2d830d274cdebb525bba07fe607f44b235634ec6c4a84549
package com.android.purebilibili.core.util
internal sealed interface DesktopGalleryVisualMediaType {
    data object ImageOnly : DesktopGalleryVisualMediaType
    data object VideoOnly : DesktopGalleryVisualMediaType
    data object ImageAndVideo : DesktopGalleryVisualMediaType
    data class SingleMimeType(val mimeType: String) : DesktopGalleryVisualMediaType
}
internal fun desktopOriginalMultipleGalleryResult(resultOk: Boolean, data: String?, clips: List<String>?, maxItems: Int): List<String> {
        if (!resultOk) return emptyList()

        val selectedUris = linkedSetOf<String>()
        data?.let(selectedUris::add)
        clips?.let { clipData ->
            repeat(clipData.size) { index ->
                selectedUris += clipData[index]
            }
        }
        return selectedUris.take(maxItems)
}
internal fun desktopOriginalGalleryMimeType(mediaType: DesktopGalleryVisualMediaType): String? = when (mediaType) {
    DesktopGalleryVisualMediaType.ImageOnly -> "image/*"
    DesktopGalleryVisualMediaType.VideoOnly -> "video/*"
    DesktopGalleryVisualMediaType.ImageAndVideo -> null
    is DesktopGalleryVisualMediaType.SingleMimeType -> mediaType.mimeType
}
