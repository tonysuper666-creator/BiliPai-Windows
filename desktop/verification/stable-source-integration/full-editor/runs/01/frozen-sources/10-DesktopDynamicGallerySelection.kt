package com.bilipai.desktop.ui

import com.android.purebilibili.core.util.desktopOriginalMultipleGalleryResult
import com.android.purebilibili.core.util.desktopOriginalSingleGalleryResult
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.nio.file.Path

/** Adapter after the existing Root-owned JFileChooser, before the existing
 * selected-image handles. No second picker, account, image list or cache owner.
 * String file URIs replace Android Uri only at the original result policy.
 * Empty Windows selections map to absent Android data/clipData.
 */
internal class DesktopDynamicGallerySelection(
    private val selectedImages: DesktopDynamicEditorSelectedImages,
    private val stillOwned: () -> Boolean,
) {
    private fun owned() { if (!stillOwned()) throw CancellationException("Gallery owner retired") }
    fun acceptResult(approved: Boolean, single: Path?, multiple: List<Path>?, maxItems: Int): List<String> {
        owned()
        require(maxItems in 1..18) { "Dynamic editor image capacity must be between 1 and 18" }
        val data = single?.toUri()?.toString()
        val clips = multiple?.takeIf { it.isNotEmpty() }?.map { it.toUri().toString() }
        val accepted = if (maxItems == 1) listOfNotNull(desktopOriginalSingleGalleryResult(approved, data, clips))
        else desktopOriginalMultipleGalleryResult(approved, data, clips, maxItems)
        owned()
        if (accepted.isEmpty()) return emptyList()
        return selectedImages.accept(accepted.map { Path.of(URI(it)) }).also { owned() }
    }
}
