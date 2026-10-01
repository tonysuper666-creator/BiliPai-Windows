// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt; do not edit.
// LF-normalized SHA-256: 0b6c8d1589b6be0ebcec709f2c04b5154afca6411559ac3c115bce122ff26ba9
package com.android.purebilibili.feature.dynamic.components
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import coil3.compose.AsyncImage
import com.bilipai.desktop.ui.LocalDesktopCommentBindings
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.skeleton.CommentListColumnSkeleton
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.home.components.resolveSharedBottomBarCapsuleShape
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
import kotlinx.coroutines.delay
fun LazyListScope.dynamicInlineCommentItems(
    comments: List<ReplyItem>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    onViewReplies: (ReplyItem) -> Unit,
    onReply: (ReplyItem) -> Unit = {},
    onLike: (ReplyItem) -> Unit = {},
    onHate: (ReplyItem) -> Unit = {},
    dynamicAuthorMid: Long = 0L,
    currentUserMid: Long? = null,
    onDelete: (ReplyItem) -> Unit = {},
    onToggleTop: (ReplyItem) -> Unit = {},
    onReport: (ReplyItem, Int) -> Unit = { _, _ -> },
    onUserClick: (Long) -> Unit,
    onImagePreview: (List<String>, Int, ImagePreviewSourceAnchor?, ImagePreviewTextContent?) -> Unit,
) {
    when {
        isLoading && comments.isEmpty() -> item(key = "dynamic_inline_comment_skeleton") {
            CommentListColumnSkeleton(itemCount = 4)
        }

        comments.isEmpty() -> item(key = "dynamic_inline_comment_empty") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacingTokens.DoubleExtraLarge),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    resolveDynamicCommentEmptyLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        else -> items(comments, key = { it.rpid }) { reply ->
            ReplyItemView(
                item = reply,
                onClick = { onViewReplies(reply) },
                onSubClick = { root, _ -> onViewReplies(root) },
                onReplyClick = { onReply(reply) },
                onLikeClick = { onLike(reply) },
                isLiked = isDynamicCommentLiked(reply),
                onHateClick = { onHate(reply) },
                isHated = reply.action == 2,
                onDeleteClick = { onDelete(reply) },
                onReportClick = { reason -> onReport(reply, reason) },
                canToggleTop = dynamicAuthorMid > 0L,
                onToggleTopClick = { onToggleTop(reply) },
                onAvatarClick = { mid -> mid.toLongOrNull()?.let(onUserClick) },
                onImagePreview = onImagePreview,
            )
        }
    }
    if (isLoadingMore) {
        item(key = "dynamic_inline_comment_loading_more") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacingTokens.Medium),
                contentAlignment = Alignment.Center,
            ) {
                AdaptiveLoadingIndicator(size = AppSpacingTokens.ExtraLarge)
            }
        }
    }
}

@Composable
fun DynamicInlineCommentComposer(
    onPostComment: (String, List<String>, (Boolean) -> Unit) -> Unit,
    replyTargetUname: String? = null,
    onClearReplyTarget: () -> Unit = {},
    liquidGlassEnabled: Boolean = false,
    backdrop: MiuixBackdrop? = null,
    modifier: Modifier = Modifier,
) {
    var commentText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    // Physical Windows keyboard; original focus and IME action are retained.
    val focusManager = LocalFocusManager.current

    LaunchedEffect(replyTargetUname) {
        if (!replyTargetUname.isNullOrBlank()) {
            delay(50L)
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    DynamicCommentComposer(
        value = commentText,
        onValueChange = { commentText = it },
        onSubmit = { message, images, onResult ->
            onPostComment(message, images) { success ->
                if (success) {
                    commentText = ""
                    if (!replyTargetUname.isNullOrBlank()) onClearReplyTarget()
                    focusManager.clearFocus()
                        }
                onResult(success)
            }
        },
        hint = resolveDynamicCommentComposerHint(replyTargetUname),
        onClearReplyTarget = if (replyTargetUname.isNullOrBlank()) null else onClearReplyTarget,
        liquidGlassEnabled = liquidGlassEnabled,
        backdrop = backdrop,
        focusRequester = focusRequester,
        modifier = modifier,
    )
}

@Composable
private fun DynamicCommentComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String, List<String>, (Boolean) -> Unit) -> Unit,
    hint: String = resolveDynamicCommentComposerHint(),
    onClearReplyTarget: (() -> Unit)? = null,
    liquidGlassEnabled: Boolean = false,
    backdrop: MiuixBackdrop? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var selectedImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSending by remember { mutableStateOf(false) }
    var activeSubmission by remember { mutableStateOf<Any?>(null) }
    val platform = LocalDesktopCommentBindings.current
    val onImagesSelected by rememberUpdatedState<(List<String>) -> Unit> { uris ->
        if (!isSending && onClearReplyTarget == null) {
            selectedImages = (selectedImages + uris).distinct().take(9)
        }
    }
    fun pickImages() {
        if (!platform.isOwned()) return
        platform.pickCommentImages(9) { uris ->
            if (platform.isOwned()) onImagesSelected(uris)
        }
    }
    LaunchedEffect(onClearReplyTarget != null) {
        if (onClearReplyTarget != null) selectedImages = emptyList()
    }
    val canSend = !isSending && (value.isNotBlank() || selectedImages.isNotEmpty())
    fun submit() {
        if (!canSend) return
        isSending = true
        val submission = Any()
        activeSubmission = submission
        onSubmit(value.trim(), selectedImages) { success ->
            if (!platform.isOwned() || activeSubmission !== submission) return@onSubmit
            activeSubmission = null
            if (success) selectedImages = emptyList()
            isSending = false
        }
    }
    val useMiuixNonGlassInput = isMiuixNonGlassEnabled()
    val dockShape = resolveSharedBottomBarCapsuleShape()
    val composerHeight = AppSpacingTokens.TripleExtraLarge + AppSpacingTokens.Small
    Column(
        modifier = modifier,
    ) {
        if (selectedImages.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacingTokens.Small),
                horizontalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small),
            ) {
                items(selectedImages, key = { it.toString() }) { uri ->
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = "已选图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(AppShapes.container(ContainerLevel.Chip)),
                        )
                        AppIconButton(
                            onClick = { selectedImages = selectedImages - uri },
                            enabled = !isSending,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(AppChromeSizeTokens.MinimumTouchTarget),
                        ) {
                            AppIcon(rememberAppClearIcon(), contentDescription = "移除图片")
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val commentFieldContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            Box(
            modifier = Modifier.weight(1f).height(composerHeight).clip(dockShape).background(commentFieldContainerColor)
        ) {
            val liquidChromeActive = false // Actual Windows native fallback capability.
            val fieldColor = if (liquidChromeActive) Color.Transparent else commentFieldContainerColor
                val fieldTextColor = MaterialTheme.colorScheme.onSurface
                val placeholderColor = if (liquidChromeActive) {
                    fieldTextColor.copy(alpha = 0.82f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)
                val keyboardActions = KeyboardActions(
                    onSend = {
                        if (resolveDynamicCommentImeSubmission(value) != null || selectedImages.isNotEmpty()) submit()
                    },
                )
                if (useMiuixNonGlassInput) {
                    AppOutlinedTextField(
                        value = value,
                        onValueChange = { if (!isSending) onValueChange(it) },
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                        placeholderText = hint,
                        singleLine = true,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        shape = dockShape,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = fieldTextColor
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = fieldColor,
                            unfocusedContainerColor = fieldColor,
                            disabledContainerColor = fieldColor,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedTextColor = fieldTextColor,
                            unfocusedTextColor = fieldTextColor,
                            disabledTextColor = fieldTextColor.copy(alpha = 0.72f),
                            focusedPlaceholderColor = placeholderColor,
                            unfocusedPlaceholderColor = placeholderColor,
                            disabledPlaceholderColor = placeholderColor,
                            cursorColor = fieldTextColor,
                        ),
                    )
                } else {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { if (!isSending) onValueChange(it) },
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                        placeholder = {
                            AppText(
                                text = hint,
                                color = placeholderColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        singleLine = true,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        shape = dockShape,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = fieldTextColor
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = fieldColor,
                            unfocusedContainerColor = fieldColor,
                            disabledContainerColor = fieldColor,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedTextColor = fieldTextColor,
                            unfocusedTextColor = fieldTextColor,
                            disabledTextColor = fieldTextColor.copy(alpha = 0.72f),
                            focusedPlaceholderColor = placeholderColor,
                            unfocusedPlaceholderColor = placeholderColor,
                            disabledPlaceholderColor = placeholderColor,
                            cursorColor = fieldTextColor,
                        ),
                    )
                }
            }
            if (onClearReplyTarget == null) {
                AppIconButton(
                    onClick = {
                        pickImages()
                    },
                    enabled = !isSending && selectedImages.size < 9,
                    modifier = Modifier.size(AppChromeSizeTokens.MinimumTouchTarget),
                ) {
                    AppIcon(
                        Icons.Outlined.Image,
                        contentDescription = "添加图片，已选 ${selectedImages.size}/9 张",
                    )
                }
            }
            AppTextButton(
                onClick = ::submit,
                enabled = canSend,
                modifier = Modifier.heightIn(min = AppChromeSizeTokens.MinimumTouchTarget),
            ) { AppText("发送") }
            if (onClearReplyTarget != null) {
                AppIconButton(onClick = onClearReplyTarget, enabled = !isSending) {
                    AppIcon(
                        rememberAppClearIcon(),
                        contentDescription = "取消回复",
                        modifier = Modifier.size(AppSpacingTokens.Large)
                    )
                }
            }
        }
    }
}

/**
 *  单条评论项
 */
