package com.android.purebilibili.feature.dynamic.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.rememberAppBackIcon
import com.android.purebilibili.core.ui.rememberAppDeleteIcon
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppDropdownMenu
import com.android.purebilibili.core.ui.components.AppDropdownMenuItem
import com.android.purebilibili.core.ui.components.AppHorizontalDivider
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.ui.components.AppTextField
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.util.PickMultipleGalleryVisualMedia
import com.android.purebilibili.data.model.response.DynamicCreatedReserve
import com.android.purebilibili.data.model.response.DynamicCreatedVote
import com.android.purebilibili.data.model.response.DynamicPublishDraft
import com.android.purebilibili.data.model.response.DynamicPublishMention
import com.android.purebilibili.data.model.response.DynamicPublishTopic

private const val MAX_DYNAMIC_IMAGES = 18

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicPublishComposer(
    initialDraft: DynamicPublishDraft,
    isEditing: Boolean,
    submitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (DynamicPublishDraft) -> Unit
) {
    var text by remember(initialDraft) { mutableStateOf(initialDraft.text) }
    var title by remember(initialDraft) { mutableStateOf(initialDraft.title) }
    var imageUris by remember(initialDraft) { mutableStateOf(initialDraft.imageUris) }
    var vote by remember(initialDraft) {
        mutableStateOf(
            initialDraft.voteId.takeIf { it > 0L }?.let {
                DynamicCreatedVote(it, initialDraft.voteTitle.ifBlank { "投票" })
            }
        )
    }
    var reserve by remember(initialDraft) {
        mutableStateOf(
            initialDraft.reserveId.takeIf { it > 0L }?.let {
                DynamicCreatedReserve(it, "预约")
            }
        )
    }
    var mentions by remember(initialDraft) { mutableStateOf(initialDraft.mentions) }
    var emotes by remember(initialDraft) { mutableStateOf(initialDraft.emotes) }
    var topic by remember(initialDraft) { mutableStateOf(initialDraft.topic) }
    var privatePublish by remember(initialDraft) { mutableStateOf(initialDraft.private) }
    var showVoteDialog by remember { mutableStateOf(false) }
    var showReserveDialog by remember { mutableStateOf(false) }
    var showMentionDialog by remember { mutableStateOf(false) }
    var showTopicDialog by remember { mutableStateOf(false) }
    var showEmoteDialog by remember { mutableStateOf(false) }
    var showVisibilityMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        PickMultipleGalleryVisualMedia(maxItems = MAX_DYNAMIC_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) {
            imageUris = (imageUris + uris.map { it.toString() }).distinct().take(MAX_DYNAMIC_IMAGES)
        }
    }

    val canPublish = !submitting &&
        (text.isNotBlank() || imageUris.isNotEmpty() || vote != null || reserve != null)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun submitDraft() {
        if (!canPublish) return
        onSubmit(
            DynamicPublishDraft(
                text = text,
                title = title,
                imageUris = imageUris,
                voteId = vote?.voteId ?: 0L,
                voteTitle = vote?.title.orEmpty(),
                reserveId = reserve?.reserveId ?: 0L,
                private = privatePublish,
                mentions = mentions,
                emotes = emotes,
                topic = topic,
                existingImages = initialDraft.existingImages.filter { it.img_src in imageUris },
            )
        )
    }

    // PiliPlus uses a sheet expanded to the full window. The shared adaptive sheet centers on
    // tablets, so this page uses the Material sheet container with theme-aware app controls.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = Dp.Unspecified,
        shape = RectangleShape,
        containerColor = AppSurfaceTokens.background(),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = AppSpacingTokens.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(AppChromeSizeTokens.MinimumTouchTarget),
                ) {
                    AppIcon(rememberAppBackIcon(), contentDescription = "返回")
                }
                AppText(
                    if (isEditing) "编辑动态" else "发布动态",
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                AppButton(
                    onClick = ::submitDraft,
                    enabled = canPublish,
                    modifier = Modifier.heightIn(min = AppChromeSizeTokens.MinimumTouchTarget),
                    shape = AppShapes.container(ContainerLevel.Pill),
                ) {
                    AppText(
                        when {
                            submitting -> if (isEditing) "保存中…" else "发布中…"
                            isEditing -> "保存"
                            else -> "发布"
                        }
                    )
                }
            }
            AppHorizontalDivider()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacingTokens.Large, vertical = AppSpacingTokens.Medium),
                    verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Medium),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppTextButton(onClick = { showTopicDialog = true }, enabled = !submitting) {
                            AppText(topic?.name?.let { "#$it#" } ?: "# 选择话题")
                        }
                        if (topic != null) {
                            AppIconButton(onClick = { topic = null }, enabled = !submitting) {
                                AppIcon(rememberAppDeleteIcon(), contentDescription = "移除话题")
                            }
                        }
                    }
                    AppTextField(
                        value = title,
                        onValueChange = { if (!submitting && it.length <= 20) title = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "标题，选填 20 字",
                        singleLine = true,
                    )
                    AppTextField(
                        value = text,
                        onValueChange = { if (!submitting) text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "说点什么吧",
                        singleLine = false,
                        minLines = 6,
                    )
                    if (vote != null) {
                        PublishAttachedItem(
                            label = "投票：${vote?.title.orEmpty()}",
                            onRemove = { vote = null },
                            enabled = !submitting,
                        )
                    }
                    if (reserve != null) {
                        PublishAttachedItem(
                            label = "预约：${reserve?.title.orEmpty()}",
                            onRemove = { reserve = null },
                            enabled = !submitting,
                        )
                    }
                    Box {
                        AppTextButton(
                            onClick = { showVisibilityMenu = true },
                            enabled = !submitting,
                        ) {
                            AppIcon(
                                if (privatePublish) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                            )
                            AppText(if (privatePublish) "仅自己可见" else "所有人可见")
                        }
                        AppDropdownMenu(
                            expanded = showVisibilityMenu,
                            onDismissRequest = { showVisibilityMenu = false },
                        ) {
                            AppDropdownMenuItem(
                                text = { AppText("所有人可见") },
                                onClick = { privatePublish = false; showVisibilityMenu = false },
                            )
                            AppDropdownMenuItem(
                                text = { AppText("仅自己可见") },
                                onClick = { privatePublish = true; showVisibilityMenu = false },
                            )
                        }
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small),
                    ) {
                        items(imageUris, key = { it }) { uri ->
                            Box {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "待发布图片",
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(AppShapes.container(ContainerLevel.Chip)),
                                    contentScale = ContentScale.Crop,
                                )
                                AppIconButton(
                                    onClick = { imageUris = imageUris - uri },
                                    enabled = !submitting,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(AppChromeSizeTokens.MinimumTouchTarget),
                                ) {
                                    AppIcon(rememberAppDeleteIcon(), contentDescription = "移除图片")
                                }
                            }
                        }
                        if (imageUris.size < MAX_DYNAMIC_IMAGES) {
                            item {
                                AppSurface(
                                    onClick = {
                                        picker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    enabled = !submitting,
                                    modifier = Modifier.size(100.dp),
                                    shape = AppShapes.container(ContainerLevel.Chip),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AppIcon(Icons.Outlined.Add, contentDescription = "添加图片")
                                    }
                                }
                            }
                        }
                    }
                    errorMessage?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            AppHorizontalDivider()
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacingTokens.Medium, vertical = AppSpacingTokens.Small),
                horizontalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    AppTextButton(onClick = { showEmoteDialog = true }, enabled = !submitting) {
                        AppText("表情")
                    }
                }
                item {
                    AppTextButton(onClick = { showMentionDialog = true }, enabled = !submitting) {
                        AppText("@用户")
                    }
                }
                item {
                    AppTextButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !submitting && imageUris.size < MAX_DYNAMIC_IMAGES,
                    ) {
                        AppIcon(Icons.Outlined.Image, contentDescription = null)
                        AppText("图片 ${imageUris.size}/$MAX_DYNAMIC_IMAGES")
                    }
                }
                item {
                    AppTextButton(onClick = { showVoteDialog = true }, enabled = !submitting) {
                        AppText("投票")
                    }
                }
                item {
                    AppTextButton(onClick = { showReserveDialog = true }, enabled = !submitting) {
                        AppText("预约")
                    }
                }
            }
        }
    }

    if (showVoteDialog) {
        DynamicCreateVoteDialog(
            onDismiss = { showVoteDialog = false },
            onCreated = { created ->
                vote = created
                showVoteDialog = false
            }
        )
    }
    if (showReserveDialog) {
        DynamicCreateReserveDialog(
            onDismiss = { showReserveDialog = false },
            onCreated = { created ->
                reserve = created
                showReserveDialog = false
            }
        )
    }
    if (showMentionDialog) {
        DynamicMentionPickerDialog(
            onDismiss = { showMentionDialog = false },
            onSelected = { mention ->
                mentions = (mentions + mention).distinctBy { it.uid }
                text = appendDynamicComposerToken(text, "@${mention.name} ")
                showMentionDialog = false
            },
        )
    }
    if (showTopicDialog) {
        DynamicTopicPickerDialog(
            onDismiss = { showTopicDialog = false },
            onSelected = { selectedTopic ->
                topic = selectedTopic
                showTopicDialog = false
            },
        )
    }
    if (showEmoteDialog) {
        DynamicEmotePickerDialog(
            onDismiss = { showEmoteDialog = false },
            onSelected = { emote ->
                emotes = (emotes + emote).distinct()
                text = appendDynamicComposerToken(text, emote)
                showEmoteDialog = false
            },
        )
    }
}

@Composable
private fun PublishAttachedItem(
    label: String,
    onRemove: () -> Unit,
    enabled: Boolean,
) {
    AppSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.container(ContainerLevel.Card),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpacingTokens.Medium, end = AppSpacingTokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(label, modifier = Modifier.weight(1f))
            AppIconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.size(AppChromeSizeTokens.MinimumTouchTarget),
            ) {
                AppIcon(rememberAppDeleteIcon(), contentDescription = "移除附件")
            }
        }
    }
}

internal fun appendDynamicComposerToken(text: String, token: String): String {
    if (token.isBlank()) return text
    return when {
        text.isBlank() -> token
        text.last().isWhitespace() || token.first().isWhitespace() -> text + token
        else -> "$text $token"
    }
}
