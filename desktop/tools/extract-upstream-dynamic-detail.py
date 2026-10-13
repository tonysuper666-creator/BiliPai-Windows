"""Source-owned dynamic comment VM slice. No duplicate model/account/cache."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
from v036_dynamic_comment_cache import adapt_comment_region, generate_policy
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
    fields,body,source_proof=adapt_comment_region(repo,media,parser)
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
    private val commentCache: com.bilipai.desktop.data.DesktopDynamicCommentCacheBinding,
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
    private suspend fun ensurePageOwned() {
        currentCoroutineContext().ensureActive()
        if (!isOwned()) throw CancellationException("Reply owner retired")
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
    selected_original=__import__('v036_dynamic_comment_cache').read_sources()['DynamicViewModel.kt']
    emitted=[host.write(output,VM,selected_original,prefix+fields+body+'\n}\n','DesktopOriginalDynamicReplySession.kt')]
    emitted.append(generate_policy(output))
    import json
    (output/'v036-dynamic-comment-source-proof.json').write_text(json.dumps(source_proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
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
