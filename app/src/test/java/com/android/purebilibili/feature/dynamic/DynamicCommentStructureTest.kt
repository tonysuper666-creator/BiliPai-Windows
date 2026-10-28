package com.android.purebilibili.feature.dynamic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DynamicCommentStructureTest {

    @Test
    fun `dynamic comment surfaces open the shared input dialog`() {
        val sheet = File(
            "src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt"
        ).readText()
        val host = File(
            "src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentInputHost.kt"
        ).readText()
        val detail = File(
            "src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt"
        ).readText()
        val screen = File(
            "src/main/java/com/android/purebilibili/feature/dynamic/DynamicScreen.kt"
        ).readText()
        val space = File(
            "src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt"
        ).readText()

        assertTrue(!sheet.contains("private fun DynamicCommentComposer("))
        assertTrue(!sheet.contains("DynamicInlineCommentComposer("))
        assertTrue(!sheet.contains("OutlinedTextField"))
        assertTrue(!sheet.contains("AppOutlinedTextField"))
        assertTrue(!sheet.contains("onPostComment"))
        assertTrue(sheet.contains("DynamicCommentEntryBar("))
        assertTrue(sheet.contains("onOpenComposer"))

        assertTrue(host.contains("CommentInputDialog("))
        assertTrue(host.contains("replyToName = replyTarget?.uname"))
        assertTrue(host.contains("allowImageAttachment = replyTarget == null"))
        assertTrue(host.contains("showSyncToDynamic = false"))
        assertTrue(host.contains("showProgressInsert = false"))
        assertTrue(host.contains("initialText = draft.text"))
        assertTrue(host.contains("onDraftChange"))

        assertTrue(detail.contains("DynamicCommentInputHost("))
        assertTrue(detail.contains("DynamicCommentEntryBar("))

        assertTrue(!screen.contains("DynamicCommentOverlayHost("))
        assertTrue(!screen.contains("DynamicCommentSheet"))
        assertTrue(space.contains("DynamicCommentOverlayHost("))
    }

    @Test
    fun `dynamic comments expose sort mode state and reset pagination when it changes`() {
        val source = File("src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt")
            .readText()

        assertTrue(source.contains("val dynamicCommentSortMode: StateFlow<CommentSortMode>"))
        assertTrue(source.contains("fun setDynamicCommentSortMode(mode: CommentSortMode)"))
        assertTrue(source.contains("hasMore = false, nextPage = 1"))
        assertTrue(source.contains("commentGrpcNextOffset = null"))
        assertTrue(source.contains("loadCommentsForDynamic(item)"))
    }

    @Test
    fun `dynamic sub replies use server sorting and cursor paging`() {
        val source = File("src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt")
            .readText()

        assertTrue(source.contains("paginationOffset = request.offset"))
        assertTrue(source.contains("CommentRepository.getMobileSortedSubCommentsForSubject("))
        assertTrue(source.contains("mode = sortMode.apiMode"))
        assertTrue(source.contains("grpcNextOffset = data.grpcNextOffset"))
    }

    @Test
    fun `dynamic detail reloads comments for each screen entry`() {
        val source = File("src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt")
            .readText()

        assertTrue(source.contains("interactionViewModel.openCommentSheet("))
        assertTrue(!source.contains("hasAutoOpenedComments"))
        assertTrue(!source.contains("rememberSaveable(\n        dynamicId"))
    }

    @Test
    fun `dynamic detail pins the comment composer outside the scrolling list`() {
        val source = File("src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt")
            .readText()
        val commentContent = source
            .substringAfter("val commentContent:")
            .substringBefore("val commentComposer:")

        assertTrue(source.contains("DynamicCommentEntryBar("))
        assertTrue(source.contains("shouldLoadMoreDynamicDetailComments("))
        assertTrue(source.contains("LaunchedEffect(detailListState, commentListState, useSplitLayout)"))
        assertTrue(commentContent.contains("dynamicInlineCommentItems("))
        assertTrue(!commentContent.contains("DynamicCommentEntryBar("))
        assertTrue(!source.contains("item(key = \"dynamic_detail_comment_composer\")"))
    }

    @Test
    fun `dynamic detail comment composer becomes a liquid dock when enabled`() {
        val source = File("src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt")
            .readText()
        val composer = source
            .substringAfter("val commentComposer:")
            .substringBefore("if (useSplitLayout)")

        assertTrue(!source.contains("detailDockBackdrop"))
        assertTrue(!composer.contains("BottomBarMatchedReusableLiquidDock("))
        assertTrue(composer.contains("liquidGlassEnabled = liquidGlassEnabled"))
        assertTrue(source.contains("shouldUseFloatingLiquidBottomInputBar("))
        assertTrue(source.contains("resolveBottomInputBarContentBottomPadding("))
        assertTrue(source.contains("if (liquidGlassEnabled) rememberChromeBackdropSource() else null"))
        assertTrue(source.contains("detailCommentSource.modifier.sharedGlobalWallpaperBackground(MaterialTheme.colorScheme.background)"))
        assertTrue(!source.contains(".layerBackdrop(detailCommentBackdrop)"))
        assertTrue(source.contains("bottom = commentContentBottomPadding"))

        val entryBarSource = File(
            "src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentEntryBar.kt"
        ).readText()
        assertTrue(entryBarSource.split("BottomBarMatchedReusableLiquidDock(").size - 1 == 1)
        assertTrue(entryBarSource.contains("reuseEnabled = true"))
        assertTrue(entryBarSource.contains("drawShellLens = true"))
        assertTrue(entryBarSource.contains("resolveFloatingDockGeometryScale(44f)"))
        assertTrue(entryBarSource.contains("resolveSharedBottomBarCapsuleShape()"))
        assertTrue(entryBarSource.contains("height(48.dp)"))
        assertTrue(entryBarSource.contains("CommentEntryBarContent("))
        assertTrue(entryBarSource.contains("containerColor"))
    }

    @Test
    fun `dynamic sub reply keeps thread open while opening shared composer`() {
        val detail = File(
            "src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt"
        ).readText()
        val viewModel = File(
            "src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt"
        ).readText()

        val replyHandler = detail
            .substringAfter("DynamicSubReplyPreviewHost(")
            .substringBefore("val postComment:")
            .substringAfter("onReplyClick = { reply ->")
            .substringBefore("},")

        assertTrue(replyHandler.contains("startCommentReply(reply)"))
        assertTrue(!replyHandler.contains("closeSubReply()"))

        val postComment = viewModel
            .substringAfter("fun postComment(")
            .substringBefore("private suspend fun uploadCommentPictures")
        assertTrue(postComment.contains("refreshSubReplies()"))
    }

    @Test
    fun `dynamic comment composer reuses shared draft state`() {
        val source = File("src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt")
            .readText()

        assertTrue(source.contains("CommentComposerDraftState()"))
        assertTrue(source.contains("fun openCommentComposer()"))
        assertTrue(source.contains("fun dismissCommentComposer()"))
        assertTrue(source.contains("commentComposerVisible.value = false"))
        assertTrue(source.contains("complete(draftSessionId, replyRpid, sentDraft)"))
        assertTrue(source.contains("isSendingComment"))
        assertTrue(source.contains("commentMentionSearchState"))
    }

    @Test
    fun `dynamic comment reload cancels and ignores the previous request`() {
        val source = File("src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt")
            .readText()

        assertTrue(source.contains("private var commentLoadJob: Job? = null"))
        assertTrue(source.contains("val requestId = ++commentLoadRequestId"))
        assertTrue(source.contains("commentLoadJob?.cancel()"))
        assertTrue(source.contains("if (requestId != commentLoadRequestId) return@launch"))
    }

    @Test
    fun `feed and space comment buttons open dynamic detail`() {
        val screenSource = File(
            "src/main/java/com/android/purebilibili/feature/dynamic/DynamicScreen.kt"
        ).readText()
        val spaceSource = File(
            "src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt"
        ).readText()
        val topicSource = File(
            "src/main/java/com/android/purebilibili/feature/search/TopicDetailScreen.kt"
        ).readText()

        assertTrue(screenSource.contains("onCommentClick = onDynamicDetailClick"))
        assertTrue(!screenSource.contains("onCommentClick = { viewModel.openCommentSheet(it) }"))
        assertTrue(spaceSource.contains("onCommentClick = { onDynamicDetailClick(dynamic.id_str) }"))
        assertTrue(topicSource.contains("onCommentClick = onDynamicDetailClick"))
    }
}
