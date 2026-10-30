@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.test.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.android.purebilibili.feature.video.screen.DesktopOriginalVideoCommentInputOverlay


import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.commentDraftTextFieldValue
import com.android.purebilibili.feature.video.viewmodel.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Actual production composer with delayed request completion; drafts remain memory-only. */
@org.junit.jupiter.api.parallel.ResourceLock("coil3.SingletonImageLoader")
class DesktopCommentDraftSessionTest {
    private data class Add(val aid: Long, val type: Int, val message: String,
        val root: Long, val parent: Long, val pictures: List<ReplyPicture>, val sync: Boolean)
    private class Harness : AutoCloseable {
        @Volatile var account = true
        @Volatile var source: Any = Any()
        @Volatile var permit = true
        var info = ViewInfo(bvid="BVfixture",aid=170001,cid=7007)
        var generation = 1L
        val feedback = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val adds = CopyOnWriteArrayList<Add>()
        val uploads = CopyOnWriteArrayList<Pair<String,Int>>()
        val mentionCalls = CopyOnWriteArrayList<String>()
        var emoteLoads = 0
        var addResult: suspend () -> Result<ReplyItem?> = { Result.success(ReplyItem(rpid=900,oid=170001)) }
        var uploadResult: suspend (String,Int) -> Result<ReplyPicture> = { uri,_ -> Result.success(ReplyPicture(imgSrc=uri)) }
        var mentionResult: suspend (String) -> Result<List<MentionSearchUser>> = { Result.success(listOf(MentionSearchUser(uid=1,name=it))) }
        var emoteResult: suspend () -> Result<List<EmotePackage>> = { Result.success(listOf(EmotePackage(1,"原表情",emote=emptyList()))) }
        private val gate=Any()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined+CoroutineExceptionHandler { _,e -> failures+=e })
        private fun owned()=account && scope.isActive
        private fun commit(action:()->Unit):Boolean=synchronized(gate) { if (owned()) {action();true} else false }
        val requests=object:DesktopVideoCommentRequests {
            override fun isOwned()=owned()
            override fun currentMid()=111L
            override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=Result.success(ReplyData(replies=emptyList(),cursor=ReplyCursor(isEnd=true)))
            override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long)=Result.success(ReplyData())
            override suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?)=Result.success(ReplyData())
            override suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture> { uploads+=source to index;return uploadResult(source,index) }
            override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean):Result<ReplyItem?> {
                currentCoroutineContext().ensureActive();if(!owned())throw CancellationException("Account retired")
                adds+=Add(oid,type,message,root,parent,pictures.toList(),syncToDynamic);return addResult()
            }
            override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean)=error("No reaction in composer")
            override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean)=error("No reaction in composer")
            override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long)=error("No delete in composer")
            override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean)=error("No top in composer")
            override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String)=error("No report in composer")
            override suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long,hasPictures:Boolean,sentAtSeconds:Long,waitMs:Long):Result<CommentFraudStatus> =error("Fraud disabled in synthetic test")
            override suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int,root:Long,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?)=error("No persistence")
        }
        val environment=DesktopOriginalVideoComposerEnvironment(scope,::owned,::commit,requests,{info},
            { emoteLoads++;emoteResult() }, { keyword -> mentionCalls+=keyword;mentionResult(keyword) }, { message -> feedback+=message })
        val vm=VideoComposerViewModel(environment)
        fun subject()=VideoSubjectSnapshot(info.bvid,info.cid,info.aid,42,"标题","",60000,generation)
        init { vm.bindSubject(subject()) }
        fun presentation(captured:Any=source)=DesktopWindowsCommentPresentation(captured,null,
            { owned() && source===captured }, { action -> synchronized(gate) { if(owned()&&permit&&source===captured) {action();true}else false } })
        fun open(p:DesktopWindowsCommentPresentation=presentation(),reply:ReplyItem?=null)=checkNotNull(vm.openCommentComposer(p,reply))
        suspend fun settled() { withTimeout(3000) { vm.isSendingComment.first { !it } } }
        override fun close() { scope.cancel();vm.close() }
    }


    @Test fun identicalReeditSurvivesSuccessAndRemainsEditableWithOriginalReceipt(): Unit = runBlocking {
        Harness().use { h ->
            val pending=CompletableDeferred<Result<ReplyItem?>>()
            h.addResult={pending.await()}
            val p=h.presentation(); val stamp=h.open(p)
            h.vm.changeCommentDraft(stamp,"A",emptyList(),false,1,1)
            val original=h.vm.composerDrafts.value.comments.drafts[0L]
            h.vm.submitComment(stamp,"A",emptyList(),false)
            withTimeout(3000) { while(h.adds.isEmpty()) yield() }
            h.vm.changeCommentDraft(stamp,"A",emptyList(),false,1,1)
            val replacement=h.vm.composerDrafts.value.comments.drafts[0L]
            assertNotSame(original,replacement)
            pending.complete(Result.success(ReplyItem(rpid=900,oid=170001)));h.settled()
            assertSame(replacement,h.vm.composerDrafts.value.comments.drafts[0L])
            assertTrue(h.vm.showCommentDialog.value);assertSame(stamp,h.vm.commentStamp.value)
            assertEquals(170001L,h.vm.commentSentEvent.first().aid)
            assertEquals("A",h.adds.single().message)
            assertTrue(h.vm.changeCommentDraft(stamp,"after success",emptyList(),false))
            assertEquals("after success",h.vm.composerDrafts.value.comments[0L]?.text)
            p.close(); assertTrue(h.failures.isEmpty())
        }
    }
    @Test fun videoAndAccountReplacementCannotExposePreviousDraftOrUtf16Selection(): Unit = runBlocking {
        Harness().use { old ->
            val p=old.presentation();val stamp=old.open(p)
            old.vm.changeCommentDraft(stamp,"A😃BCD",emptyList(),false,5,1)
            assertEquals(5,old.vm.composerDrafts.value.comments[0L]?.selectionStart)
            old.info=ViewInfo(bvid="BVnext",aid=170002,cid=7008);old.generation++
            old.vm.bindSubject(old.subject());val next=old.open(p)
            assertTrue(old.vm.composerDrafts.value.comments.drafts.isEmpty())
            assertTrue(old.vm.composerDrafts.value.comments.desktopSelections.isEmpty())
            assertFalse(old.vm.changeCommentDraft(stamp,"late",emptyList(),false,5,1))
            old.vm.changeCommentDraft(next,"new video",emptyList(),false,2,0)
            old.account=false
            assertFalse(old.vm.changeCommentDraft(next,"retired account",emptyList(),false))
            Harness().use { account ->
                val fresh=account.open()
                assertTrue(account.vm.composerDrafts.value.comments.drafts.isEmpty())
                assertTrue(account.vm.composerDrafts.value.comments.desktopSelections.isEmpty())
                assertNull(account.vm.composerDrafts.value.comments[0L])
                account.vm.changeCommentDraft(fresh,"new account",emptyList(),false)
                assertEquals(-1,account.vm.composerDrafts.value.comments[0L]?.selectionStart)
            }
            p.close()
        }
    }
    @Test fun replySwitchAndReopenRejectCapturedOldSessionWithoutLosingReceipt(): Unit = runBlocking {
        Harness().use { h ->
            val pending=CompletableDeferred<Result<ReplyItem?>>()
            h.addResult={pending.await()};val p=h.presentation();val root=h.open(p)
            h.vm.changeCommentDraft(root,"root",emptyList(),false,1,0)
            h.vm.submitComment(root,"root",emptyList(),false)
            withTimeout(3000) { while(h.adds.isEmpty())yield() }
            val child=h.open(p,ReplyItem(rpid=99,root=90))
            assertTrue(child.draftSessionId>root.draftSessionId)
            h.vm.changeCommentDraft(child,"child",emptyList(),false,3,0)
            assertFalse(h.vm.changeCommentDraft(root,"stale",emptyList(),false))
            h.vm.dismissCommentComposer(child);val reopened=h.open(p,ReplyItem(rpid=99,root=90))
            assertFalse(h.vm.changeCommentDraft(child,"late",emptyList(),false))
            pending.complete(Result.success(null));h.settled()
            assertSame(reopened,h.vm.commentStamp.value);assertTrue(h.vm.showCommentDialog.value)
            assertEquals("child",h.vm.composerDrafts.value.comments[99L]?.text)
            assertEquals(3,h.vm.composerDrafts.value.comments[99L]?.selectionStart)
            assertEquals(170001L,h.vm.commentSentEvent.first().aid);p.close()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class, coil3.annotation.DelicateCoilApi::class)
    @Test fun actualOverlayRejectsDisposedEditorCallbackAfterDraftSessionReopens(): Unit = runBlocking {
        Harness().use { h ->
            val folder=java.nio.file.Files.createTempDirectory("draft-overlay")
            val context=DesktopPluginContext(DesktopPluginStore(folder))
            val previousLoader=coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
            coil3.SingletonImageLoader.reset()
            val images=DesktopApplicationImageLoader(
                com.bilipai.desktop.data.DesktopRepository(com.bilipai.desktop.data.DesktopSessionStore.temporary()),
                folder.resolve("images"), { h.account })
            val port=java.lang.reflect.Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
                arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
                when(method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "DraftEditorNoNetworkPort"
                    "getContext" -> context
                    "isOwned" -> h.account
                    else -> error("Unexpected editor port: ${method.name}")
                }
            } as DesktopCommentPlatform
            val scheduler=TestCoroutineScheduler()
            val errors=mutableListOf<Throwable>()
            val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _,e -> errors+=e })
            val presentation=h.presentation()
            val first=h.open(presentation)
            h.vm.changeCommentDraft(first,"first",emptyList(),false,5,1)
            val active=mutableStateOf(first)
            val graphics=java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics()
            val configuration=graphics.deviceConfiguration
            graphics.dispose()
            val swingRoot=object:javax.swing.JPanel() {
                override fun getGraphicsConfiguration()=configuration
            }
            val interop=androidx.compose.ui.viewinterop.SwingInteropContainer(swingRoot, true, {})
            val scene=ImageComposeScene(900,800,Density(1f),coroutineContext=scope.coroutineContext) {
                MaterialTheme {
                    CompositionLocalProvider(LocalDesktopCommentBindings provides port, LocalDesktopApplicationImageLoader provides images) {
                        // The production DesktopWindowsVideoCommentsSection also keys by exact stamp.
                        interop {
                            key(active.value) {
                                DesktopOriginalVideoCommentInputOverlay(h.vm,active.value,CommentUiState(),{0L})
                            }
                        }
                    }
                }
            }
            fun settle() { repeat(30) {
                javax.swing.SwingUtilities.invokeAndWait {
                    interop.postponingExecutingScheduledUpdates {
                        Snapshot.sendApplyNotifications();scheduler.advanceTimeBy(16);scheduler.runCurrent()
                        scene.render(scheduler.currentTime*1_000_000L).close();scheduler.runCurrent()
                    }
                }
                assertTrue(errors.isEmpty(),errors.toString())
            } }
            fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
            fun editor()=scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsActions.SetText)!=null }
            try {
                settle()
                fun components(c:java.awt.Component):List<java.awt.Component> =
                    listOf(c)+(if(c is java.awt.Container)c.components.flatMap(::components) else emptyList())
                val native=components(swingRoot).filterIsInstance<DesktopInlineEmotePane>().single()
                val lateNativeChange=native.valueChanged
                val stale=assertNotNull(editor().config[SemanticsActions.SetText].action)
                javax.swing.SwingUtilities.invokeAndWait { assertTrue(stale(AnnotatedString("edited first"))) };settle()
                assertEquals("edited first",h.vm.composerDrafts.value.comments[0L]?.text)
                h.vm.dismissCommentComposer(first)
                val second=h.open(presentation,ReplyItem(rpid=99,root=90))
                h.vm.changeCommentDraft(second,"second",emptyList(),false,6,2)
                active.value=second;settle()
                assertTrue(editor().config[SemanticsProperties.EditableText].text=="second")
                javax.swing.SwingUtilities.invokeAndWait {
                    stale(AnnotatedString("late callback"))
                    // Capture the real callback before disposal; do not rely only on the retired Swing widget.
                    lateNativeChange(TextFieldValue("late native callback",TextRange(2)))
                };settle()
                assertEquals("edited first",h.vm.composerDrafts.value.comments[0L]?.text)
                assertEquals("second",h.vm.composerDrafts.value.comments[99L]?.text)
                val current=assertNotNull(editor().config[SemanticsActions.SetText].action)
                javax.swing.SwingUtilities.invokeAndWait { assertTrue(current(AnnotatedString("current"))) };settle()
                assertEquals("current",h.vm.composerDrafts.value.comments[99L]?.text)
                assertSame(second,h.vm.commentStamp.value)
                assertTrue(h.adds.isEmpty());assertTrue(h.uploads.isEmpty());assertTrue(h.failures.isEmpty())
            } finally { scene.close();scope.cancel();scheduler.runCurrent();javax.swing.SwingUtilities.invokeAndWait { interop.dispose() };presentation.close();images.close();coil3.SingletonImageLoader.setUnsafe(previousLoader);folder.toFile().deleteRecursively() }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class, coil3.annotation.DelicateCoilApi::class)
    @Test fun migratedAdapterContentUsesDraftKeyToReplaceTheLiveEditor(): Unit = runBlocking {
        Harness().use { h ->
            val folder=java.nio.file.Files.createTempDirectory("draft-overlay")
            val context=DesktopPluginContext(DesktopPluginStore(folder))
            val previousLoader=coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
            coil3.SingletonImageLoader.reset()
            val images=DesktopApplicationImageLoader(
                com.bilipai.desktop.data.DesktopRepository(com.bilipai.desktop.data.DesktopSessionStore.temporary()),
                folder.resolve("images"), { h.account })
            val port=java.lang.reflect.Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
                arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
                when(method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "DraftEditorNoNetworkPort"
                    "getContext" -> context
                    "isOwned" -> h.account
                    else -> error("Unexpected editor port: ${method.name}")
                }
            } as DesktopCommentPlatform
            val scheduler=TestCoroutineScheduler()
            val errors=mutableListOf<Throwable>()
            val scope=CoroutineScope(SupervisorJob()+UnconfinedTestDispatcher(scheduler)+CoroutineExceptionHandler { _,e -> errors+=e })
            val presentation=h.presentation()
            val first=h.open(presentation)
            h.vm.changeCommentDraft(first,"first",emptyList(),false,5,1)
            val active=mutableStateOf(first)
            val graphics=java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics()
            val configuration=graphics.deviceConfiguration
            graphics.dispose()
            val swingRoot=object:javax.swing.JPanel() {
                override fun getGraphicsConfiguration()=configuration
            }
            val interop=androidx.compose.ui.viewinterop.SwingInteropContainer(swingRoot, true, {})
            val snapshotConstructor = Class.forName("com.android.purebilibili.feature.video.screen.DesktopOriginalHolderCommentInputSnapshot")
                .declaredConstructors.single().apply { isAccessible = true }
            val actionsConstructor = Class.forName("com.android.purebilibili.feature.video.screen.DesktopOriginalHolderCommentInputActions")
                .declaredConstructors.single().apply { isAccessible = true }
            val contentMethod = Class.forName("com.android.purebilibili.feature.video.screen.VideoDetailInputOverlayAdapterKt")
                .declaredMethods.single { it.name == "VideoDetailCommentInputOverlayContent" }.apply { isAccessible = true }
            val scene=ImageComposeScene(900,800,Density(1f),coroutineContext=scope.coroutineContext) {
                MaterialTheme {
                    CompositionLocalProvider(LocalDesktopCommentBindings provides port, LocalDesktopApplicationImageLoader provides images) {
                        // No caller key: the migrated content must apply snapshot.draftKey itself.
                        interop {
                            val stamp = active.value
                            val drafts by h.vm.composerDrafts.collectAsState()
                            val draft = drafts.comments[if (stamp === first) 0L else 99L] ?: CommentComposerDraft()
                            val snapshot = snapshotConstructor.newInstance(true, stamp.toString(), false, null,
                                "comment", true, emptyList<Any>(), emptyList<Any>(), false, null, draft)
                            val update: (String, List<String>, Boolean, Int, Int) -> Unit = { text, images, sync, begin, end ->
                                h.vm.changeCommentDraft(stamp, text, images, sync, begin, end)
                            }
                            val actions = actionsConstructor.newInstance(({} as () -> Unit),
                                ({ _: String -> } as (String) -> Unit), update,
                                ({ _: String, _: List<String>, _: Boolean -> } as (String, List<String>, Boolean) -> Unit))
                            contentMethod.invoke(null, snapshot, actions, ({ 0L } as () -> Long), currentComposer, 0)
                        }
                    }
                }
            }
            fun settle() { repeat(30) {
                javax.swing.SwingUtilities.invokeAndWait {
                    interop.postponingExecutingScheduledUpdates {
                        Snapshot.sendApplyNotifications();scheduler.advanceTimeBy(16);scheduler.runCurrent()
                        scene.render(scheduler.currentTime*1_000_000L).close();scheduler.runCurrent()
                    }
                }
                assertTrue(errors.isEmpty(),errors.toString())
            } }
            fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
            fun editor()=scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsActions.SetText)!=null }
            try {
                settle()
                fun components(c:java.awt.Component):List<java.awt.Component> =
                    listOf(c)+(if(c is java.awt.Container)c.components.flatMap(::components) else emptyList())
                val native=components(swingRoot).filterIsInstance<DesktopInlineEmotePane>().single()
                val lateNativeChange=native.valueChanged
                val stale=assertNotNull(editor().config[SemanticsActions.SetText].action)
                javax.swing.SwingUtilities.invokeAndWait { assertTrue(stale(AnnotatedString("edited first"))) };settle()
                assertEquals("edited first",h.vm.composerDrafts.value.comments[0L]?.text)
                h.vm.dismissCommentComposer(first)
                val second=h.open(presentation,ReplyItem(rpid=99,root=90))
                h.vm.changeCommentDraft(second,"second",emptyList(),false,6,2)
                active.value=second;settle()
                assertTrue(editor().config[SemanticsProperties.EditableText].text=="second")
                javax.swing.SwingUtilities.invokeAndWait {
                    stale(AnnotatedString("late callback"))
                    // Capture the real callback before disposal; do not rely only on the retired Swing widget.
                    lateNativeChange(TextFieldValue("late native callback",TextRange(2)))
                };settle()
                assertEquals("edited first",h.vm.composerDrafts.value.comments[0L]?.text)
                assertEquals("second",h.vm.composerDrafts.value.comments[99L]?.text)
                val current=assertNotNull(editor().config[SemanticsActions.SetText].action)
                javax.swing.SwingUtilities.invokeAndWait { assertTrue(current(AnnotatedString("current"))) };settle()
                assertEquals("current",h.vm.composerDrafts.value.comments[99L]?.text)
                assertSame(second,h.vm.commentStamp.value)
                assertTrue(h.adds.isEmpty());assertTrue(h.uploads.isEmpty());assertTrue(h.failures.isEmpty())
            } finally { scene.close();scope.cancel();scheduler.runCurrent();javax.swing.SwingUtilities.invokeAndWait { interop.dispose() };presentation.close();images.close();coil3.SingletonImageLoader.setUnsafe(previousLoader);folder.toFile().deleteRecursively() }
        }
    }

}
