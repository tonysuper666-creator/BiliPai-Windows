"""Source-owned dynamic comment VM slice. No duplicate model/account/cache."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import importlib.util
import sys
sys.dont_write_bytecode = True
BASE = 'app/src/main/java/com/android/purebilibili/'
VM = BASE + 'feature/dynamic/DynamicViewModel.kt'
VIDEO_VM = BASE + 'feature/video/viewmodel/VideoCommentViewModel.kt'

def load(repo, name, path):
    spec=importlib.util.spec_from_file_location(name,repo/path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')

def generate(repo,output):
    host=load(repo,'reply_vm_host','desktop/tools/extract-upstream-plugins.py')
    media=host.media_extractor(repo);parser=media.parser_for(repo)
    appearance=load(repo,'reply_vm_declarations','desktop/tools/extract-appearance-platform.py')
    original=read(repo,VM)
    fields=original[original.index('    private val _selectedDynamic ='):original.index('    // 点赞状态缓存')]
    # Expose the existing target owner for Root's guarded count projection.
    # The private selected detail fallback remains private and is never a page/cache authority.
    fields += '    internal val selectedCommentTarget: StateFlow<DynamicCommentTarget?> = _selectedCommentTarget.asStateFlow()\n'
    # Windows receipt metadata records a confirmed response even when its count is unchanged.
    # It owns no count/target/item schema, and never advances on reset/fallback.
    fields += '    private val _commentConfirmationRevision = MutableStateFlow(0L)\n'
    fields += '    internal val commentConfirmationRevision: StateFlow<Long> = _commentConfirmationRevision.asStateFlow()\n'
    begin=original.index('    private fun findDynamicById')
    end=original.index('    fun likeDynamic(',begin)
    body=original[begin:end]
    # A detail session is keyed to the existing raw DynamicItem identity. The
    # renderer never constructs a second feed/items cache to implement lookup.
    import textwrap
    lookup=textwrap.indent(media.function(original,'findDynamicById',parser).strip(),'    ')
    assert lookup in body
    body=body.replace(lookup,'''    private fun findDynamicById(dynamicId: String): DynamicItem? {
        if (dynamicId != subjectId || !isOwned()) return null
        return _selectedDynamic.value?.takeIf { it.id_str == dynamicId } ?: seedItem()?.takeIf { it.id_str == subjectId }
    }''',1)
    # Only Android ContentResolver/Uri admission is adapted. The original 9-image
    # validation, reply prohibition, ordered map, pictures payload and callbacks remain.
    upload = textwrap.indent(media.function(original,'uploadCommentPictures',parser).strip(),'    ')
    assert upload in body
    body=body.replace(upload,'    private suspend fun uploadCommentPictures(imageUris: List<String>): List<ReplyPicture> =\n        withContext(Dispatchers.IO) {\n            require(imageUris.size <= 9) { "最多选择 9 张图片" }\n            imageUris.mapIndexed { index, uri ->\n                requests.uploadCommentPicture(uri, index).getOrElse { throw it }\n            }\n        }',1)
    body=body.replace('List<Uri>', 'List<String>')
    body=body.replace('                val pictures = uploadCommentPictures(imageUris)\n',
                      '                val pictures = uploadCommentPictures(imageUris)\n                ensureRequestOwned()\n')
    body=body.replace('CommentRepository.','requests.')
    body=body.replace('        _selectedDynamic.value = item\n',
                      '        if (!isOwned()) return\n        _selectedDynamic.value = item\n')
    body=body.replace('        if (rpid <= 0L) return', '        if (!isOwned() || rpid <= 0L) return')
    body=body.replace('        _commentReplyTarget.value = resolveDynamicCommentReplyTarget(reply)',
                      '        if (!isOwned()) return\n        _commentReplyTarget.value = resolveDynamicCommentReplyTarget(reply)')
    body=body.replace('    fun clearCommentReplyTarget() {', '    fun clearCommentReplyTarget() {\n        if (!isOwned()) return')
    body=body.replace('    fun setDynamicCommentSortMode(mode: CommentSortMode) {',
                      '    fun setDynamicCommentSortMode(mode: CommentSortMode) {\n        if (!isOwned()) return')
    body=body.replace('    fun setSubReplySortMode(mode: SubReplySortMode) {',
                      '    fun setSubReplySortMode(mode: SubReplySortMode) {\n        if (!isOwned()) return')
    body=body.replace('    fun openSubReply(rootReply: ReplyItem, targetReplyId: Long = 0L) {',
                      '    fun openSubReply(rootReply: ReplyItem, targetReplyId: Long = 0L) {\n        if (!isOwned()) return')
    body=body.replace('    fun openSubReplyFromRoute(rootReplyId: Long, targetReplyId: Long = 0L): Boolean {',
                      '    fun openSubReplyFromRoute(rootReplyId: Long, targetReplyId: Long = 0L): Boolean {\n        if (!isOwned()) return false')
    body=body.replace('DynamicRepository.getDynamicDetail(', 'requests.getDynamicDetail(')
    body=body.replace('val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache\n                if (csrf.isNullOrEmpty())',
                      'if (!requests.hasCsrf())')
    body=body.replace('viewModelScope.launch {','launchOwned {')
    body=body.replace('return@launch','return@launchOwned')
    # All source result callbacks run under the captured task/epoch/subject
    # generation. An old finally must not clear a replacement loading gate.
    body=body.replace('.onSuccess { data ->','.onSuccess { data ->\n                    ensureRequestOwned()')
    body=body.replace('.onSuccess {\n','.onSuccess {\n                    ensureRequestOwned()\n')
    body=body.replace('.onFailure { error ->','.onFailure { error ->\n                    ensureRequestOwned()')
    body=body.replace('fullDetail ->\n                        effectiveItem', 'fullDetail ->\n                        ensureRequestOwned()\n                        effectiveItem')
    body=body.replace('                if (requestId != commentLoadRequestId)',
                      '                ensureRequestOwned()\n                if (requestId != commentLoadRequestId)')
    body=body.replace('                if (response.isSuccess) {','                ensureRequestOwned()\n                if (response.isSuccess) {')
    body=body.replace('            } catch (e: Exception) {','            } catch (e: CancellationException) {\n                throw e\n            } catch (e: Exception) {')
    body=body.replace('            } catch (e: CancellationException) {\n                throw e\n            } catch (e: CancellationException) {\n                throw e\n',
                      '            } catch (e: CancellationException) {\n                throw e\n')
    # Error/result callbacks also need the exact request ownership before writes.
    body=body.replace('onSuccess = {','onSuccess = {\n                    ensureRequestOwned()\n')
    body=body.replace('onFailure = { error ->','onFailure = { error ->\n                    ensureRequestOwned()\n')
    body=body.replace('onFailure = { onResult','onFailure = { ensureRequestOwned()\n                    onResult')
    body=body.replace('                if (requestId == commentLoadRequestId) {',
                      '                if (isOwned() && requestId == commentLoadRequestId) {')
    # Account/content identifiers and transport text are not printed by the
    # Windows adapter. Preserve original control flow and UI failure text.
    import re
    body=re.sub(r'^\s*com\.android\.purebilibili\.core\.util\.Logger\.[dew]\([^\n]+\)\n','',body,flags=re.M)
    body=re.sub(r'^\s*com\.android\.purebilibili\.core\.util\.Logger\.[dew]\(\n[\s\S]*?^\s*\)\n', '\n', body,flags=re.M)
    body=body.replace('                e.printStackTrace()\n','')
    body=body.replace('loadCommentsForDynamic(\n            item = item,',
                      'if (!isOwned() || item.id_str != subjectId) return\n        loadCommentsForDynamic(\n            item = item,')
    body=body.replace('        if (!isOwned()) return\n        _selectedDynamic.value = item\n        if (!isOwned() || item.id_str != subjectId) return',
                      '        if (!isOwned() || item.id_str != subjectId) return\n        _selectedDynamic.value = item')
    # A main refresh retires the exact comment request generation. Close that
    # generation's separate thread request too, rather than leaving a rejected
    # old read at isLoading=true forever. Raw items are kept, never re-cached.
    body=body.replace('        val requestId = ++commentLoadRequestId\n        commentLoadJob?.cancel()',
                      '        val requestId = ++commentLoadRequestId\n'
                      '        subReplyLoadJob?.cancel()\n'
                      '        _subReplyState.update { it.copy(visible = false, isLoading = false, error = null) }\n'
                      '        commentLoadJob?.cancel()',1)
    body=body.replace('            } catch (e: Exception) {\n                onResult(false, e.message ?: "网络错误")',
                      '            } catch (e: Exception) {\n                ensureRequestOwned()\n                onResult(false, e.message ?: "网络错误")')
    # Keep the exact source request/reducers/rollback. Only synchronous Windows
    # admission serializes like/hate for one rpid; unique completion holders
    # release even if a pre-cancelled/queued coroutine never enters its body.
    for name,next_name,mutation_line in (
        ('likeComment','hateComment','        _comments.value = applyDynamicCommentLikeInList'),
        ('hateComment','deleteDynamicComment','        val toHated = !isDynamicCommentHated(current)')):
        start=body.index('    fun '+name+'(');end=body.index('    fun '+next_name+'(',start)
        block=body[start:end]
        assert mutation_line in block and block.count('        launchOwned {')==1
        block=block.replace(mutation_line,'        val mutationHolder = acquireReplyMutation(rpid) ?: return\n'+mutation_line,1)
        block=block.replace('        launchOwned {','        val mutationJob = launchOwned {',1)
        marker='\n        }\n    }'
        assert block.count(marker)==1
        block=block.replace(marker,'\n        }\n        mutationJob.invokeOnCompletion { replyMutationHolders.remove(rpid, mutationHolder) }\n    }',1)
        body=body[:start]+block+body[end:]
    # Windows dispatch can queue the request after the original composer clears
    # its reply UI. Capture the original immutable target at admission, matching
    # the request parameters read synchronously by Android Main.immediate.
    post_start = body.index('    fun postComment(')
    post_end = body.index('    fun likeComment(', post_start)
    post = body[post_start:post_end]
    assert post.count('        launchOwned {') == 1
    assert post.count('                val replyTarget = _commentReplyTarget.value\n') == 1
    post = post.replace('        launchOwned {',
                        '        if (!isOwned()) return\n'
                        '        val replyTarget = _commentReplyTarget.value\n'
                        '        launchOwned {', 1)
    post = post.replace('                val replyTarget = _commentReplyTarget.value\n', '', 1)
    # Windows-only completion of the exact admitted submission. Refresh/sort may
    # retire its read generation while retaining the same mounted composer.
    # Only cancellation releases busy; this seam emits no business receipt/toast.
    post = post.replace('        onResult: (Boolean, String) -> Unit,',
        '        onSubmissionCancelled: () -> Unit = {},\n        onResult: (Boolean, String) -> Unit,', 1)
    post = post.replace('onResult(', 'deliverSubmissionResult(')
    post = post.replace('        val replyTarget = _commentReplyTarget.value\n', '        val replyTarget = _commentReplyTarget.value\n        val submissionId = commentSubmissionSequence.incrementAndGet()\n        val resultDelivered = AtomicBoolean(false)\n        fun deliverSubmissionResult(success: Boolean, message: String) {\n            if (isOwned() && commentSubmissionSequence.get() == submissionId && resultDelivered.compareAndSet(false, true)) {\n                onResult(success, message)\n            }\n        }\n', 1)
    post = post.replace('        launchOwned {', '        val submissionJob = launchOwned {', 1)
    completion_marker = '        }\n    }\n\n    private suspend fun uploadCommentPictures'
    assert post.count(completion_marker) == 1
    post = post.replace(completion_marker, '        }\n        submissionJob.invokeOnCompletion { failure ->\n            if (failure is CancellationException && isOwned() && commentSubmissionSequence.get() == submissionId &&\n                resultDelivered.compareAndSet(false, true)) {\n                onSubmissionCancelled()\n            }\n        }\n    }\n\n    private suspend fun uploadCommentPictures', 1)
    body = body[:post_start] + post + body[post_end:]
    # The exact selected response is already checked for task ownership,
    # current request id and current sort. Keep its original count assignment.
    selected_count = '                    _commentTotalCount.value = selected.totalCount\n'
    assert body.count(selected_count) == 1
    body = body.replace(selected_count,
                        selected_count + '                    _commentConfirmationRevision.update { it + 1L }\n', 1)
    # Delete success likewise runs after ensureRequestOwned in the original
    # fold branch; a reset/fallback or cancelled failure produces no receipt.
    deleted_count = '                    _commentTotalCount.value = (_commentTotalCount.value - 1).coerceAtLeast(0)\n'
    assert body.count(deleted_count) == 1
    body = body.replace(deleted_count,
                        deleted_count + '                    _commentConfirmationRevision.update { it + 1L }\n', 1)
    # Capture a new original request id before admission; close cancels owned
    # child jobs, while sorting/refreshing keep the same subject session.
    video=read(repo,VIDEO_VM)
    start_dissolve=media.function(video,'startSubDissolve',parser)
    start_dissolve=start_dissolve.replace('    val current = _subReplyState.value',
                                        '    if (!isOwned() || rpid <= 0L) return\n    val current = _subReplyState.value')
    body += '\n'+start_dissolve+'\n'
    body=body.replace('    fun deleteDynamicComment(rpid: Long, onResult: (Boolean, String) -> Unit) {',
                      '    fun deleteDynamicComment(rpid: Long, onResult: (Boolean, String) -> Unit) {\n'
                      '        if (!isOwned()) return\n'
                      '        _subReplyState.update { it.copy(dissolvingIds = (it.dissolvingIds - rpid).toImmutableSet()) }')
    prefix='''package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.ui.DesktopDynamicReplyRequests
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

internal class DesktopOriginalDynamicReplySession(
    private val subjectId: String,
    parentScope: CoroutineScope,
    private val requests: DesktopDynamicReplyRequests,
    private val seedItem: () -> DynamicItem?,
    private val stillOwned: () -> Boolean = { true },
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val viewModelScope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    fun isOwned(): Boolean = alive.get() && ownedJob.isActive && stillOwned() && requests.isOwned()
    private val commentSubmissionSequence = AtomicLong(0L)
    private val replyMutationHolders = ConcurrentHashMap<Long, Any>()
    private fun acquireReplyMutation(rpid: Long): Any? {
        val holder = Any()
        return holder.takeIf { replyMutationHolders.putIfAbsent(rpid, holder) == null }
    }
    private inner class ReplyTaskScope(
        private val requestId: Long,
        override val coroutineContext: kotlin.coroutines.CoroutineContext,
    ) : CoroutineScope {
        fun ensureRequestOwned() {
            coroutineContext.ensureActive()
            if (!isOwned() || requestId != commentLoadRequestId) throw CancellationException("Reply owner retired")
        }
    }
    private fun launchOwned(block: suspend ReplyTaskScope.() -> Unit): Job {
        val requestId = commentLoadRequestId
        return viewModelScope.launch {
            val task = ReplyTaskScope(requestId, coroutineContext)
            task.ensureRequestOwned()
            task.block()
        }
    }
    override fun close() {
        if (alive.getAndSet(false)) {
            commentLoadRequestId++
            ownedJob.cancel()
        }
    }
'''
    output.mkdir(parents=True,exist_ok=True)
    emitted=[host.write(output,VM,original,prefix+fields+body+'\n}\n','DesktopOriginalDynamicReplySession.kt')]
    original=read(repo,VIDEO_VM)
    # These pure helpers are not in the editor's original state/schema producer.
    names=['resolveSubReplyRemoteTotalCount','resolveSubReplyLoadedTotalCount','resolveRoutedCommentRootReply']
    helpers='\n\n'.join(media.function(original,name,parser) for name in names)
    emitted.append(host.write(output,VIDEO_VM,original,'''package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.*
'''+helpers,'DesktopOriginalDynamicReplyCountPolicies.kt'))
    path=BASE+'feature/dynamic/DynamicInteractionPolicy.kt'
    original=read(repo,path)
    emitted.append(host.write(output,path,original,'''package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
'''+appearance.declarations(parser,original,['shouldApplyDynamicCommentPageResult']),'DesktopOriginalDynamicReplyPagingGuard.kt'))
    return emitted

if __name__=='__main__':
    import argparse
    cli=argparse.ArgumentParser();cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True)
    args=cli.parse_args();generate(args.repo,args.output)
