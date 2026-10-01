package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppDialogAction
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.data.model.response.DynamicPublishDraft
import com.android.purebilibili.feature.dynamic.components.DynamicManageAction
import com.android.purebilibili.feature.dynamic.verifyDesktopOriginalDynamicPublish
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Routes keep the original draft, independently of a scrolling card's lifetime. */
internal class DesktopDynamicEditorActions(
    val publish: (DynamicPublishDraft) -> Unit,
    val edit: (DynamicManageAction.Edit) -> Unit,
)
internal val LocalDesktopDynamicEditorActions = staticCompositionLocalOf<DesktopDynamicEditorActions?> { null }

internal class DesktopDynamicEditorRequest(val draft: DynamicPublishDraft, val dynamicId: String?)

/** Only modal/task ownership lives here. The existing session owns confirmed refreshes. */
internal class DesktopDynamicEditorRoot(
    repository: DesktopRepository,
    internal val session: DesktopDynamicCardSession,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    internal val operations = DesktopDynamicCardOperations(repository, session.expectedEpoch, ::isOwned, session.emotes)
    var request by mutableStateOf<DesktopDynamicEditorRequest?>(null); private set
    var feedback by mutableStateOf<String?>(null); private set
    val actions = DesktopDynamicEditorActions(
        publish = { draft -> if (isOwned()) request = DesktopDynamicEditorRequest(draft, null) },
        edit = { action -> if (isOwned()) request = DesktopDynamicEditorRequest(action.initialDraft, action.dynamicId) },
    )
    fun isOwned(): Boolean = alive.get() && session.isOwned()
    fun owns(candidate: DesktopDynamicEditorRequest): Boolean = isOwned() && request === candidate
    fun dismiss(candidate: DesktopDynamicEditorRequest) { if (request === candidate) request = null }
    fun show(message: String) { if (isOwned()) feedback = message }
    fun clearFeedback() { feedback = null }
    fun published(candidate: DesktopDynamicEditorRequest, id: String) {
        if (!owns(candidate)) return
        show("发布成功")
        session.confirmContentChange()
        // Original ViewModel verification belongs to the page owner, after the modal closes.
        scope.launch {
            verifyDesktopOriginalDynamicPublish(id, operations::getPublishedDynamicDetail) { _, message -> show(message) }
        }
    }
    fun edited(candidate: DesktopDynamicEditorRequest) {
        if (!owns(candidate)) return
        show("已更新动态")
        session.confirmContentChange()
    }
    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        scope.cancel(); request = null; feedback = null
    }
}

@Composable internal fun rememberDesktopDynamicEditorRoot(
    repository: DesktopRepository, session: DesktopDynamicCardSession,
): DesktopDynamicEditorRoot {
    val scope = rememberCoroutineScope()
    val root = remember(repository, session) { DesktopDynamicEditorRoot(repository, session, scope) }
    DisposableEffect(root) { onDispose { root.close() } }
    return root
}

@Composable internal fun DesktopDynamicEditorRootHost(root: DesktopDynamicEditorRoot, hostWindow: Window?) {
    if (!root.isOwned()) return
    root.request?.let { request -> key(root, request) {
        val alive = remember { AtomicBoolean(true) }
        val owned = remember(root, request) { { alive.get() && root.owns(request) } }
        val selected = remember(root, request) { DesktopDynamicEditorSelectedImages(owned) }
        val operations = remember(root, request) {
            // The modal must retire its own uploads without retiring post-publish verification.
            root.operations.forEditor(owned)
        }
        val pickers = remember(selected, hostWindow) {
            DesktopDynamicEditorWindowsPickers(selected, owned, { hostWindow }, root::show)
        }
        DisposableEffect(selected) { onDispose { alive.set(false); selected.close() } }
        DesktopOriginalDynamicEditorHost(request.draft, request.dynamicId, operations, root.session,
            pickers::pickImages, pickers::chooseDateAndTime, selected::read,
            onDismiss = { root.dismiss(request) }, onPublished = { root.published(request, it) },
            onEdited = { _, _ -> root.edited(request) })
    } }
    root.feedback?.let { message ->
        AppAlertDialog(onDismissRequest = root::clearFeedback, text = { AppText(message) },
            confirmButton = { AppDialogAction(onClick = root::clearFeedback) { AppText("确定") } })
    }
}
