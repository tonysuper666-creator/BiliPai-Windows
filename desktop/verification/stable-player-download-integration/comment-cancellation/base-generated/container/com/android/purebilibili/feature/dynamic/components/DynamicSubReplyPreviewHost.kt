// Original source app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicSubReplyPreviewHost.kt
// LF SHA256 0fe88ebb6855f0eab997c334eb2e75e8a10423d948259fed50d38acc2d69acad
package com.android.purebilibili.feature.dynamic.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.bilipai.desktop.ui.LocalDesktopCommentBindings
import kotlinx.coroutines.ensureActive
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.ui.components.SubReplySheet
import com.android.purebilibili.feature.video.viewmodel.SubReplySortMode
import com.android.purebilibili.feature.video.viewmodel.SubReplyUiState

@Composable
fun DynamicSubReplyPreviewHost(
    state: SubReplyUiState,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onSortModeChange: (SubReplySortMode) -> Unit,
    onUserClick: (Long) -> Unit,
    onReplyClick: ((ReplyItem) -> Unit)? = null,
    onCommentLike: ((Long) -> Unit)? = null,
    onCommentHate: ((Long) -> Unit)? = null,
    currentMid: Long = 0L,
    onDeleteComment: ((Long) -> Unit)? = null,
    onCoveredBlurProgressChange: ((Float) -> Unit)? = null,
) {
    val platform = LocalDesktopCommentBindings.current
    if (!platform.isOwned()) return
    val catalog = platform.emotes
    val emoteCatalogSessionKey = catalog.currentSessionKey()
    var emoteMap by remember(emoteCatalogSessionKey) {
        mutableStateOf(catalog.snapshot())
    }
    LaunchedEffect(emoteCatalogSessionKey) {
        val loaded = catalog.ensureLoaded()
        ensureActive()
        if (platform.isOwned()) emoteMap = loaded
    }
    var showImagePreview by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewInitialIndex by remember { mutableIntStateOf(0) }
    var previewSourceRect by remember { mutableStateOf<ImagePreviewSourceAnchor?>(null) }
    var previewTextContent by remember { mutableStateOf<ImagePreviewTextContent?>(null) }

    if (showImagePreview && previewImages.isNotEmpty()) {
        ImagePreviewDialog(
            images = previewImages,
            initialIndex = previewInitialIndex,
            sourceRect = previewSourceRect?.rect,
            sourceRects = previewSourceRect?.galleryRects.orEmpty(),
            sourceCornerRadiusDp = previewSourceRect?.cornerRadiusDp
                ?: AppShapes.containerCornerDp(ContainerLevel.Field).value,
            textContent = previewTextContent,
            onDismiss = {
                showImagePreview = false
                previewTextContent = null
            }
        )
    }

    SubReplySheet(
        state = state,
        emoteMap = emoteMap,
        onDismiss = onDismiss,
        onLoadMore = onLoadMore,
        onSortModeChange = onSortModeChange,
        onAvatarClick = { mid -> mid.toLongOrNull()?.let(onUserClick) },
        onReplyClick = onReplyClick,
        onCommentLike = onCommentLike,
        onCommentHate = onCommentHate,
        currentMid = currentMid,
        onDeleteComment = onDeleteComment,
        onImagePreview = { images, index, rect, textContent ->
            previewImages = images
            previewInitialIndex = index
            previewSourceRect = rect
            previewTextContent = textContent
            showImagePreview = true
        },
        onCoveredBlurProgressChange = onCoveredBlurProgressChange,
    )
}
