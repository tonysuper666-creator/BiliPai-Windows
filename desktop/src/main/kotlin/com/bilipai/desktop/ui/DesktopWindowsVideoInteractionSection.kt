package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.purebilibili.core.store.DesktopOriginalVideoHolderSendSettings
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.AppModalPresentation
import com.android.purebilibili.feature.video.note.buildVideoNoteShareText
import com.android.purebilibili.feature.video.share.VideoShareSheetHost
import com.android.purebilibili.feature.video.share.buildVideoSharePayload
import com.android.purebilibili.feature.video.ui.components.DanmakuSendDialog
import com.android.purebilibili.feature.video.ui.section.*
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

internal enum class DesktopWindowsVideoInteraction { DANMAKU, SHARE, AI_SUMMARY, NOTES }

internal class DesktopWindowsVideoInteractionPresentation(
    val assembly: DesktopOriginalVideoOwnerAssembly,
    val sourceOwner: DesktopOriginalVideoAcceptedPublication,
    val mode: DesktopWindowsVideoInteraction,
    val stillOwned: () -> Boolean,
    val dismiss: () -> Unit,
    val stillPresented: () -> Boolean = stillOwned,
    val visible: Boolean = true,
)

/** One window's lexical lease over the original factory admission. Payload/native
 * I/O runs outside the gate; only bounded state publication belongs inside it. */
internal class DesktopWindowsVideoInteractionLease(
    private val stillOwned: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    fun isOwned(): Boolean = alive.get() && stillOwned()
    fun commit(action: () -> Unit): Boolean {
        if (!isOwned()) return false
        var applied = false
        return admit { if (isOwned()) { action(); applied = true } } && applied
    }
    fun effect(action: () -> Unit): Boolean {
        if (!commit {}) return false
        if (!isOwned()) return false
        action() // Clipboard, browser and system sharing never execute in the admission.
        return true
    }
    override fun close() { alive.set(false) }
}

/** The existing native actor may outlive the sheet only AFTER it actually opens
 * and the captured source admits that result. This is not a playback authority. */
internal class DesktopWindowsNativeShareHandoff(
    private val presentationOwned: () -> Boolean,
    private val sourceOwned: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) {
    private val handedOff = AtomicBoolean(false)
    private val rejected = AtomicBoolean(false)
    fun isOwned(): Boolean = !rejected.get() && sourceOwned() && (handedOff.get() || presentationOwned())
    private fun admitPresentation(action: () -> Unit): Boolean {
        var applied = false
        return admit {
            if (!rejected.get() && presentationOwned() && sourceOwned()) { action(); applied = true }
        } && applied
    }
    suspend fun open(openNative: suspend (() -> Boolean) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!admitPresentation {}) { rejected.set(true); return false }
        try {
            val opened = openNative(::isOwned) // Native prepare/show stays outside admission.
            currentCoroutineContext().ensureActive()
            if (!opened || !admitPresentation { handedOff.set(true) } || !sourceOwned()) {
                rejected.set(true)
                return false
            }
            return true
        } catch (failure: Throwable) { rejected.set(true); throw failure }
    }
}

private val LocalDesktopWindowsVideoInteractionWindow = staticCompositionLocalOf { false }

/** Sole container replacement for the whole original DanmakuSendDialog body. */
@Composable internal fun DesktopWindowsVideoInteractionDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    content: @Composable () -> Unit,
) {
    if (LocalDesktopWindowsVideoInteractionWindow.current)
        DesktopWindowsPlayerDialog("发送弹幕", onDismissRequest, properties.dismissOnBackPress,
            preferredHeightDp = 720, content = content)
    else Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
}

/** In the already owned native share window, preserve all original sheet content
 * and branch selection without creating another Canvas-overlapping popup layer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DesktopWindowsVideoInteractionModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState,
    presentationOverride: AppModalPresentation? = null,
    sheetSurfaceModifier: Modifier = Modifier,
    dragHandle: @Composable (() -> Unit)? = { Spacer(Modifier.height(24.dp)) },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalDesktopWindowsVideoInteractionWindow.current)
        Column(modifier.fillMaxSize().then(sheetSurfaceModifier)) {
            dragHandle?.invoke()
            content()
        }
    else AppModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier,
        sheetState = sheetState, presentationOverride = presentationOverride,
        sheetSurfaceModifier = sheetSurfaceModifier, dragHandle = dragHandle, content = content)
}

/** Complete original components use the same whole VM, settings and retained
 * Root effects. This window does not fetch metadata or own playback independently. */
@Composable internal fun DesktopWindowsVideoInteractionSection(
    presentation: DesktopWindowsVideoInteractionPresentation,
    settings: DesktopOriginalPlayerSettingsContext,
    admit: ((() -> Unit) -> Boolean),
    shareForPresentation: (() -> Boolean, ((() -> Unit) -> Boolean), () -> Boolean) -> DesktopVideoShareBindings,
    nativeHandoffOwned: () -> Boolean,
    openLink: (String) -> Unit,
    shareText: (String, String, DesktopWindowsNativeShareHandoff) -> Unit,
) {
    val assembly = presentation.assembly
    val source = presentation.sourceOwner
    key(assembly, source) {
        val latestOwned by rememberUpdatedState(presentation.stillOwned)
        val latestPresented by rememberUpdatedState(presentation.stillPresented)
        val latestAdmission by rememberUpdatedState(admit)
        val latestDismiss by rememberUpdatedState(presentation.dismiss)
        val scope = rememberCoroutineScope()
        val lease = remember {
            DesktopWindowsVideoInteractionLease(
                { scope.isActive && latestOwned() && assembly.owns() && assembly.native.isCurrent(source) &&
                    assembly.playback.captureDesktopPlaybackState().let { raw -> raw is VideoPlaybackUiState.Success &&
                        raw.info.bvid == source.request.bvid && raw.info.cid == source.request.cid } },
                { action -> latestAdmission(action) },
            )
        }
        val context = remember(lease, settings) { DesktopOriginalPlayerSettingsContext(settings.pluginContext,
            { lease.isOwned() && settings.isCurrentForOriginalWrite() },
            { action -> lease.commit(action) }) }
        val share = remember(lease) {
            val subject = assembly.domains.engagement.uiState.value.subject?.takeIf {
                it.bvid == source.request.bvid && it.cid == source.request.cid
            }
            val feedback = subject?.let {
                DesktopOriginalVideoEngagementPresentation(source, it,
                    lease::isOwned, lease::commit, nativeHandoffOwned,
                    { action -> latestAdmission(action) })
            }
            shareForPresentation(lease::isOwned, lease::commit, nativeHandoffOwned)
                .whilePresented { latestPresented() }.withPreparedFeedback { bvid ->
                if (feedback != null) withContext(feedback.context) {
                    currentCoroutineContext().ensureActive()
                    assembly.domains.engagement.showShareFeedback(bvid)
                }
            }
        }
        fun shareNote(title: String, text: String) {
            val handoff = DesktopWindowsNativeShareHandoff(lease::isOwned, nativeHandoffOwned, lease::commit)
            lease.effect { shareText(title, text, handoff) }
        }
        var composerStamp by remember { mutableStateOf<Any?>(null) }
        DisposableEffect(lease) {
            onDispose {
                // Retire first. Clearing only the exact old composer stamp cannot
                // resume a successor source or clear a newer opened composer.
                lease.close()
                composerStamp?.let(assembly.playback::retireDesktopDanmakuComposer)
            }
        }
        val state by assembly.playback.uiState.collectAsState()
        val success = (state as? VideoPlaybackUiState.Success)?.takeIf {
            it.info.bvid == source.request.bvid && it.info.cid == source.request.cid
        }
        val showDanmaku by assembly.playback.showDanmakuDialog.collectAsState()
        val sending by assembly.playback.isSendingDanmaku.collectAsState()
        val drafts by assembly.playback.composerDrafts.collectAsState()
        val color by DesktopOriginalVideoHolderSendSettings.getDanmakuSendColor(context).collectAsState(16_777_215)
        val mode by DesktopOriginalVideoHolderSendSettings.getDanmakuSendMode(context).collectAsState(1)
        val fontSize by DesktopOriginalVideoHolderSendSettings.getDanmakuSendFontSize(context).collectAsState(25)
        fun dismiss() { lease.commit { latestDismiss() } }
        fun dismissDanmaku() {
            lease.commit {
                if (assembly.playback.captureDesktopDanmakuComposerStamp() === composerStamp)
                    assembly.playback.hideDanmakuSendDialog()
                latestDismiss()
            }
        }
        LaunchedEffect(presentation.mode, lease) {
            if (presentation.mode == DesktopWindowsVideoInteraction.DANMAKU) {
                var opened = false
                lease.commit {
                    opened = assembly.playback.showDanmakuSendDialog()
                    composerStamp = assembly.playback.captureDesktopDanmakuComposerStamp()
                    if (!opened) latestDismiss()
                }
            }
        }
        LaunchedEffect(showDanmaku, composerStamp) {
            if (presentation.mode == DesktopWindowsVideoInteraction.DANMAKU && composerStamp != null &&
                !assembly.playback.showDanmakuDialog.value) dismiss()
        }
        // A positive content gate avoids a non-local-return marker escaping
        // Compose's inline key lowering; all original source checks remain here.
        if (success != null && lease.isOwned()) CompositionLocalProvider(LocalDesktopOriginalPlayerSettingsContext provides context,
            LocalDesktopWindowsVideoInteractionWindow provides true) {
            when (presentation.mode) {
                DesktopWindowsVideoInteraction.DANMAKU -> DanmakuSendDialog(
                    visible = showDanmaku, onDismiss = ::dismissDanmaku, isSending = sending,
                    initialColor = color, initialMode = mode, initialFontSize = fontSize,
                    initialText = drafts.danmaku.text, initialAttentionCommand = drafts.danmaku.attentionCommand,
                    onSend = { text, selectedColor, selectedMode, selectedFont, attention ->
                        lease.commit { assembly.playback.sendDanmaku(text, selectedColor, selectedMode, selectedFont, attention) }
                    },
                    onDraftChange = { text, attention -> lease.commit { assembly.playback.updateDanmakuDraft(text, attention) } },
                    onSelectionChange = { selectedColor, selectedMode, selectedFont ->
                        scope.launch {
                            DesktopOriginalPlaybackPreferenceOperation.run(context, lease::isOwned) {
                                DesktopOriginalVideoHolderSendSettings.setDanmakuSendColor(context, selectedColor)
                                DesktopOriginalVideoHolderSendSettings.setDanmakuSendMode(context, selectedMode)
                                DesktopOriginalVideoHolderSendSettings.setDanmakuSendFontSize(context, selectedFont)
                            }
                        }
                    },
                )
                DesktopWindowsVideoInteraction.SHARE -> DesktopWindowsPlayerDialog("分享视频", ::dismiss,
                    preferredHeightDp = 480, presentationVisible = presentation.visible) {
                    CompositionLocalProvider(LocalDesktopVideoShareBindings provides share) {
                        VideoShareSheetHost(buildVideoSharePayload(success.info.title, success.info.bvid,
                            success.info.pic, success.info.owner.name,
                            com.android.purebilibili.core.util.FormatUtils.formatStat(success.info.stat.view.toLong())), ::dismiss)
                    }
                }
                DesktopWindowsVideoInteraction.AI_SUMMARY -> DesktopWindowsPlayerDialog(
                    if (success.videoNoteState.editorVisible) "视频笔记" else "AI 总结", {
                        lease.commit { assembly.playback.closeVideoNoteEditor(); latestDismiss() }
                    }) {
                    AiSummarySheet(!success.videoNoteState.editorVisible, success.aiSummary, success.aiSummaryPrompt, ::dismiss,
                        onTimestampClick = { time -> lease.commit { assembly.playback.seekTo(time) } },
                        onRetry = { lease.commit { assembly.playback.retryAiSummary() } },
                        onCreateNoteDraft = { lease.commit { assembly.playback.createVideoNoteDraftFromAiSummary() } })
                    // Creating a draft uses the original editor state; no document copy is owned here.
                    if (success.videoNoteState.editorVisible) DesktopWindowsVideoNoteEditor(success, assembly,
                        lease, ::dismiss, ::shareNote)
                }
                DesktopWindowsVideoInteraction.NOTES -> DesktopWindowsPlayerDialog("视频笔记", {
                    lease.commit { assembly.playback.closeVideoNoteEditor(); latestDismiss() }
                }) {
                    var confirmDelete by remember { mutableStateOf(false) }
                    if (success.videoNoteState.editorVisible) DesktopWindowsVideoNoteEditor(success, assembly,
                        lease, ::dismiss, ::shareNote)
                    else VideoNoteListSheet(!confirmDelete, success.videoNoteState, success.isLoggedIn, ::dismiss,
                        onCreateOrEditClick = { lease.commit { assembly.playback.openVideoNoteEditor() } },
                        onOfficialEditorClick = { lease.effect { openLink("https://www.bilibili.com/h5/note-app?oid=${success.info.aid}&pagefrom=ugcvideo") } },
                        onRetryClick = { lease.commit { assembly.playback.retryVideoNote() } },
                        onDeleteClick = { lease.commit { confirmDelete = true } },
                        onShareClick = { document -> shareNote(document.title.ifBlank { success.info.title },
                            buildVideoNoteShareText(success.info.title, success.info.bvid, document, false)) },
                        onPublicNoteClick = { cvid, _ -> lease.effect { openLink("https://www.bilibili.com/read/cv$cvid") } },
                        onAuthorClick = { mid -> if (mid > 0L) lease.effect { openLink("https://space.bilibili.com/$mid") } },
                        onLoadMore = { lease.commit { assembly.playback.loadMorePublicVideoNotes() } },
                    )
                    VideoNoteDeleteConfirmDialog(confirmDelete, success.videoNoteState.deleting,
                        onConfirm = { lease.commit { confirmDelete = false; assembly.playback.deleteVideoNote(); latestDismiss() } },
                        onDismiss = { lease.commit { confirmDelete = false; latestDismiss() } })
                }
            }
        }
    }
}

@Composable private fun DesktopWindowsVideoNoteEditor(
    success: VideoPlaybackUiState.Success,
    assembly: DesktopOriginalVideoOwnerAssembly,
    lease: DesktopWindowsVideoInteractionLease,
    dismiss: () -> Unit,
    shareText: (String, String) -> Unit,
) {
    VideoNoteEditorSheet(success.videoNoteState,
        onDismiss = { lease.commit { assembly.playback.closeVideoNoteEditor(); dismiss() } },
        onDocumentChange = { document -> lease.commit { assembly.playback.updateVideoNoteEditorDocument(document) } },
        onTimestampClick = { time -> lease.commit { assembly.playback.seekTo(time) } },
        onShare = { document -> lease.effect { shareText(document.title.ifBlank { success.info.title },
            buildVideoNoteShareText(success.info.title, success.info.bvid, document,
                success.videoNoteState.editorFromAiSummary)) } },
        onSave = { document -> lease.commit { assembly.playback.saveVideoNote(document) } },
        currentTimestampProvider = {
            var time: com.android.purebilibili.feature.video.note.VideoNoteBlock.Timestamp? = null
            lease.commit { time = assembly.playback.currentVideoNoteTimestamp() }; time
        })
}
