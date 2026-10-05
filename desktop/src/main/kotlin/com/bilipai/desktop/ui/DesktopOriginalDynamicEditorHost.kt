package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.data.model.response.DynamicPublishDraft
import com.android.purebilibili.feature.dynamic.components.DynamicPublishComposer
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owner/lifetime seam for the original composer. It never creates a session. */
@Composable
internal fun DesktopOriginalDynamicEditorHost(
    initialDraft: DynamicPublishDraft,
    dynamicId: String?,
    operations: DesktopDynamicCardOperations,
    session: DesktopDynamicCardSession,
    imagePicker: (Int, (List<String>) -> Unit) -> Unit,
    dateTimePicker: (Long, (Int, Int, Int, Int, Int) -> Unit) -> Unit,
    imageProvider: suspend (String) -> Triple<String?, String?, okhttp3.RequestBody>,
    registerSubmission: (Job) -> Boolean,
    onDismiss: () -> Unit,
    onPublished: (String) -> Unit,
    onEdited: (String, DynamicPublishDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(session, dynamicId) { mutableStateOf(false) }
    var errorMessage by remember(session, dynamicId) { mutableStateOf<String?>(null) }
    val latestPublished by rememberUpdatedState(onPublished)
    val latestEdited by rememberUpdatedState(onEdited)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val platform = remember(operations, session, imagePicker, dateTimePicker) {
        DesktopDynamicEditorOperationsBinding(operations, session.emotes, imagePicker, dateTimePicker, session::isOwned)
    }
    if (!session.isOwned() || !operations.isOwned()) return
    CompositionLocalProvider(LocalDesktopDynamicEditorBindings provides platform) {
        DynamicPublishComposer(
            initialDraft = initialDraft,
            isEditing = dynamicId != null,
            submitting = submitting,
            errorMessage = errorMessage,
            onDismiss = { latestDismiss() },
            onSubmit = { draft ->
                if (!submitting && session.isOwned() && operations.isOwned()) {
                    submitting = true
                    errorMessage = null
                    val submission = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            if (dynamicId == null) {
                                operations.publishDynamic(draft, imageProvider).fold(
                                    onSuccess = { id -> if (session.isOwned() && operations.isOwned()) {
                                        latestPublished(id); latestDismiss()
                                    } },
                                    onFailure = { failure -> errorMessage = failure.message ?: "发布失败" },
                                )
                            } else {
                                operations.editDynamic(dynamicId, draft, imageProvider).fold(
                                    onSuccess = { if (session.isOwned() && operations.isOwned()) {
                                        latestEdited(dynamicId, draft); latestDismiss()
                                    } },
                                    onFailure = { failure -> errorMessage = failure.message ?: "编辑失败" },
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } finally {
                            submitting = false
                        }
                    }
                    if (!registerSubmission(submission) || !submission.start()) {
                        submitting = false
                        submission.cancel()
                    }
                }
            },
        )
    }
}
