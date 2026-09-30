package com.bilipai.desktop.diagnostics

import com.bilipai.desktop.update.UpdateStorage
import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Window
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class DesktopNativeShareRetirement(val state:Int,val dataSupplied:Boolean) {
    val safeToDelete:Boolean get()=state==3 || state==4 || !dataSupplied
}
/** A narrow platform transport port. Only isolated fixtures supply a synthetic event source. */
internal interface DesktopNativeShareTransport {
    fun prepare(path:Path):Long
    fun show(token:Long):Boolean
    fun state(token:Long):Int
    fun retire(token:Long):DesktopNativeShareRetirement
}

/** Same actor owns lease files; this owner serializes native grant, clear-all and retirement. */
internal class DesktopNativeCrashShare(
    nativeDll:()->Path,expectedSha256:String,window:()->Window?,
    internal val diagnostics:DesktopDiagnostics,
    transportFactory:(()->DesktopNativeShareTransport)?=null,
) {
    init {require(expectedSha256.matches(Regex("[a-f0-9]{64}")))}
    private interface Api:StdCallLibrary {
        fun BilipaiSharePrepare(path:WString,result:LongByReference):Int
        fun BilipaiShareShow(token:Long,hwnd:Pointer):Int
        fun BilipaiShareState(token:Long,result:IntByReference):Int
        fun BilipaiShareRetire(token:Long,state:IntByReference,supplied:IntByReference):Int
    }
    private val transport by lazy {
        transportFactory?.invoke() ?: run {
            check(System.getProperty("os.name").startsWith("Windows") && Native.POINTER_SIZE==8)
            val path=UpdateStorage.existingPathWithoutLinks(nativeDll().toAbsolutePath())
            require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path) in 1..16L*1024*1024)
            val hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString(""){"%02x".format(it)}
            check(hash==expectedSha256){"原生分享资源校验失败"}
            val api=Native.load(path.toString(),Api::class.java)
            object:DesktopNativeShareTransport {
                override fun prepare(path:Path):Long {
                    val result=LongByReference()
                    check(api.BilipaiSharePrepare(WString(path.toAbsolutePath().toString()),result)>=0 && result.value!=0L)
                    return result.value
                }
                override fun show(token:Long):Boolean {
                    val owner=window() ?: return false
                    return owner.isDisplayable && owner.isVisible && api.BilipaiShareShow(token,Native.getWindowPointer(owner))>=0
                }
                override fun state(token:Long):Int {
                    val result=IntByReference();check(api.BilipaiShareState(token,result)>=0);return result.value
                }
                override fun retire(token:Long):DesktopNativeShareRetirement {
                    val state=IntByReference(-1);val supplied=IntByReference(1)
                    check(api.BilipaiShareRetire(token,state,supplied)>=0){"原生分享资源尚未释放"}
                    return DesktopNativeShareRetirement(state.value,supplied.value!=0)
                }
            }
        }
    }
    private data class Active(val token:Long,val lease:DesktopCrashShareLease,val fail:()->Unit,val generation:Long)
    private val operations=Mutex();private val clearOperations=Mutex()
    private val closing=AtomicBoolean();private val accepting=AtomicBoolean(true);private val generation=AtomicLong()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var watcher:Job?=null;private var active:Active?=null
    private val mutableError=MutableStateFlow<String?>(null)
    val error:StateFlow<String?> = mutableError.asStateFlow()
    init {diagnostics.installNativeShareClearHook(::clearAllOwned)}
    private fun owns(value:Long)=!closing.get() && accepting.get() && generation.get()==value
    private suspend fun releaseLocked(record:Active):DesktopNativeShareRetirement=withContext(NonCancellable) {
        val outcome=withContext(Dispatchers.Swing){transport.retire(record.token)}
        active=null // Native token is gone even if the following filesystem deletion fails.
        diagnostics.retireNativeCrashShareLease(record.lease,outcome.safeToDelete)
        outcome
    }
    private suspend fun settleLocked():Boolean {
        val record=active ?: return true
        val state=transport.state(record.token)
        return if(state==3 || state==4 || state==5) {
            val outcome=releaseLocked(record)
            if(state==5 && owns(record.generation))record.fail()
            outcome.safeToDelete
        } else false
    }
    /** Only an explicit original SHARE action calls this; no expiry timer infers completion. */
    suspend fun shareSnapshot(onLateFailure:()->Unit={}):Boolean {
        val acceptedEpoch=generation.get()
        if(!owns(acceptedEpoch))return false
        return operations.withLock {
            if(!owns(acceptedEpoch))return@withLock false
            val epoch=generation.incrementAndGet()
            watcher?.cancelAndJoin();watcher=null
            var lease:DesktopCrashShareLease?=null;var token=0L;var retained=false
            try {
                if(!settleLocked())active?.let {releaseLocked(it)}
                // This new explicit SHARE may replace the window source subscription,
                // while old possibly exposed copies remain immutable on private disk.
                lease=diagnostics.prepareNativeCrashShareLease() ?: return@withLock false
                if(!owns(epoch))return@withLock false
                token=withContext(Dispatchers.IO){transport.prepare(lease.path)}
                check(token!=0L && transport.state(token)==0)
                if(!owns(epoch))return@withLock false
                diagnostics.markNativeCrashShareMayExpose(lease)
                val shown=withContext(Dispatchers.Swing){owns(epoch) && transport.show(token)}
                if(!shown)return@withLock false
                val record=Active(token,lease,onLateFailure,epoch);active=record;retained=true;mutableError.value=null
                watcher=scope.launch {
                    try {
                        while(isActive && owns(epoch)) {
                            delay(250) // State polling only, never a lease expiration/completion timer.
                            val done=operations.withLock {
                                if(!owns(epoch)||active!==record)true
                                else if(settleLocked())true else active!==record
                            }
                            if(done)return@launch
                        }
                    }catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){if(owns(epoch)){mutableError.value="系统分享或副本清理尚未完成。";onLateFailure()}}
                    catch(_:LinkageError){if(owns(epoch)){mutableError.value="系统分享暂不可用。";onLateFailure()}}
                }
                true
            }catch(cancelled:CancellationException){throw cancelled}
            catch(capacity:DesktopCrashShareCapacityException){mutableError.value=capacity.safeMessage;false}
            catch(_:Exception){mutableError.value="系统分享暂不可用；未确认的副本会保留，显式清理日志可撤销。";false}
            catch(_:LinkageError){mutableError.value="系统分享暂不可用。";false}
            finally {
                if(!retained && lease!=null)withContext(NonCancellable) {
                    val safe=if(token==0L)true else try {
                        withContext(Dispatchers.Swing){transport.retire(token).safeToDelete}
                    }catch(failure:Exception) {
                        active=Active(token,lease,onLateFailure,epoch) // retain retry ownership on COM revoke failure
                        throw failure
                    }
                    diagnostics.retireNativeCrashShareLease(lease,safe)
                }
            }
        }
    }
    fun retire(){if(closing.compareAndSet(false,true)){accepting.set(false);generation.incrementAndGet()}}
    private suspend fun stopWatcher(){watcher?.cancelAndJoin();watcher=null}
    /** Called by the SAME actor's public clearAll, outside its writer queue. */
    private suspend fun clearAllOwned(action:suspend()->Unit):Unit=withContext(NonCancellable) {
        clearOperations.withLock {
            check(!closing.get()){ "诊断分享已经停止" }
            accepting.set(false);generation.incrementAndGet();stopWatcher()
            try {operations.withLock {
                active?.let {releaseLocked(it)} // revoke/drain before user-authorized file deletion
                action()
                mutableError.value=null
            }}finally{if(!closing.get())accepting.set(true)}
        }
    }
    suspend fun shutdownForRestore():Unit=withContext(NonCancellable) {
        retire();stopWatcher()
        operations.withLock {active?.let {releaseLocked(it)}}
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
