package com.bilipai.desktop.data

import androidx.compose.runtime.staticCompositionLocalOf
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/* TON-1082 (TON-1071 B1). Upstream repositories send every request through the process-wide NetworkModule, which the
 * desktop never initializes. The ApiClient patch routes its Retrofit services through [DesktopUpstreamCalls] and turns
 * its own OkHttp clients into gates that refuse to send. Each request is sent for one [DesktopUpstreamOwner]: a
 * repository and its Store, the window lifetime, the account epoch and the purpose, all captured in one Store
 * admission when the operation starts. Coroutines carry the owner in [DesktopUpstreamScope]; a call without one is a
 * wiring error and fails loudly instead of falling back to "whoever is signed in when it executes". */

/** On whose behalf an upstream request is sent. */
internal sealed interface DesktopUpstreamPurpose {
    /** The Store's active account (or its signed-out visitor session). */
    data object Main : DesktopUpstreamPurpose
    /** An explicit guest: a fresh visitor buvid3 only, never an account Cookie, forced cookie or access key. */
    data object Guest : DesktopUpstreamPurpose
    /** The selected playback account, bound to one authorization receipt and revision. */
    class Playback(val authorization: DesktopPlaybackAuthorization) : DesktopUpstreamPurpose
}

/** The account an owner was captured for. Holds a digest of the session credential, never the credential. */
internal data class DesktopUpstreamIdentity(private val store: Any, val epoch: Long, val mid: Long?, val credentialDigest: String?) {
    /** A cache partition name for this identity: equal identities share it, any other Store, epoch or account does not. */
    fun scope(guest: Boolean): String = requireNotNull(digest("${System.identityHashCode(store)}:$epoch:$mid:$credentialDigest:$guest"))

    companion object {
        fun digest(credential: String?): String? = credential?.takeIf { it.isNotBlank() }?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

/** TON-1084 (TON-1071 B2). What upstream TokenManager reads for one owner: the Store's active account (Android's
 * TokenManager is always the main account; a playback account is resolved separately), captured in the same admission
 * as the owner and never updated. A guest owner has its visitor buvid3 only. Not a data class: no credential in toString. */
internal class DesktopUpstreamTokens private constructor(
    val sessData: String?, val csrf: String?, val mid: Long?, val buvid3: String?,
    val accessToken: String?, val refreshToken: String?, val accessTokenPlatform: String, val isVip: Boolean,
) {
    val signedIn: Boolean get() = sessData != null

    override fun toString(): String = "DesktopUpstreamTokens(mid=$mid, signedIn=$signedIn)"

    companion object {
        private const val TV = com.android.purebilibili.core.store.TokenManager.ACCESS_TOKEN_PLATFORM_TV

        fun account(session: com.android.purebilibili.core.store.StoredAccountSession?, visitorBuvid3: String?): DesktopUpstreamTokens =
            if (session == null || session.sessData.isBlank()) signedOut(visitorBuvid3)
            else DesktopUpstreamTokens(session.sessData, session.csrf.ifBlank { null }, session.mid.takeIf { it > 0L },
                session.buvid3.ifBlank { null } ?: visitorBuvid3, session.accessToken.ifBlank { null },
                session.refreshToken.ifBlank { null }, session.accessTokenPlatform.ifBlank { TV }, session.isVip)

        /** Signed out, or an explicit guest: no account value at all, only the visitor identity. */
        fun signedOut(visitorBuvid3: String?): DesktopUpstreamTokens =
            DesktopUpstreamTokens(null, null, null, visitorBuvid3?.ifBlank { null }, null, null, TV, false)
    }
}

/** Thrown when an owner is used after an account switch, a window close or a playback authorization change. */
internal class DesktopUpstreamOwnerRetired(entry: String) : CancellationException("Upstream request owner retired: $entry")

/** Created only by [DesktopRepository.upstreamOwner]. */
internal class DesktopUpstreamOwner internal constructor(
    private val repository: DesktopRepository,
    val identity: DesktopUpstreamIdentity,
    private val stillOwned: () -> Boolean,
    val purpose: DesktopUpstreamPurpose,
    private val capturedTokens: DesktopUpstreamTokens,
) {
    val epoch: Long get() = identity.epoch
    internal fun owned(): Boolean = stillOwned()

    fun isCurrent(): Boolean = stillOwned() && repository.isUpstreamOwnerCurrent(this)

    fun admit(entry: String = "admission") { if (!isCurrent()) throw DesktopUpstreamOwnerRetired(entry) }

    /** The session values upstream TokenManager reads for this owner (TON-1084). A retired owner has none. */
    fun tokens(entry: String): DesktopUpstreamTokens { admit(entry); return capturedTokens }

    /** Runs [block] inside the owner's Store admission, so no account switch can happen while it publishes. */
    fun <T> admitted(block: () -> T): T = repository.withUpstreamOwnerAdmission(this, block)

    /** The same repository, lifetime and epoch for another purpose. The derived owner keeps every retirement condition
     * of this one (a playback owner's receipt and revision included), so it retires together with it. An explicit
     * guest never becomes an account owner, and a playback owner can only be captured with its authorization. */
    fun withPurpose(next: DesktopUpstreamPurpose): DesktopUpstreamOwner = when {
        next === purpose -> this
        next is DesktopUpstreamPurpose.Playback -> throw UnsupportedDesktopCapability(
            "An upstream playback request from a ${purpose::class.simpleName} owner", "capture a Playback owner with its authorization (TON-1082)")
        purpose === DesktopUpstreamPurpose.Guest && next === DesktopUpstreamPurpose.Main -> throw UnsupportedDesktopCapability(
            "An upstream account request from an explicit guest owner", "a guest owner never carries account credentials (TON-1082)")
        // A guest derived from an account owner reads only the visitor identity, never the account snapshot.
        else -> DesktopUpstreamOwner(repository, identity, ::isCurrent, next,
            if (next === DesktopUpstreamPurpose.Guest) DesktopUpstreamTokens.signedOut(repository.upstreamGuestBuvid3) else capturedTokens)
    }

    /** Requests through the repository's own client, dispatcher, pool and CookieJar, stamped with this owner. */
    fun callFactory(configure: (OkHttpClient.Builder.() -> Unit)? = null): Call.Factory = repository.upstreamCallFactory(this, configure)

    fun webSocketFactory(): WebSocket.Factory = repository.upstreamWebSocketFactory(this)
}

/** Carries the owner across coroutine dispatches. The previous value is restored on every switch and on exit, so a
 * nested owner applies inside its block only. A new independent scope or an OkHttp callback thread has no owner. */
internal class DesktopUpstreamScope(val owner: DesktopUpstreamOwner) :
    ThreadContextElement<DesktopUpstreamOwner?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopUpstreamScope>
    override fun updateThreadContext(context: CoroutineContext): DesktopUpstreamOwner? = DesktopUpstreamCalls.swap(owner)
    override fun restoreThreadContext(context: CoroutineContext, oldState: DesktopUpstreamOwner?) { DesktopUpstreamCalls.swap(oldState) }
}

/** What the upstream patches use instead of NetworkModule's clients. */
internal object DesktopUpstreamCalls {
    private val current = ThreadLocal<DesktopUpstreamOwner?>()

    internal fun swap(owner: DesktopUpstreamOwner?): DesktopUpstreamOwner? = current.get().also { current.set(owner) }

    fun currentOwner(): DesktopUpstreamOwner? = current.get()

    fun requireOwner(entry: String): DesktopUpstreamOwner = current.get() ?: throw UnsupportedDesktopCapability(
        "$entry without a desktop request owner", "run the upstream call inside DesktopUpstreamScope (TON-1082)")

    fun requirePlayback(entry: String): DesktopUpstreamOwner = requireOwner(entry).also {
        if (it.purpose !is DesktopUpstreamPurpose.Playback) throw UnsupportedDesktopCapability(
            "$entry from a ${it.purpose::class.simpleName} owner", "the upstream playback account path needs a Playback owner; it never falls back to the main account (TON-1082)")
    }

    /** Blocking code (and tests) that already hold an owner. */
    fun <T> withOwner(owner: DesktopUpstreamOwner, block: () -> T): T {
        val previous = swap(owner)
        try { return block() } finally { swap(previous) }
    }

    suspend fun <T> withOwnerScope(owner: DesktopUpstreamOwner, block: suspend () -> T): T = withContext(DesktopUpstreamScope(owner)) { block() }

    private fun entry(request: okhttp3.Request) = "NetworkModule request ${request.method} ${request.url.host}${request.url.encodedPath}"

    // A playback owner also reaches the account services of the same epoch (upstream mixes api and playbackApi);
    // an explicit guest owner stays a guest.
    private fun accountOwner(entry: String) = requireOwner(entry).let {
        if (it.purpose is DesktopUpstreamPurpose.Playback) it.withPurpose(DesktopUpstreamPurpose.Main) else it
    }

    /** NetworkModule.api and the other account Retrofit services. */
    val main: Call.Factory = Call.Factory { request -> accountOwner(entry(request)).callFactory().newCall(request) }

    /** Direct account requests that need their own client settings (redirects, timeouts). */
    fun main(configure: OkHttpClient.Builder.() -> Unit): Call.Factory =
        Call.Factory { request -> accountOwner(entry(request)).callFactory(configure).newCall(request) }

    /** NetworkModule.guestApi, guestDynamicApi and guest direct requests. */
    val guest: Call.Factory = Call.Factory { request ->
        requireOwner(entry(request)).withPurpose(DesktopUpstreamPurpose.Guest).callFactory().newCall(request)
    }

    /** NetworkModule.playbackApi() and playbackBangumiApi(). */
    val playback: Call.Factory = Call.Factory { request -> requirePlayback(entry(request)).callFactory().newCall(request) }

    /** LiveDanmakuClient's default socket factory. */
    val webSocket: WebSocket.Factory = WebSocket.Factory { request, listener ->
        accountOwner("NetworkModule WebSocket ${request.url.host}${request.url.encodedPath}").webSocketFactory().newWebSocket(request, listener)
    }

    /** Reads or writes of account-scoped upstream cache and state: the owner check and [block] run in one Store
     * admission, so no account switch, window close or playback change can land between them. */
    fun <T> admitted(entry: String, block: () -> T): T = requireOwner(entry).admitted(block)

    /** The cache partition of the current owner's account (a guest owner has its own). */
    fun cacheScope(entry: String): String = requireOwner(entry).let { it.identity.scope(it.purpose === DesktopUpstreamPurpose.Guest) }

    /** The current owner's account as (mid, authenticated); (0, false) for an explicit guest. */
    fun account(entry: String): Pair<Long, Boolean> = requireOwner(entry).let {
        if (it.purpose === DesktopUpstreamPurpose.Guest) 0L to false else (it.identity.mid ?: 0L) to (it.identity.credentialDigest != null)
    }

    /** The account an upstream account-scoped cache belongs to; a guest owner has its own identity. */
    fun sessionIdentity(entry: String): Any = requireOwner(entry).let { it.identity to (it.purpose === DesktopUpstreamPurpose.Guest) }

    /** TokenManager's session caches (TON-1084): the current owner's snapshot. No owner is a wiring error and fails
     * loudly; a retired owner is refused; only an explicit guest or a signed-out owner reads null. */
    fun tokens(entry: String): DesktopUpstreamTokens = requireOwner(entry).tokens(entry)

    /** TokenManager.accountIdentity: one fixed identity per owner, refused once the owner is retired. */
    fun accountIdentity(entry: String): kotlinx.coroutines.flow.StateFlow<com.android.purebilibili.core.store.AccountSessionIdentity> =
        requireOwner(entry).let { owner -> DesktopUpstreamOwnedState(owner, entry,
            com.android.purebilibili.core.store.AccountSessionIdentity(owner.tokens(entry).mid, owner.epoch)) }

    /** Upstream session writes: login, logout, account switching and their storage belong to the desktop's own flow. */
    fun refuseSessionWrite(entry: String): Nothing = throw UnsupportedDesktopCapability(
        "$entry (an upstream session write)", "sign-in, sign-out and account switching use the desktop account flow; " +
            "upstream code reads its owner's session snapshot and never keeps a process-wide copy (TON-1084)")

    /** NetworkModule's own clients on the desktop: same protocols and cache contract, but they never send. Every
     * upstream request is routed through an owner before it reaches a client; one that still arrives here was not
     * routed, and is refused before any desktop authorization could pick "the current account" for it. */
    fun gateTransport(name: String, cache: Cache?): OkHttpClient = OkHttpClient.Builder()
        .protocols(com.android.purebilibili.core.network.NetworkModule.resolveSharedNetworkProtocols())
        .apply { if (cache != null) cache(cache) }
        .addInterceptor { chain ->
            val request = chain.request()
            throw UnsupportedDesktopCapability("NetworkModule.$name ${request.method} ${request.url.host}${request.url.encodedPath} " +
                "(owner tag ${if (request.tag(DesktopUpstreamOwner::class.java) == null) "missing" else "on the raw transport"})",
                "send it through DesktopUpstreamCalls with a desktop request owner (TON-1082)")
        }
        .build()

    /** Explicit third-party requests (SponsorBlock, QQ Music, NetEase): no account, no CookieJar, the desktop
     * credential boundary on every hop, and Bilibili hosts refused because they would need an owner. */
    val publicTransport: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(com.android.purebilibili.core.network.NetworkModule.resolveSharedNetworkProtocols())
            .proxySelector(com.bilipai.desktop.network.DesktopNetworkProxyPlatform.buildAppProxySelector())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .addInterceptor { chain ->
                val request = chain.request()
                if (DesktopSessionStore.isBilibiliHost(request.url.host) || usesDefaultBilibiliHeaders(request.url.host))
                    throw UnsupportedDesktopCapability("A third-party upstream request to ${request.url.host}",
                        "Bilibili requests need a desktop request owner (TON-1082)")
                chain.proceed(request)
            }
            // A redirect does not rerun application interceptors: every hop is checked again here.
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (DesktopSessionStore.isBilibiliHost(request.url.host) || usesDefaultBilibiliHeaders(request.url.host))
                    throw UnsupportedDesktopCapability("A third-party upstream request redirected to ${request.url.host}",
                        "Bilibili requests need a desktop request owner (TON-1082)")
                chain.proceed(com.bilipai.desktop.network.enforceDesktopCredentialBoundary(request))
            }
            .build()
    }
}

/** A StateFlow fixed to one owner: every read checks the owner first, so a retired owner yields no value. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class DesktopUpstreamOwnedState<T>(private val owner: DesktopUpstreamOwner, private val entry: String, private val state: T) :
    kotlinx.coroutines.flow.StateFlow<T> {
    override val value: T get() { owner.admit(entry); return state }
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>): Nothing {
        collector.emit(value)
        kotlinx.coroutines.awaitCancellation()
    }
}

/** The HTTP caches behind NetworkModule. The 32 MiB guest cache is NetworkModule.okHttpClient.cache and serves the
 * explicit guest requests of every repository; account requests use one partition per identity, deleted as soon as
 * that identity is retired. Partition names are random, so the directory never names an account. */
internal object DesktopUpstreamHttpCache {
    val budgetBytes: Long get() = com.android.purebilibili.core.network.NetworkModule.resolveApiHttpCacheBudgetBytes()

    private val root: Path by lazy {
        System.getProperty("bilipai.upstreamHttpCache")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: DesktopSessionStore.defaultDataDirectory().resolve("upstream-http")
    }

    val guest: Cache by lazy { Cache(root.resolve("guest").toFile(), budgetBytes) }

    private val clearedAccountRoots = mutableSetOf<Path>()

    /** Partitions left by an earlier process can never be matched to an identity again. */
    @Synchronized fun accountRoot(storeRoot: Path?): Path {
        val accounts = (storeRoot ?: root).resolve("account")
        if (clearedAccountRoots.add(accounts) && Files.isDirectory(accounts)) deleteTree(accounts)
        return accounts
    }

    internal fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
    }
}

/** One repository's transports for upstream requests: derived from its client, so dispatcher, pool and CookieJar stay
 * the same; only the HTTP cache differs. */
internal class DesktopUpstreamTransports(private val storeRoot: () -> Path?) {
    private class Partition(val identity: DesktopUpstreamIdentity, val base: OkHttpClient, val cache: Cache, val transport: OkHttpClient)
    private var guest: Pair<OkHttpClient, OkHttpClient>? = null
    private var account: Partition? = null

    // Every hop of a guest request, redirects included, drops account credentials from its URL.
    @Synchronized fun guest(base: OkHttpClient): OkHttpClient = guest?.takeIf { it.first === base }?.second
        ?: base.newBuilder().cache(DesktopUpstreamHttpCache.guest)
            .addNetworkInterceptor { chain -> chain.proceed(com.bilipai.desktop.network.stripDesktopGuestCredentials(chain.request(), networkHop = true)) }
            .build().also { guest = base to it }

    /** Callers hold the owner's Store admission, so a retired owner can never replace the current partition. */
    @Synchronized fun account(base: OkHttpClient, identity: DesktopUpstreamIdentity): OkHttpClient {
        account?.let { if (it.identity == identity && it.base === base) return it.transport }
        retireAccountLocked()
        val directory = DesktopUpstreamHttpCache.accountRoot(storeRoot()).resolve(UUID.randomUUID().toString())
        val cache = Cache(directory.toFile(), DesktopUpstreamHttpCache.budgetBytes)
        return base.newBuilder().cache(cache).build().also { account = Partition(identity, base, cache, it) }
    }

    /** Account switch, sign-in, sign-out or removal: the old identity's responses are deleted, not just hidden. */
    @Synchronized fun retireAccount() = retireAccountLocked()

    @Synchronized internal fun accountCacheDirectory(): java.io.File? = account?.cache?.directory

    private fun retireAccountLocked() {
        val retired = account ?: return
        account = null
        runCatching { retired.cache.delete() }
        DesktopUpstreamHttpCache.deleteTree(retired.cache.directory.toPath())
    }
}

/** A window's way to capture owners for upstream code it runs: the repository and the window lifetime. The epoch is
 * read when [owner] is called, i.e. when the operation starts. */
internal class DesktopUpstreamSession(private val repository: DesktopRepository, private val stillOwned: () -> Boolean) {
    fun owner(purpose: DesktopUpstreamPurpose = DesktopUpstreamPurpose.Main): DesktopUpstreamOwner =
        repository.upstreamOwner(purpose, stillOwned)

    /** Runs [block] for an owner captured now. An operation that starts after its window closed or its account was
     * switched does not run at all; one that is retired while running fails with the retired request. */
    suspend fun withOwner(purpose: DesktopUpstreamPurpose = DesktopUpstreamPurpose.Main, block: suspend () -> Unit) {
        val owner = try { owner(purpose) } catch (retired: BiliApiException) { if (retired.apiCode == -101) return else throw retired }
        DesktopUpstreamCalls.withOwnerScope(owner, block)
    }

    /** The explicit read-only session of this window for non-coroutine upstream reads (UI, blocking core code): the
     * active account's TokenManager values as of now, read in one admission. Nothing is cached in between. */
    fun tokens(entry: String): DesktopUpstreamTokens = owner().tokens(entry)

    companion object {
        fun require(session: DesktopUpstreamSession?, entry: String): DesktopUpstreamSession = session
            ?: throw UnsupportedDesktopCapability("$entry outside a desktop window", "mount LocalDesktopUpstreamSession at the window root (TON-1082)")
    }
}

internal val LocalDesktopUpstreamSession = staticCompositionLocalOf<DesktopUpstreamSession?> { null }
