package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*

/** A retained epoch's API views and the complete original protocols. The ONLY HTTP client,
 * cookie admission, session backing, WBI cache and visitor bootstrap remain in repository.
 * The required invalidation callback must enqueue Root's real account lifecycle event. */
internal class DesktopHomeRootRequestBinding(
    private val repository:DesktopRepository,
    preferences:DesktopDiscoveryPreferences,
    parentScope:CoroutineScope,
    val capturedEpoch:Long,
    private val capturedMid:Long?,
    private val isCurrent:()->Boolean,
    commitIfCurrent:((()->Unit)->Boolean),
    private val onAuthenticationInvalidated:(epoch:Long,mid:Long)->Unit,
) : AutoCloseable {
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private val requestJob=SupervisorJob(parentScope.coroutineContext[Job])
    private val requestScope=CoroutineScope(parentScope.coroutineContext+requestJob)
    private fun owns()=!closed.get() && requestJob.isActive && repository.sessionEpoch==capturedEpoch && isCurrent()
    private fun assertOwned(){
        if(!owns())throw CancellationException("Home request binding retired")
    }
    private fun <T> service(type:Class<T>,base:String,guest:Boolean=false)=
        repository.ownedHomeService(type,base,capturedEpoch,::owns,guest)
    private val api=service(BilibiliApi::class.java,"https://api.bilibili.com/")
    private val guestApi=service(BilibiliApi::class.java,"https://api.bilibili.com/",guest=true)
    private val messages=service(MessageApi::class.java,"https://api.vc.bilibili.com/")
    private val buvid=service(BuvidApi::class.java,"https://api.bilibili.com/")
    val environment=DesktopHomeProtocolEnvironment(api,guestApi,messages,requestScope,::owns,commitIfCurrent,
        {assertOwned();preferences.feedMode.value},{assertOwned();preferences.refreshCount.value},
        {repository.homeWbiKeys(capturedEpoch,::owns,api)},
        {repository.ownedHomeAccessToken(capturedEpoch,::owns)},
        {repository.ownedHomeCookie("bili_jct",capturedEpoch,::owns)},
        {repository.ownedHomeCookie("buvid3",capturedEpoch,::owns)},
        {repository.assertOwnedHomeSessionRestored(capturedEpoch,::owns)},
        {repository.ensureOwnedHomeSession(capturedEpoch,::owns,buvid)})
    val ports=DesktopHomeRequestPorts(environment)
    fun setNavIdentity(mid:Long?,isVip:Boolean){
        assertOwned()
        if(!repository.updateHomeNavIdentity(capturedEpoch,capturedMid,mid,isVip,onAuthenticationInvalidated))
            throw CancellationException("Home nav response has a foreign account owner")
    }
    override fun close(){if(closed.compareAndSet(false,true)){requestJob.cancel();ports.close()}}
}
