package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.NavData
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*

/** A per-nav source borrows this exact original Home binding/gate. It owns no Job, Store or page.
 * The request child may finish normally before an enqueued invalidation is consumed. */
internal class DesktopHomeNavRequestSource internal constructor(
    val receipt: DesktopHomeNavRequestReceipt,
    private val binding: DesktopHomeRootRequestBinding,
) {
    internal fun belongsTo(owner: DesktopHomeRootRequestBinding) = binding === owner
    fun commitIfCurrent(block: () -> Unit): Boolean = binding.commitNavSource(block)
}

/** Only a successful original NavData(isLogin=false) from a logged-in primary request creates this.
 * This is a Home AUTH observation, not the failure origin of an arbitrary current Video leaf. */
internal class DesktopHomeAuthenticationInvalidation internal constructor(val source: DesktopHomeNavRequestSource)

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
    private val commitIfCurrent:((()->Unit)->Boolean),
    private val onAuthenticationInvalidated:(DesktopHomeAuthenticationInvalidation)->Unit,
) : AutoCloseable {
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private val requestJob=SupervisorJob(parentScope.coroutineContext[Job])
    private val requestScope=CoroutineScope(parentScope.coroutineContext+requestJob)
    private fun owns()=!closed.get() && requestJob.isActive && repository.sessionEpoch==capturedEpoch && isCurrent()
    private fun assertOwned(){
        if(!owns())throw CancellationException("Home request binding retired")
    }
    // Captured once by the actual retained Root constructor, before its first publication.
    // This is the existing Store receipt, not another account/source cache.
    private val mountedReceipt = repository.captureHomeNavRequest(capturedEpoch, capturedMid, ::owns)
    /** Dedicated Store -> original entry admission. General owns() remains lock-free. */
    internal fun withMountedPublication(block: () -> Unit): Boolean {
        var applied = false
        val admitted = repository.withCurrentHomeNavRequest(mountedReceipt, ::owns) {
            commitIfCurrent { if (owns()) { block(); applied = true } }
        }
        return admitted && applied
    }
    internal fun isMountedSourceCurrent() = withMountedPublication {}
    internal fun assertMountedSourceCurrent() {
        if (!isMountedSourceCurrent()) throw CancellationException("Mounted Home source retired")
    }
    private fun <T> service(type:Class<T>,base:String,guest:Boolean=false)=
        repository.ownedHomeService(type,base,capturedEpoch,::isMountedSourceCurrent,guest)
    private val api=service(BilibiliApi::class.java,"https://api.bilibili.com/")
    private val guestApi=service(BilibiliApi::class.java,"https://api.bilibili.com/",guest=true)
    private val messages=service(MessageApi::class.java,"https://api.vc.bilibili.com/")
    private val buvid=service(BuvidApi::class.java,"https://api.bilibili.com/")
    // Dedicated request/publication qualification only; never change generic Gate owns.
    val environment=DesktopHomeProtocolEnvironment(api,guestApi,messages,requestScope,
        ::isMountedSourceCurrent,::withMountedPublication,
        {assertMountedSourceCurrent();preferences.feedMode.value},{assertMountedSourceCurrent();preferences.refreshCount.value},
        {repository.homeWbiKeys(capturedEpoch,::isMountedSourceCurrent,api)},
        {repository.ownedHomeAccessToken(capturedEpoch,::isMountedSourceCurrent)},
        {repository.ownedHomeCookie("bili_jct",capturedEpoch,::isMountedSourceCurrent)},
        {repository.ownedHomeCookie("buvid3",capturedEpoch,::isMountedSourceCurrent)},
        {repository.assertOwnedHomeSessionRestored(capturedEpoch,::isMountedSourceCurrent)},
        {repository.ensureOwnedHomeSession(capturedEpoch,::isMountedSourceCurrent,buvid)})
    private fun beginNavRequest(callerJob: Job): DesktopHomeNavRequestSource {
        var receipt: DesktopHomeNavRequestReceipt? = null
        // Capture the ORIGINAL per-request receipt in the same mounted Store->entry gate.
        // A preceding unlocked assert would let an old Root acquire a new installation stamp.
        val admitted = withMountedPublication {
            receipt = repository.captureHomeNavRequest(capturedEpoch, capturedMid) { callerJob.isActive && owns() }
        }
        if (!admitted) throw CancellationException("Home nav mounted source retired")
        return DesktopHomeNavRequestSource(requireNotNull(receipt), this)
    }
    internal fun commitNavSource(block: () -> Unit): Boolean {
        var applied = false
        val admitted = commitIfCurrent { if (owns()) { block(); applied = true } }
        return admitted && applied
    }
    private fun observeNavResult(source: DesktopHomeNavRequestSource, isLogin: Boolean, callerJob: Job) {
        if (!source.belongsTo(this)) throw CancellationException("Home nav request has a foreign source")
        var applied = false
        val admitted = repository.withCurrentHomeNavRequest(source.receipt, ::owns) {
            applied = source.commitIfCurrent {
                // The actual executing child must still be live when it publishes; queue lifetime uses the retained owner.
                if (!callerJob.isActive) throw CancellationException("Home nav request caller retired")
                if (!isLogin && (source.receipt.mid ?: 0L) > 0L)
                    onAuthenticationInvalidated(DesktopHomeAuthenticationInvalidation(source))
            }
        }
        if (!admitted || !applied) throw CancellationException("Home nav result source retired")
    }
    /** Store -> original retained-entry monitor, exactly as the AUTH observer. No
     * fresh receipt/stamp is captured and no suspend, cancel or analytics work is admitted. */
    private fun commitNavPublication(source:DesktopHomeNavRequestSource, callerJob:Job, block:()->Unit) {
        if (!source.belongsTo(this)) throw CancellationException("Home nav publication has a foreign source")
        var applied=false
        val admitted=repository.withCurrentHomeNavRequest(source.receipt, { callerJob.isActive && owns() }) {
            applied=source.commitIfCurrent {
                if (!callerJob.isActive) throw CancellationException("Home nav publication caller retired")
                block()
            }
        }
        if (!admitted || !applied) throw CancellationException("Home nav publication source retired")
    }
    private fun backgroundPublication(source:DesktopHomeNavRequestSource, callerJob:Job) =
        DesktopHomeBackgroundPublication(
            publishCurrent={ block -> commitNavPublication(source,callerJob,block) },
            checkCurrent={ commitNavPublication(source,callerJob) {} })
    private fun navPublication(source:DesktopHomeNavRequestSource, nav:NavData, callerJob:Job) =
        DesktopHomeNavPublication(nav,
            publishCurrent={ block -> commitNavPublication(source, callerJob) {
                // Keep the original VIP setter, including its existing synchronous persistence.
                setNavIdentity(if (nav.isLogin) nav.mid else null, nav.isLogin && nav.vip.status == 1)
                block()
            } },
            checkCurrent={ commitNavPublication(source, callerJob) {} },
            backgroundForCaller={ childJob -> backgroundPublication(source,childJob) })
    val ports=DesktopHomeRequestPorts(environment, ::beginNavRequest, ::observeNavResult, ::navPublication, ::backgroundPublication)
    private fun setNavIdentity(mid:Long?,isVip:Boolean){
        assertOwned()
        if(!repository.updateHomeNavIdentity(capturedEpoch,capturedMid,mid,isVip))
            throw CancellationException("Home nav response has a foreign account owner")
    }
    override fun close(){if(closed.compareAndSet(false,true)){requestJob.cancel();ports.close()}}
}
