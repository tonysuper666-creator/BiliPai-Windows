package com.bilipai.desktop.diagnostics

import com.android.purebilibili.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transient prompt ownership only; the same retained diagnostic actor owns every file. */
internal data class DesktopCrashPromptState(
    val pending:Boolean=false,val handled:Boolean=false,val loaded:Boolean=false,val busy:Boolean=false,
    val error:String?=null,val closed:Boolean=false,val viewerRequested:Boolean=false,val retryAction:CrashLogPromptAction?=null,
)
internal class DesktopCrashPromptController(val diagnostics:DesktopDiagnostics):AutoCloseable {
    private val gate=Any();private val operations=Mutex();private var generation=0L
    private val mutable=MutableStateFlow(DesktopCrashPromptState())
    val state:StateFlow<DesktopCrashPromptState> = mutable.asStateFlow()
    private fun owns(token:Long)=synchronized(gate){!mutable.value.closed&&generation==token}
    private fun publish(token:Long,transform:(DesktopCrashPromptState)->DesktopCrashPromptState)=synchronized(gate){if(owns(token))mutable.value=transform(mutable.value)}
    suspend fun load():Unit=operations.withLock {
        val token=synchronized(gate){if(mutable.value.closed)return@withLock; (++generation).also{mutable.value=mutable.value.copy(busy=true,error=null,retryAction=null)}}
        try {val pending=diagnostics.hasPendingCrash();publish(token){it.copy(pending=pending,loaded=true,busy=false)}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){publish(token){it.copy(loaded=true,busy=false,error="崩溃日志提示无法读取，请重试。")}}
        finally{publish(token){it.copy(busy=false)}}
    }
    suspend fun handle(action:CrashLogPromptAction):Unit=operations.withLock {
        val token=synchronized(gate){
            if(mutable.value.closed||mutable.value.busy||!mutable.value.pending)return@withLock
            (++generation).also{mutable.value=mutable.value.copy(handled=true,busy=true,error=null,retryAction=null)}
        }
        try {
            if(!shouldClearPendingCrashLogAfterAction(action)) {
                publish(token){it.copy(busy=false)};return@withLock
            }
            var shareReadable=true
            if(action==CrashLogPromptAction.SHARE) {
                try {diagnostics.viewLocal()}
                catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){shareReadable=false}
            }
            // An unfulfilled share/read on an already retired UI must not enqueue a new marker mutation.
            if(!owns(token))return@withLock
            diagnostics.clearCrashPrompt()
            publish(token){it.copy(pending=false,busy=false,viewerRequested=action==CrashLogPromptAction.SHARE&&shareReadable,
                error=if(!shareReadable)"本地崩溃日志无法读取，原快照已保留。" else null)}
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){publish(token){it.copy(busy=false,error="崩溃日志标记无法清理，原快照已保留。",retryAction=action)}}
        finally{publish(token){it.copy(busy=false)}}
    }
    fun dismissViewer()=synchronized(gate){if(!mutable.value.closed)mutable.value=mutable.value.copy(viewerRequested=false)}
    fun dismissError()=synchronized(gate){if(!mutable.value.closed)mutable.value=mutable.value.copy(error=null)}
    override fun close()=synchronized(gate){generation++;mutable.value=mutable.value.copy(closed=true,pending=false,handled=true,busy=false,error=null,viewerRequested=false,retryAction=null)}
    /** Main calls this before its retained diagnostics lifecycle/actor closes and Store freezes. */
    suspend fun shutdownForRestore():Unit=withContext(NonCancellable){close();operations.withLock{}}
}
