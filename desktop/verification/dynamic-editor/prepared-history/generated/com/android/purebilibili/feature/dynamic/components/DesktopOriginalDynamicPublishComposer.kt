// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicPublishComposer.kt; do not edit.
// LF-normalized SHA-256: 875a12047cb71c0cdfcf893538f56f3b20ae2e75894a6326c66d264a3175bad1
package com.android.purebilibili.feature.dynamic.components

import com.bilipai.desktop.ui.LocalDesktopDynamicEditorBindings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.AppDialogAction
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.ui.components.AppTextField
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppNativeSegmentedControl
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.rememberAppDeleteIcon
import com.android.purebilibili.data.model.response.DynamicCreatedReserve
import com.android.purebilibili.data.model.response.DynamicCreatedVote
import com.android.purebilibili.data.model.response.DynamicPublishDraft
import com.android.purebilibili.data.model.response.DynamicPublishMention
import com.android.purebilibili.data.model.response.DynamicPublishTopic

@Composable
fun DynamicPublishComposer(
    initialDraft: DynamicPublishDraft,
    isEditing: Boolean,
    submitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (DynamicPublishDraft) -> Unit
) {
    val platform = LocalDesktopDynamicEditorBindings.current
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

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { AppText(if (isEditing) "编辑动态" else "发布动态") },
        text = {
            val visibilityOptions = remember {
                listOf(
                    AppSegmentOption(false, "公开"),
                    AppSegmentOption(true, "仅自己可见"),
                )
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small)
                ) {
                    AppTextField(
                        value = title,
                        onValueChange = { if (it.length <= 20) title = it },
                        placeholder = "标题，选填 20 字",
                        singleLine = true
                    )
                    AppTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = "说点什么吧…",
                        singleLine = false,
                        minLines = 4
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small)) {
                            items(imageUris, key = { it }) { uri ->
                                Box {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(AppShapes.container(ContainerLevel.Chip)),
                                        contentScale = ContentScale.Crop
                                    )
                                    AppIconButton(
                                        onClick = { imageUris = imageUris - uri },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(AppChromeSizeTokens.MinimumTouchTarget),
                                    ) {
                                        AppIcon(
                                            imageVector = rememberAppDeleteIcon(),
                                            contentDescription = "移除图片",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                    }
                    // Original native toolbar body. The Android liquid dock is pending,
                    // not replaced by a shader or an invented HomeSettings value.
                    Box(modifier = Modifier.fillMaxWidth().height(AppSpacingTokens.TripleExtraLarge)) {
                        val liquidChromeActive = false
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = if (liquidChromeActive) AppSpacingTokens.ExtraSmall else AppSpacingTokens.None,
                                ),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacingTokens.ExtraSmall),
                        ) {
                            item {
                                AppTextButton(onClick = { showTopicDialog = true }) {
                                    AppText(topic?.name?.let { "#$it#" } ?: "话题")
                                }
                            }
                            item {
                                AppTextButton(onClick = { showMentionDialog = true }) { AppText("@用户") }
                            }
                            item {
                                AppTextButton(onClick = { showEmoteDialog = true }) { AppText("表情") }
                            }
                            item {
                                AppTextButton(
                                    onClick = {
                                        platform.pickImages(9) { uris ->
                                            if (platform.isOwned() && uris.isNotEmpty()) {
                                                imageUris = (imageUris + uris.map { it.toString() }).distinct().take(9)
                                            }
                                        }
                                    }
                                ) { AppText(if (imageUris.isEmpty()) "图片" else "图片 ${imageUris.size}/9") }
                            }
                            item {
                                AppTextButton(onClick = { showVoteDialog = true }) {
                                    AppText(vote?.title?.let { "投票：$it" } ?: "投票")
                                }
                            }
                            item {
                                AppTextButton(onClick = { showReserveDialog = true }) {
                                    AppText(reserve?.title?.let { "预约：$it" } ?: "预约")
                                }
                            }
                        }
                    }
                        AppNativeSegmentedControl(
                            options = visibilityOptions,
                            selectedValue = privatePublish,
                            modifier = Modifier.fillMaxWidth(),
                            onSelectionChange = { privatePublish = it },
                        )
                errorMessage?.let { AppText(it) }
                }
            }
        },
        confirmButton = {
            AppDialogAction(
                onClick = {
                    if (submitting) return@AppDialogAction
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
            ) {
                AppText(
                    when {
                        submitting -> "发布中…"
                        isEditing -> "保存"
                        else -> "发布"
                    }
                )
            }
        },
        dismissButton = {
            AppDialogAction(onClick = onDismiss) { AppText("取消") }
        }
    )

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

internal fun appendDynamicComposerToken(text: String, token: String): String {
    if (token.isBlank()) return text
    return when {
        text.isBlank() -> token
        text.last().isWhitespace() || token.first().isWhitespace() -> text + token
        else -> "$text $token"
    }
}
