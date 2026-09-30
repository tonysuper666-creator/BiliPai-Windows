package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.android.purebilibili.core.store.TodayWatchFeedbackSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Transient UI classification, never a replacement preference or feedback schema. */
internal class DesktopDiscoveryStorageFailure(
    val failedConstructor:Boolean,
    val accountFeedback:Boolean,
    cause:Throwable,
) : IllegalStateException(
    (if(accountFeedback)"本地推荐反馈无法读取" else "发现页设置无法读取")+
        "，原文件保持不变；"+if(failedConstructor)"请修复文件后重试；仍失败时请重新启动应用。" else "请修复文件后重新启动应用。",
    cause,
)

/** Only a failed constructor permits another factory attempt. Existing decoded backing is not reloaded. */
internal fun openDesktopDiscoveryStorage(
    repository:DesktopRepository,
    preferencesFactory:()->DesktopDiscoveryPreferences,
):DesktopDiscoveryRepository {
    val preferences=try {preferencesFactory()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){throw DesktopDiscoveryStorageFailure(true,false,failure)}
    try {preferences.feedMode.value;preferences.refreshCount.value}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){throw DesktopDiscoveryStorageFailure(false,false,failure)}
    return DesktopDiscoveryRepository(repository,preferences)
}

/** Constructor and already-held decode failures have different truthful recovery actions. */
internal fun openDesktopDiscoveryFeedback(
    discovery:DesktopDiscoveryRepository,
    accountMid:Long?,
):StateFlow<TodayWatchFeedbackSnapshot> {
    val source=try {discovery.feedback(accountMid)}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){throw DesktopDiscoveryStorageFailure(true,true,failure)}
    try {source.value}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){throw DesktopDiscoveryStorageFailure(false,true,failure)}
    return source
}

/** Retains one successful object; it never reopens an initialized/frozen preference generation. */
internal class DesktopDiscoveryStorageGuard<T:Any>(
    private val factory:()->T,
    private val sessionEpoch:()->Long = {0L},
    private val stillOwned:()->Boolean = {true},
) : AutoCloseable {
    private val lock=Any()
    private val mutableResult=MutableStateFlow<Result<T>?>(null)
    val result:StateFlow<Result<T>?> = mutableResult.asStateFlow()
    private var generation=0L
    private var pending:Pair<Long,Long>?=null
    private var closed=false
    private var retained:T?=null
    val isActive:Boolean get() = synchronized(lock) { !closed&&stillOwned() }
    val canRetry:Boolean get() = synchronized(lock) {
        !closed&&stillOwned()&&pending==null&&
            (mutableResult.value?.exceptionOrNull() as? DesktopDiscoveryStorageFailure)?.failedConstructor==true
    }
    suspend fun load():Boolean {
        val epoch=sessionEpoch()
        val request=synchronized(lock) {
            if(closed||!stillOwned())return false
            retained?.let{mutableResult.value=Result.success(it);return true}
            val previous=mutableResult.value?.exceptionOrNull()
            if(previous!=null&&(previous as? DesktopDiscoveryStorageFailure)?.failedConstructor!=true)return false
            if(pending?.second==epoch)return false
            (++generation).also{pending=it to epoch;mutableResult.value=null}
        }
        val loaded=try {Result.success(withContext(Dispatchers.IO){
            currentCoroutineContext().ensureActive()
            // Late coroutine scheduling must not even invoke a retired factory.
            if(!owns(request,epoch))throw CancellationException("旧发现页存储读取已退役")
            factory()
        })} catch(cancelled:CancellationException) {
            synchronized(lock){if(pending?.first==request)pending=null}
            throw cancelled
        } catch(failure:Exception){Result.failure<T>(failure)}
        return synchronized(lock) {
            if(!owns(request,epoch)){if(pending?.first==request)pending=null;return false}
            pending=null
            loaded.getOrNull()?.let{retained=it}
            mutableResult.value=loaded
            loaded.isSuccess
        }
    }
    private fun owns(request:Long,epoch:Long):Boolean = synchronized(lock) {
        !closed&&stillOwned()&&generation==request&&sessionEpoch()==epoch
    }
    override fun close()=synchronized(lock){closed=true;generation++;pending=null}
}
