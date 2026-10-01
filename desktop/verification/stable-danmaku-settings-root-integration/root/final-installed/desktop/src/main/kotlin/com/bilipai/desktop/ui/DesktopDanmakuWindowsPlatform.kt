package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import com.android.purebilibili.core.ui.common.TextSelectionBottomSheet
import com.android.purebilibili.data.repository.DanmakuCloudSyncSettings
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException

/** No clipboard, HTTP, store or scope is constructed here: all ports belong to the mounted page. */
internal class DesktopDanmakuWindowsPlatform(
    private val owner:DesktopOriginalCommentRootOwner,
    private val pageScope:CoroutineScope,
    private val stillOwned:()->Boolean,
    private val card:DesktopDynamicCardPlatform,
    private val window:Window,
) : DesktopDanmakuPlatform,DesktopDanmakuSettingsPlatform {
    override fun isOwned()=pageScope.isActive&&owner.isOwned()&&stillOwned()&&card.isOwned()
    private fun checkpoint(){if(!isOwned())throw CancellationException("Danmaku page owner retired")}
    override fun showFeedback(message:String){if(isOwned())owner.feedback(message)}
    override fun copyText(text:String,label:String){if(isOwned())card.copyText(text)}
    @Composable override fun textSelection(text:String,title:String?,onDismiss:()->Unit) {
        if(isOwned())TextSelectionBottomSheet(text,title,onDismiss)
    }
    override val cloud:DesktopDanmakuCloudRuleActions=object:DesktopDanmakuCloudRuleActions {
        private val operations:DesktopDynamicCardOperations get()=owner.operations
        override suspend fun getDanmakuCloudFilterRules()=ownedResult{operations.getDanmakuCloudFilterRules()}
        override suspend fun addDanmakuCloudFilterRule(type:Int,filter:String)=ownedResult{operations.addDanmakuCloudFilterRule(type,filter)}
        override suspend fun deleteDanmakuCloudFilterRule(id:Long)=ownedResult{operations.deleteDanmakuCloudFilterRule(id)}
        override suspend fun syncDanmakuCloudConfig(settings:DanmakuCloudSyncSettings)=ownedResult{operations.syncDanmakuCloudConfig(settings)}
    }
    private suspend fun <T> ownedResult(block:suspend()->Result<T>):Result<T> {
        currentCoroutineContext().ensureActive();checkpoint()
        return block().also{currentCoroutineContext().ensureActive();checkpoint()}
    }
    override fun elapsedRealtimeMillis()=System.nanoTime()/1_000_000L
    override fun onCloudSyncFailure(message:String?){if(isOwned())owner.feedback(message?:"弹幕云同步失败")}
    override fun pickRuleFile(mimeTypes:Array<String>,onSelected:(String?)->Unit) {
        if(!isOwned())return
        pageScope.launch {
            val chooser=AtomicReference<FileDialog?>()
            val closer=coroutineContext.job.invokeOnCompletion {
                chooser.get()?.let{dialog->java.awt.EventQueue.invokeLater{dialog.dispose()}}
            }
            try {
                val selected=withContext(Dispatchers.Swing) {
                    checkpoint()
                    check(window.isDisplayable){"Root window is unavailable"}
                    val dialog=when(window){
                        is Frame->FileDialog(window,"导入弹幕屏蔽规则",FileDialog.LOAD)
                        is Dialog->FileDialog(window,"导入弹幕屏蔽规则",FileDialog.LOAD)
                        else->error("Danmaku rule chooser requires the actual Root Frame or Dialog")
                    }
                    chooser.set(dialog)
                    try {
                        dialog.isMultipleMode=false
                        dialog.isVisible=true
                        checkpoint()
                        dialog.file?.let{Path.of(dialog.directory,it).toUri().toString()}
                    } finally {chooser.compareAndSet(dialog,null);dialog.dispose()}
                }
                ensureActive();checkpoint();onSelected(selected)
            } catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){showFeedback(failure.message?:"弹幕规则文件选择失败")}
            finally{closer.dispose()}
        }
    }
    override suspend fun openRuleInput(fileUri:String):InputStream? {
        val caller=currentCoroutineContext()
        caller.ensureActive();checkpoint()
        val uri=URI(fileUri)
        require(uri.scheme.equals("file",ignoreCase=true)&&uri.rawAuthority.isNullOrEmpty()){"Expected a local rule file URI"}
        val path=Path.of(uri)
        return withContext(Dispatchers.IO) {
            caller.ensureActive();checkpoint()
            val stream=Files.newInputStream(path)
            try {caller.ensureActive();checkpoint();DesktopOwnedDanmakuRuleInput(stream,checkNotNull(caller[Job])){caller.ensureActive();checkpoint()}}
            catch(failure:Throwable){stream.close();throw failure}
        }
    }
}

/** An import keeps the invoking modal's Job, so modal dismissal also retires an already opened stream. */
internal class DesktopOwnedDanmakuRuleInput(
    stream:InputStream,
    callerJob:Job,
    private val checkpoint:()->Unit,
) : FilterInputStream(stream) {
    private val closed=AtomicBoolean(false)
    private val retirement=callerJob.invokeOnCompletion{closed.set(true);runCatching{stream.close()}}
    private inline fun <T> guarded(block:()->T):T {
        try {checkpoint();if(closed.get())throw IOException("Danmaku rule input is closed");return block().also{checkpoint()}}
        catch(failure:Throwable){try{close()}catch(closeFailure:Throwable){failure.addSuppressed(closeFailure)};throw failure}
    }
    override fun read()=guarded{`in`.read()}
    override fun read(buffer:ByteArray)=guarded{`in`.read(buffer)}
    override fun read(buffer:ByteArray,offset:Int,length:Int)=guarded{`in`.read(buffer,offset,length)}
    override fun skip(count:Long)=guarded{`in`.skip(count)}
    override fun available()=guarded{`in`.available()}
    override fun reset()=guarded{`in`.reset()}
    override fun close(){try{if(closed.compareAndSet(false,true))super.close()}finally{retirement.dispose()}}
}
