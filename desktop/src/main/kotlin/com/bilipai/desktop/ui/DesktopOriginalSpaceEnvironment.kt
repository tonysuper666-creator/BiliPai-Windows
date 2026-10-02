package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Call
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.Continuation
import java.lang.reflect.InvocationTargetException

/** A retained Space NavEntry child over the same global session, APIs and Store.
 * Public UP reads accept the original guest identity; account mutations still use
 * the existing original ActionRepository protocol and its permission checks.
 * Lock order is existing SessionStore -> Root entry -> this short state monitor.
 * No transport, IO or join occurs inside those monitors.
 */
internal class DesktopOriginalSpaceEnvironment(
    private val repository: DesktopRepository,
    val epoch: Long,
    val accountMid: Long?,
    val upMid: Long,
    parent: CoroutineScope,
    private val retained: () -> Boolean,
    private val visible: () -> Boolean,
    private val commitEntry: ((() -> Unit) -> Boolean),
    private val feedback: (String) -> Unit,
    private val transport: OkHttpClient,
) {
    private val alive = AtomicBoolean(true)
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val delegate = CoroutineScope(parent.coroutineContext + lifetime)
    private val stateLock = Any()
    private val permit = ThreadLocal<(() -> Boolean)?>()
    private val channels = ConcurrentHashMap<String, Long>()
    private val operations = mutableMapOf<String, Job>()
    private val followOwner = repository.dynamicCacheSessionGuard.dynamicCacheOwner()
    // Named type supplies a member launch: the unchanged original call captures
    // inside its real caller Job, rather than inheriting a parent page token.
    val scope = DesktopOriginalSpaceRequestScope(delegate) { block -> launchOwned(null,false,block) }
    fun owns(): Boolean = alive.get() && lifetime.isActive && retained() &&
        repository.sessionEpoch == epoch && repository.account.value?.mid == accountMid
    private fun <T> admit(block: () -> T): T = repository.withProfileAccountAdmission(epoch,accountMid,::owns,commitEntry) {
        synchronized(stateLock) {
            if (!owns() || permit.get()?.invoke()==false) throw CancellationException("Space caller retired")
            block()
        }
    }
    fun checkpoint() = admit { Unit }
    fun requireVisibleAction() = admit { if (!visible()) throw CancellationException("Space entry covered") }
    fun preferenceWritePermit(store:com.bilipai.desktop.plugins.DesktopPluginStore):com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit =
        admit { requireVisibleAction();com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit(store) }
    fun requireMid(mid:Long) { require(mid>0 && mid==upMid) { "Space entry belongs to another UP" }; checkpoint() }
    fun csrf():String? = admit { repository.ownedHomeCookie("bili_jct",epoch,::owns) }
    fun primaryAccessToken():String? = admit { repository.ownedHomeAccessToken(epoch,::owns) }
    fun primaryAccessTokenPlatform():String = admit { repository.accessTokenCredentials().second }
    fun callFactory():Call.Factory = Call.Factory { request ->
        val caller=permit.get() ?: throw java.io.IOException("Space request has no actual caller")
        checkpoint()
        val body=request.body
        val oneShot=if(request.method=="POST" && body!=null) request.newBuilder().method(request.method,
            object:RequestBody() {
                override fun contentType()=body.contentType()
                override fun contentLength()=body.contentLength()
                override fun isOneShot()=true
                override fun writeTo(sink:okio.BufferedSink)=body.writeTo(sink)
            }).build() else request
        repository.ownedHomeCallFactory(epoch,{owns()&&caller()},transport=transport).newCall(oneShot)
    }
    private val web = retrofit2.Retrofit.Builder().baseUrl("https://api.bilibili.com/")
        .callFactory(callFactory()).addConverterFactory(kotlinx.serialization.json.Json {
            ignoreUnknownKeys=true;coerceInputValues=true
        }.asConverterFactory("application/json".toMediaType())).build()
    // Capture the actual suspend caller at Retrofit invocation, including an
    // async child. The request's fixed admission outlives this synchronous
    // invocation and cannot fall back to its still-active parent after cancel.
    private fun <T:Any> service(type:Class<T>):T {
        val raw=web.create(type)
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader,arrayOf(type)) {_,method,args->
            val continuation=args?.lastOrNull() as? Continuation<*>
            if(continuation==null) return@newProxyInstance method.invoke(raw,*(args?:emptyArray()))
            val actual=continuation.context[Job] ?: throw java.io.IOException("Space request has no actual caller")
            val parentPermit=permit.get() ?: throw java.io.IOException("Space request has no entry admission")
            val admitted={actual.isActive&&parentPermit()&&owns()}
            permit.set(admitted)
            try {checkpoint();method.invoke(raw,*(args?:emptyArray()))}
            catch(failure:InvocationTargetException){throw failure.targetException}
            finally {permit.set(parentPermit)}
        })
    }
    val api:BilibiliApi = service(BilibiliApi::class.java)
    val spaceApi:SpaceApi = service(SpaceApi::class.java)
    private val bangumiApi:BangumiApi = service(BangumiApi::class.java)
    private val buvidApi:BuvidApi = service(BuvidApi::class.java)
    val history=DesktopOriginalHomeHistoryProtocol(api)
    private val favoriteEnvironment=DesktopFavoriteEnvironment(delegate,api,spaceApi,null,bangumiApi,
        {owns()&&permit.get()?.invoke()!=false},::csrf,{accountMid},feedback,
        null,null,null,null,::primaryAccessToken,::primaryAccessTokenPlatform,
        { change -> checkpoint();repository.followStateEvents.confirm(checkNotNull(followOwner),change) })
    val favorites get()=favoriteEnvironment.favorite
    val actions=DesktopOriginalSpaceActions(this,DesktopOriginalTabletOwnerSpaceActions(api),favoriteEnvironment.actions)
    val bangumi=DesktopOriginalSpaceBangumiRequests(bangumiApi,this)
    val followStateChanges:Flow<FollowStateChange> = repository.followStateEvents.changes
        .filter { owns()&&it.owner.epoch==epoch&&repository.followStateEvents.isCurrent(it.owner) }.map { it.change }
    suspend fun wbiKeys():Result<Pair<String,String>> {
        currentCoroutineContext().ensureActive();checkpoint()
        val caller=permit.get() ?: throw CancellationException("Space WBI has no caller")
        return repository.homeWbiKeys(epoch,{owns()&&caller()},api).also { currentCoroutineContext().ensureActive();checkpoint() }
    }
    suspend fun ensureSession() {
        val caller=permit.get() ?: throw CancellationException("Space visitor initialization has no caller")
        repository.ensureOwnedHomeSession(epoch,{owns()&&caller()},buvidApi)
        currentCoroutineContext().ensureActive();checkpoint()
    }
    suspend fun <T> withActualCaller(block:suspend()->T):T {
        val caller=currentCoroutineContext().job
        val allowed={caller.isActive&&owns()}
        return withContext(permit.asContextElement(allowed)) {
            checkpoint();block().also {ensureActive();checkpoint()}
        }
    }
    suspend fun getSpaceAggregate(mid:Long):SpaceAggregateResponse {
        requireMid(mid)
        val response=spaceApi.getSpaceAggregate(buildSpaceAggregateParams(mid,primaryAccessToken(),primaryAccessTokenPlatform()))
        currentCoroutineContext().ensureActive();checkpoint()
        return response
    }
    fun <T> mutableStateFlow(initial:T):MutableStateFlow<T> {
        val state=MutableStateFlow(initial)
        return object:MutableStateFlow<T> by state {
            override var value:T get()=state.value;set(next){admit { state.value=next }}
            override fun compareAndSet(expect:T,update:T):Boolean=admit { state.compareAndSet(expect,update) }
            override fun tryEmit(value:T):Boolean=admit { state.tryEmit(value) }
            override suspend fun emit(value:T) { currentCoroutineContext().ensureActive();admit { state.value=value } }
        }
    }
    fun launchMutation(channel:String,block:suspend CoroutineScope.()->Unit):Job=launchOwned(channel,true,block)
    fun launchRead(channel:String,block:suspend CoroutineScope.()->Unit):Job=launchOwned(channel,false,block)
    private fun launchOwned(channel:String?,mutation:Boolean,block:suspend CoroutineScope.()->Unit):Job {
        lateinit var actual:Job
        val previous:Job?
        synchronized(stateLock) {
            if(!owns() || mutation&&!visible()) return Job().also { it.cancel() }
            if(mutation && channel!=null) operations[channel]?.takeIf { it.isActive }?.let { return it }
            val ticket=if(channel==null)0L else (channels[channel]?:0L)+1L
            if(channel!=null)channels[channel]=ticket
            previous=channel?.let {operations[it]}
            val allowed={actual.isActive&&owns()&&(channel==null||channels[channel]==ticket)}
            actual=delegate.launch(permit.asContextElement(allowed),start=CoroutineStart.LAZY) {
                checkpoint();block();currentCoroutineContext().ensureActive();checkpoint()
            }
            if(channel!=null)operations[channel]=actual
        }
        previous?.cancel();actual.start();return actual
    }
    fun close(){if(alive.compareAndSet(true,false))lifetime.cancel()}
    suspend fun closeAndJoin():Boolean {
        require(currentCoroutineContext()[Job]!==lifetime);close()
        return withContext(NonCancellable) { withTimeoutOrNull(5000) { lifetime.join();true }?:false }
    }
}

internal class DesktopOriginalSpaceRequestScope(private val delegate:CoroutineScope,
    private val invoke:(suspend CoroutineScope.()->Unit)->Job):CoroutineScope {
    override val coroutineContext:CoroutineContext get()=delegate.coroutineContext
    fun launch(block:suspend CoroutineScope.()->Unit):Job=invoke(block)
}
/** Required action facets reuse the two installed original protocol producers. */
internal class DesktopOriginalSpaceActions(private val environment:DesktopOriginalSpaceEnvironment,
    private val relation:DesktopOriginalTabletOwnerSpaceActions,
    private val groups:DesktopOriginalFavoriteActions) {
    private suspend fun <T> owned(block:suspend()->T):T {
        currentCoroutineContext().ensureActive();environment.checkpoint()
        return block().also {currentCoroutineContext().ensureActive();environment.checkpoint()}
    }
    private suspend fun <T> result(block:suspend()->Result<T>):Result<T> = owned(block).also {
        (it.exceptionOrNull() as? CancellationException)?.let {cancelled->throw cancelled}
    }
    suspend fun getRelationDetail(mid:Long)=owned {relation.getRelationDetail(mid)}
    suspend fun checkFollowStatus(mid:Long)=owned {relation.checkFollowStatus(mid)}
    suspend fun followUser(mid:Long,follow:Boolean)=result {groups.followUser(mid,follow)}
    suspend fun getFollowGroupTags()=result {groups.getFollowGroupTags()}
    suspend fun getUserFollowGroupIds(mid:Long)=result {groups.getUserFollowGroupIds(mid)}
    suspend fun overwriteFollowGroupIds(targetMids:Set<Long>,selectedTagIds:Set<Long>)=result {groups.overwriteFollowGroupIds(targetMids,selectedTagIds)}
}
