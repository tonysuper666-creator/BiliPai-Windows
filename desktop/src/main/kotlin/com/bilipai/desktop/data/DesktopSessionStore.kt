package com.bilipai.desktop.data

import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import com.android.purebilibili.core.store.StoredAccountSession

/** Immutable receipt of the PRIMARY account and the last successful explicit UI credential install.
 * The opaque in-memory stamp contains no credentials and is owned only by the existing Store. */
internal class DesktopHomeNavRequestReceipt internal constructor(
    val epoch: Long,
    val mid: Long?,
    internal val uiLoginInstallationStamp: Any,
)

/** Server cookies retain their scope; explicitly authorized account credentials cover Bilibili hosts. */
internal class DesktopSessionStore(private val path: Path = defaultPath(), private val persistent: Boolean = true) : CookieJar, DesktopDynamicCacheSessionGuard {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var saved = if (persistent) readSaved() else SavedSession()
    // Replaced only after saveLoginAccount actually persists. Identical credentials may keep generation unchanged.
    private var primaryUiLoginInstallationStamp = Any()
    private val mutableGeneration = MutableStateFlow(0L)
    val generationState = mutableGeneration.asStateFlow()
    @Volatile var generation: Long = 0
        private set(value) { field = value; mutableGeneration.value = value }
    internal val requestGeneration = ThreadLocal<Long>()
    // The same synchronous OkHttp interceptor scope as requestGeneration, cleared in finally.
    internal val requestAdmission = ThreadLocal<() -> Boolean>()
    internal val requestGuestBuvid3 = ThreadLocal<String?>()
    // Original playback_mid preference is kept in THIS encrypted file, not another account store.
    private val mutablePlaybackAuthorizationRevision = MutableStateFlow(0L)
    internal val playbackAuthorizationRevision = mutablePlaybackAuthorizationRevision.asStateFlow()
    private var playbackIdentity = playbackIdentityLocked()
    private var playbackCookieJar: DesktopOriginalPlaybackAccountCookieJar? = null
    internal val requestPlaybackAuthorization = ThreadLocal<DesktopPlaybackAuthorization>()

    private data class PlaybackIdentity(val selection: Long?, val effective: StoredAccountSession?)
    private fun playbackAccountLocked(): StoredAccountSession? = saved.playbackAccountMid?.let { mid ->
        accountRecords(saved).firstOrNull { it.session.mid == mid && it.session.sessData.isNotBlank() }?.session
    }
    private fun playbackIdentityLocked(): PlaybackIdentity {
        val selected = playbackAccountLocked()
        val effective = selected ?: saved.account?.let { recordCurrent(saved).session }
        // Display metadata/last-used timestamps do not change authorization.
        return PlaybackIdentity(saved.playbackAccountMid, effective?.copy(name = "", face = "", vipLabel = "", lastUsedAt = 0))
    }
    private fun refreshPlaybackAuthorizationLocked(force: Boolean = false) {
        val next = playbackIdentityLocked()
        if (force || next != playbackIdentity) {
            playbackIdentity = next
            playbackCookieJar = null
            mutablePlaybackAuthorizationRevision.value++
        }
    }
    internal fun storedAccountSessions(): List<StoredAccountSession> = synchronized(lock) { accountRecords(saved).map { it.session } }
    internal fun activeAccountMid(): Long? = synchronized(lock) { saved.account?.mid }
    internal fun getPlaybackAccountMid(): Long? = synchronized(lock) { saved.playbackAccountMid }
    internal fun getPlaybackAccount(): StoredAccountSession? = synchronized(lock) { playbackAccountLocked() }
    internal fun setPlaybackAccountMid(mid: Long?, expectedGeneration: Long, stillOwned: () -> Boolean): Boolean = synchronized(lock) {
        if (generation != expectedGeneration || !stillOwned()) throw kotlinx.coroutines.CancellationException("Playback preference owner retired")
        if (mid != null && accountRecords(saved).none { it.session.mid == mid && it.session.sessData.isNotBlank() }) return@synchronized false
        val next = saved.copy(playbackAccountMid = mid)
        persist(next); saved = next
        refreshPlaybackAuthorizationLocked(force = true)
        true
    }
    internal fun capturePlaybackAuthorization(expectedGeneration: Long, stillOwned: () -> Boolean): DesktopPlaybackAuthorization = synchronized(lock) {
        if (generation != expectedGeneration || !stillOwned()) throw kotlinx.coroutines.CancellationException("Playback owner retired")
        refreshPlaybackAuthorizationLocked()
        val selected = playbackAccountLocked()
        // Original NetworkModule uses the main transport when selected MID is the active MID.
        val dedicated = selected?.takeIf { it.mid != saved.account?.mid }
        if (dedicated != null && playbackCookieJar == null) playbackCookieJar = DesktopOriginalPlaybackAccountCookieJar(dedicated)
        DesktopPlaybackAuthorization(DesktopPlaybackAuthorizationReceipt(generation, mutablePlaybackAuthorizationRevision.value), selected,
            playbackCookieJar.takeIf { dedicated != null })
    }
    internal fun isPlaybackAuthorizationCurrent(receipt: DesktopPlaybackAuthorizationReceipt): Boolean = synchronized(lock) {
        refreshPlaybackAuthorizationLocked()
        generation == receipt.accountEpoch && mutablePlaybackAuthorizationRevision.value == receipt.revision
    }
    internal fun <T> withPlaybackAuthorizationAdmission(receipt: DesktopPlaybackAuthorizationReceipt,
        stillOwned: () -> Boolean, block: () -> T): T = synchronized(lock) {
        if (!isPlaybackAuthorizationCurrent(receipt) || !stillOwned()) throw kotlinx.coroutines.CancellationException("Playback authorization retired")
        block()
    }
    internal fun capturePlaybackCachePartition(receipt: DesktopPlaybackAuthorizationReceipt,
        stillOwned: () -> Boolean): String = withPlaybackAuthorizationAdmission(receipt, stillOwned) {
        val account = playbackIdentity.effective
        val identity = buildString {
            append("BiliPai Windows native media byte cache v1\n")
            listOf(account?.mid?.toString().orEmpty(), account?.sessData.orEmpty(), account?.csrf.orEmpty(),
                account?.accessToken.orEmpty(), account?.refreshToken.orEmpty(), account?.accessTokenPlatform.orEmpty(),
                account?.buvid3.orEmpty(), account?.isVip?.toString().orEmpty()).forEach { value ->
                append(value.length); append(':'); append(value)
            }
        }
        java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    internal fun playbackRequestCookies(authorization: DesktopPlaybackAuthorization, url: HttpUrl,
        stillOwned: () -> Boolean): Map<String, String> = withPlaybackAuthorizationAdmission(authorization.receipt, stillOwned) {
        authorization.cookieJar?.loadForRequest(url)?.associate { it.name to it.value } ?: currentCookies()
    }

    private val serverCookies = saved.serverCookies.mapNotNull { it.toCookie() }
        .filter { it.expiresAt > System.currentTimeMillis() }
        .associateBy { it.identity() }.toMutableMap()
    private val mutableAccount = MutableStateFlow(saved.account)
    val account: StateFlow<AccountSummary?> = mutableAccount.asStateFlow()
    private val mutableAccounts = MutableStateFlow(accountRecords(saved).map { it.toInfo() })
    val accounts: StateFlow<List<DesktopStoredAccountInfo>> = mutableAccounts.asStateFlow()

    /** Profile TokenManager writes share the canonical primary identity and encrypted file.
     * Called only inside Repository's captured-epoch Store -> entry admission. */
    internal fun saveProfileMid(mid: Long) = synchronized(lock) {
        val account = saved.account ?: throw CancellationException("Profile primary session absent")
        if (mid <= 0L || account.mid != mid) throw CancellationException("Profile nav MID does not match captured primary")
        val next = saved.copy(account = account.copy(mid = mid)).let { it.copy(accounts = accountRecords(it)) }
        persist(next); saved = next
        mutableAccount.value = next.account
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
    }

    internal fun saveProfileVipStatus(vip: Boolean) = synchronized(lock) {
        val account = saved.account ?: throw CancellationException("Profile primary session absent")
        val next = saved.copy(account = account.copy(isVip = vip)).let { it.copy(accounts = accountRecords(it)) }
        persist(next); saved = next; refreshPlaybackAuthorizationLocked()
        mutableAccount.value = next.account
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
    }

    /** Original AccountSessionStore.upsertCurrentAccount values, using this Store's credentials.
     * No cookie/token/selected-playback identity is obtained from Profile's NavData. */
    internal fun upsertProfileCurrentAccount(nav: com.android.purebilibili.data.model.response.NavData?) = synchronized(lock) {
        val account = saved.account ?: return@synchronized
        val mid = nav?.mid?.takeIf { it > 0L } ?: account.mid
        val sessData = saved.cookies["SESSDATA"]?.takeIf { it.isNotBlank() } ?: return@synchronized
        if (mid != account.mid) throw CancellationException("Profile upsert MID does not match captured primary")
        val existing = accountRecords(saved).associateBy { it.session.mid }
        val previous = existing[mid]
        val timestamp = System.currentTimeMillis()
        val updated = StoredAccountSession(
            mid = mid,
            name = nav?.uname?.ifBlank { previous?.session?.name.orEmpty() } ?: previous?.session?.name.orEmpty(),
            face = nav?.face?.ifBlank { previous?.session?.face.orEmpty() } ?: previous?.session?.face.orEmpty(),
            sessData = sessData,
            csrf = saved.cookies["bili_jct"].orEmpty(),
            accessToken = saved.accessToken.orEmpty(),
            refreshToken = saved.refreshToken,
            accessTokenPlatform = saved.accessTokenPlatform,
            buvid3 = saved.cookies["buvid3"].orEmpty().ifBlank { saved.spiCookies["buvid3"].orEmpty() },
            isVip = nav?.vip?.status == 1 || account.isVip,
            vipLabel = nav?.vip?.label?.text.orEmpty().ifBlank { previous?.session?.vipLabel.orEmpty() },
            lastUsedAt = timestamp
        )
        val primary = account.copy(name = updated.name, avatar = updated.face, isVip = updated.isVip)
        val nextPrimary = saved.copy(account = primary, lastUsedAt = timestamp)
        val record = recordCurrent(nextPrimary).copy(session = updated)
        val merged = existing.values.filterNot { it.session.mid == mid }.plus(record).sortedByDescending { it.session.lastUsedAt }
        val next = nextPrimary.copy(accounts = merged)
        persist(next); saved = next; refreshPlaybackAuthorizationLocked()
        mutableAccount.value = primary
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
    }

    internal fun accessTokenCredentials(): Pair<String?, String> = synchronized(lock) { saved.accessToken to saved.accessTokenPlatform }
    internal fun appCredentials(): DesktopAppCredentials? = synchronized(lock) {
        saved.accessToken?.takeIf { it.isNotBlank() }?.let { DesktopAppCredentials(it, saved.refreshToken, saved.accessTokenPlatform, saved.tokenExpiresAt) }
    }
    internal data class CookieSnapshot(val server: List<Cookie>, val spi: Map<String, String>)
    internal fun cookieSnapshot(): CookieSnapshot = synchronized(lock) { CookieSnapshot(serverCookies.values.toList(), saved.spiCookies.toMap()) }
    internal fun accountCookieHeader(mid: Long): String? = synchronized(lock) {
        accountRecords(saved).firstOrNull { it.session.mid == mid }?.cookies?.entries?.joinToString("; ") { "${it.key}=${it.value}" }
    }
    internal fun <T> withHomeRequestAdmission(expectedGeneration: Long, stillOwned: () -> Boolean, block: () -> T): T = synchronized(lock) {
        if (generation != expectedGeneration || !stillOwned()) throw BiliApiException(-101, "账号已切换，请重新加载")
        block()
    }
    internal fun homeRequestCookies(expectedGeneration: Long, stillOwned: () -> Boolean): Map<String, String> =
        withHomeRequestAdmission(expectedGeneration, stillOwned) { currentCookies() }

    internal fun captureHomeNavRequest(expectedGeneration: Long, expectedMid: Long?,
        stillOwned: () -> Boolean): DesktopHomeNavRequestReceipt = synchronized(lock) {
        if (generation != expectedGeneration || saved.account?.mid != expectedMid || !stillOwned())
            throw CancellationException("Home nav request source retired")
        DesktopHomeNavRequestReceipt(generation, saved.account?.mid, primaryUiLoginInstallationStamp)
    }

    /** Receipt check under the existing Store monitor; callers add the originating entry gate in Store -> entry order. */
    internal fun withCurrentHomeNavRequest(receipt: DesktopHomeNavRequestReceipt,
        stillOwned: () -> Boolean, block: () -> Unit): Boolean = synchronized(lock) {
        if (generation != receipt.epoch || saved.account?.mid != receipt.mid ||
            primaryUiLoginInstallationStamp !== receipt.uiLoginInstallationStamp || !stillOwned())
            return@synchronized false
        block()
        true
    }

    /** Original TokenManager MID/VIP cache intent is projected into this existing account.
     * Never adopt another MID or credentials, and never increment epoch for a profile flag. */
    internal fun updateHomeNavIdentity(expectedGeneration: Long, expectedMid: Long?, navMid: Long?, isVip: Boolean): Boolean = synchronized(lock) {
        if (generation != expectedGeneration || saved.account?.mid != expectedMid) return@synchronized false
        val current = saved.account
        if (current == null) return@synchronized navMid == null
        // The original guest cache projection does not carry a request receipt. AUTH observation
        // is emitted by the actual getNavInfo caller; never manufacture a late epoch/MID-only event here.
        if (navMid == null) return@synchronized true
        if (navMid != current.mid) return@synchronized false
        if (current.isVip == isVip) return@synchronized true
        val updated = current.copy(isVip = isVip)
        var next = saved.copy(account = updated)
        next = next.copy(accounts = accountRecords(next))
        persist(next); saved = next; refreshPlaybackAuthorizationLocked()
        mutableAccount.value = updated
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
        true
    }

    internal fun loginIdentityBuvid(): String = synchronized(lock) { saved.loginBuvid }
    internal fun saveLoginIdentityBuvid(buvid: String) = synchronized(lock) { val next = saved.copy(loginBuvid = buvid); persist(next); saved = next; refreshPlaybackAuthorizationLocked() }

    override fun dynamicCacheOwner(): DesktopDynamicCacheOwner? = synchronized(lock) { dynamicCacheOwnerLocked() }
    override fun withCurrentDynamicCacheOwner(owner: DesktopDynamicCacheOwner, block: () -> Unit): Boolean = synchronized(lock) {
        if (dynamicCacheOwnerLocked() != owner) return@synchronized false
        block()
        true
    }
    private fun dynamicCacheOwnerLocked(): DesktopDynamicCacheOwner? {
        // NotInterested is a local action in the original app, including guest detail pages.
        // This owner separates local cache generations; it does not authorize network requests.
        val mid = saved.account?.mid?.takeIf { it > 0L } ?: 0L
        val credential = if (mid > 0L) saved.cookies["SESSDATA"].orEmpty() else ""
        // A one-way, domain-separated tag survives cold starts without persisting another credential.
        // The process epoch separately rejects jobs from a retired login, including the same MID.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(
            ("BiliPai Windows dynamic cache v1\n$mid\n$credential").toByteArray(Charsets.UTF_8))
        return DesktopDynamicCacheOwner(mid, generation, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.scheme != "https" || !isBilibiliHost(url.host)) return
        synchronized(lock) {
            if (requestGeneration.get()?.let { it != generation } == true || requestAdmission.get()?.invoke() == false || requestGuestBuvid3.get() != null) return
            requestPlaybackAuthorization.get()?.let { authorization ->
                if (!isPlaybackAuthorizationCurrent(authorization.receipt)) return
                authorization.cookieJar?.let { it.saveFromResponse(url, cookies); return }
            }
            val now = System.currentTimeMillis()
            var accountCookies = saved.cookies
            cookies.filter { belongsToResponseHost(it, url.host) }.forEach { cookie ->
                if (cookie.expiresAt <= now) {
                    serverCookies.remove(cookie.identity())
                    if (cookie.name in ACCOUNT_COOKIE_NAMES && cookie.path == "/") accountCookies = accountCookies - cookie.name
                } else {
                    // Set-Cookie may intentionally target a future path, so do not call matches(url) here.
                    serverCookies[cookie.identity()] = cookie
                }
            }
            removeExpired(now)
            val next = saved.copy(cookies = accountCookies, serverCookies = serverCookies.values.map { ServerCookie.fromCookie(it) })
            val credentialChanged = saved.cookies["SESSDATA"] != next.cookies["SESSDATA"]
            persist(next)
            saved = next; refreshPlaybackAuthorizationLocked()
            if (credentialChanged) generation++
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.scheme != "https" || !isBilibiliHost(url.host)) return emptyList()
        return synchronized(lock) {
            if (requestGeneration.get()?.let { it != generation } == true || requestAdmission.get()?.invoke() == false)
                throw BiliApiException(-101, "账号已切换，请重新加载")
            requestPlaybackAuthorization.get()?.let { authorization ->
                if (!isPlaybackAuthorizationCurrent(authorization.receipt)) throw java.io.IOException("Playback authorization retired")
                authorization.cookieJar?.let { return@synchronized it.loadForRequest(url) }
            }
            requestGuestBuvid3.get()?.let { visitor ->
                return@synchronized listOf(Cookie.Builder().domain(url.host).name("buvid3").value(visitor).build())
            }
            removeExpired(System.currentTimeMillis())
            val cookies = serverCookies.values.filter { it.matches(url) }.toMutableList()
            // SPI's b_3/b_4 are explicitly returned for the Bilibili visitor session. They fill missing
            // identifiers only; an actual server cookie matching this URL retains its own scope/value.
            (saved.spiCookies + saved.cookies.filterKeys { it in VISITOR_COOKIE_NAMES }).forEach { (name, value) ->
                if (cookies.none { it.name == name }) globalCookie(name, value)?.let(cookies::add)
            }
            saved.cookies.filterKeys { it in ACCOUNT_COOKIE_NAMES }.forEach { (name, value) ->
                cookies.removeAll { it.name == name }
                globalCookie(name, value)?.let(cookies::add)
            }
            cookies
        }
    }

    /** Cookie header material for explicit account validation, never the automatic request jar. */
    fun currentCookies(): Map<String, String> = synchronized(lock) {
        removeExpired(System.currentTimeMillis())
        saved.spiCookies.toMutableMap().apply {
            serverCookies.values.forEach { put(it.name, it.value) }
            putAll(saved.cookies)
        }
    }

    fun saveAccount(cookies: Map<String, String>, account: AccountSummary, imported: Boolean = false,
        credentials: DesktopAppCredentials? = null, preserveAccessToken: Boolean = true, snapshot: CookieSnapshot? = null) = synchronized(lock) {
        val previous = accountRecords(saved)
        val changed = saved.account?.mid != account.mid || saved.cookies["SESSDATA"] != cookies["SESSDATA"]
        val sameAccount = saved.account?.mid == account.mid
        if (snapshot != null) {
            serverCookies.clear(); snapshot.server.forEach { serverCookies[it.identity()] = it }
        } else if (saved.account != null && !sameAccount) serverCookies.clear()
        val authorizedNames = if (imported) PERSISTED_COOKIE_NAMES else ACCOUNT_COOKIE_NAMES
        var newSaved = saved.copy(
            cookies = (if (sameAccount || saved.account == null) saved.cookies.filterKeys { it in VISITOR_COOKIE_NAMES } else emptyMap()) + cookies.filterKeys { it in authorizedNames },
            account = account,
            serverCookies = serverCookies.values.map { ServerCookie.fromCookie(it) },
            spiCookies = snapshot?.spi ?: saved.spiCookies.takeIf { sameAccount || saved.account == null }.orEmpty(),
            accessToken = credentials?.accessToken ?: saved.accessToken.takeIf { sameAccount && preserveAccessToken },
            refreshToken = credentials?.refreshToken ?: saved.refreshToken.takeIf { sameAccount && preserveAccessToken }.orEmpty(),
            accessTokenPlatform = credentials?.platform ?: saved.accessTokenPlatform.takeIf { sameAccount && preserveAccessToken } ?: "tv",
            tokenExpiresAt = credentials?.expiresAt ?: saved.tokenExpiresAt.takeIf { sameAccount && preserveAccessToken } ?: 0,
            lastUsedAt = System.currentTimeMillis(),
        )
        newSaved = newSaved.copy(accounts = previous.filterNot { it.session.mid == account.mid } + recordCurrent(newSaved))
        persist(newSaved)
        saved = newSaved; refreshPlaybackAuthorizationLocked()
        if (changed) generation++
        mutableAccount.value = account
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
    }

    /** Same original save/persist and monitor; a UI login observer cannot infer success from epoch alone. */
    internal fun saveLoginAccount(cookies: Map<String, String>, account: AccountSummary,
        credentials: DesktopAppCredentials?, snapshot: CookieSnapshot?, expectedEpoch: Long,
        stillOwned: () -> Boolean): DesktopLoginInstallationReceipt = synchronized(lock) {
        if (generation != expectedEpoch || !stillOwned())
            throw CancellationException("Login installation source retired")
        val sourceMid = saved.account?.mid
        saveAccount(cookies, account, imported = true, credentials = credentials,
            preserveAccessToken = false, snapshot = snapshot)
        // Same monitor, only after the original save/persist succeeded; failed installs leave the old stamp.
        primaryUiLoginInstallationStamp = Any()
        DesktopLoginInstallationReceipt(expectedEpoch, sourceMid, generation, account.mid)
    }

    fun saveSpiCookies(cookies: Map<String, String>, expectedGeneration: Long? = null) = synchronized(lock) {
        if (expectedGeneration != null && expectedGeneration != generation) throw BiliApiException(-101, "账号已切换，请重新加载")
        val visitors = cookies.filterKeys { it in SPI_COOKIE_NAMES }.filterValues { it.isNotBlank() }
        val next = saved.copy(spiCookies = saved.spiCookies + visitors,
            serverCookies = serverCookies.values.map { ServerCookie.fromCookie(it) })
        persist(next)
        saved = next; refreshPlaybackAuthorizationLocked()
    }

    fun logout() = synchronized(lock) {
        val cleared = SavedSession(accounts = accountRecords(saved), loginBuvid = saved.loginBuvid, playbackAccountMid = saved.playbackAccountMid)
        persist(cleared)
        saved = cleared; refreshPlaybackAuthorizationLocked()
        serverCookies.clear()
        mutableAccount.value = null
        generation++
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
    }

    fun activateAccount(mid: Long, validatedAccount: AccountSummary? = null): Boolean = synchronized(lock) {
        val target = accountRecords(saved).firstOrNull { it.session.mid == mid && it.session.sessData.isNotBlank() } ?: return@synchronized false
        val source = target.session
        val account = validatedAccount?.takeIf { it.mid == mid } ?: AccountSummary(source.mid, source.name, source.face, source.isVip)
        val next = saved.copy(cookies = target.cookies, account = account, spiCookies = target.spiCookies,
            serverCookies = target.serverCookies, accessToken = source.accessToken.takeIf { it.isNotBlank() },
            refreshToken = source.refreshToken, accessTokenPlatform = source.accessTokenPlatform,
            tokenExpiresAt = target.tokenExpiresAt, lastUsedAt = System.currentTimeMillis(), accounts = accountRecords(saved))
        persist(next); saved = next; refreshPlaybackAuthorizationLocked(); serverCookies.clear()
        next.serverCookies.mapNotNull { it.toCookie() }.filter { it.expiresAt > System.currentTimeMillis() }.forEach { serverCookies[it.identity()] = it }
        generation++; mutableAccount.value = account; mutableAccounts.value = accountRecords(saved).map { it.toInfo() }; true
    }

    fun removeAccount(mid: Long): Boolean = synchronized(lock) {
        val existing = accountRecords(saved)
        if (existing.none { it.session.mid == mid }) return@synchronized false
        val remaining = existing.filterNot { it.session.mid == mid }
        val playbackMid = saved.playbackAccountMid?.takeUnless { it == mid }
        val next = if (saved.account?.mid == mid) SavedSession(accounts = remaining, loginBuvid = saved.loginBuvid, playbackAccountMid = playbackMid) else saved.copy(accounts = remaining, playbackAccountMid = playbackMid)
        persist(next); saved = next; refreshPlaybackAuthorizationLocked()
        if (mutableAccount.value?.mid == mid) { serverCookies.clear(); generation++; mutableAccount.value = null }
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }; true
    }

    /** UI account removal result from the same original Store and original remover, not a second account actor. */
    internal fun removeLoginAccount(mid: Long): Pair<Boolean, Boolean> = synchronized(lock) {
        val wasPrimary = saved.account?.mid == mid
        val removed = removeAccount(mid)
        removed to (removed && wasPrimary)
    }

    private fun accountRecords(session: SavedSession): List<SavedAccount> {
        val current = session.account?.takeIf { session.cookies["SESSDATA"].orEmpty().isNotBlank() }?.let { recordCurrent(session) }
        return (session.accounts.filterNot { it.session.mid == current?.session?.mid } + listOfNotNull(current)).sortedByDescending { it.session.lastUsedAt }
    }

    private fun recordCurrent(session: SavedSession): SavedAccount {
        val account = requireNotNull(session.account)
        return SavedAccount(StoredAccountSession(account.mid, account.name, account.avatar, session.cookies["SESSDATA"].orEmpty(),
            session.cookies["bili_jct"].orEmpty(), session.accessToken.orEmpty(), session.refreshToken, session.accessTokenPlatform,
            session.cookies["buvid3"].orEmpty().ifBlank { session.spiCookies["buvid3"].orEmpty() }, account.isVip,
            vipLabel = session.accounts.firstOrNull { it.session.mid == account.mid }?.session?.vipLabel.orEmpty(),
            lastUsedAt = session.lastUsedAt),
            session.cookies, session.spiCookies, session.serverCookies, session.tokenExpiresAt)
    }

    private fun SavedAccount.toInfo() = DesktopStoredAccountInfo(AccountSummary(session.mid, session.name, session.face, session.isVip),
        session.lastUsedAt, session.accessToken.isNotBlank(), session.accessTokenPlatform)

    private fun removeExpired(now: Long) {
        serverCookies.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun globalCookie(name: String, value: String): Cookie? = runCatching {
        Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").secure().build()
    }.getOrNull()

    private fun readSaved(): SavedSession = runCatching {
        if (!Files.isRegularFile(path)) return@runCatching SavedSession()
        val restored = transformSecrets(json.decodeFromString<SavedSession>(Files.readString(path)), DesktopCredentialCipher::unprotect)
        if (restored.schemaVersion < 2) {
            // Old flattened visitor entries lack provenance and cannot safely be widened on restore.
            restored.copy(schemaVersion = 2, cookies = restored.cookies.filterKeys { it in ACCOUNT_COOKIE_NAMES },
                spiCookies = emptyMap(), serverCookies = emptyList())
        } else restored.copy(cookies = restored.cookies.filterKeys { it in PERSISTED_COOKIE_NAMES },
            spiCookies = restored.spiCookies.filterKeys { it in SPI_COOKIE_NAMES })
    }.getOrElse { SavedSession() }

    private fun persist(session: SavedSession) {
        if (!persistent) return
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "session-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(SavedSession.serializer(), transformSecrets(session.copy(schemaVersion = 3), DesktopCredentialCipher::protect)))
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    private data class CookieIdentity(val name: String, val domain: String, val path: String)
    private fun Cookie.identity() = CookieIdentity(name, domain, path)

    @Serializable
    private data class SavedSession(
        val cookies: Map<String, String> = emptyMap(),
        val account: AccountSummary? = null,
        val spiCookies: Map<String, String> = emptyMap(),
        val serverCookies: List<ServerCookie> = emptyList(),
        val schemaVersion: Int = 0,
        val accessToken: String? = null,
        val accessTokenPlatform: String = "tv",
        val refreshToken: String = "",
        val tokenExpiresAt: Long = 0,
        val lastUsedAt: Long = 0,
        val loginBuvid: String = "",
        val accounts: List<SavedAccount> = emptyList(),
        @kotlinx.serialization.SerialName("playback_mid") val playbackAccountMid: Long? = null,
    )

    @Serializable private data class SavedAccount(val session: StoredAccountSession, val cookies: Map<String, String>,
        val spiCookies: Map<String, String> = emptyMap(), val serverCookies: List<ServerCookie> = emptyList(), val tokenExpiresAt: Long = 0)

    private fun transformSecrets(value: SavedSession, transform: (String) -> String): SavedSession {
        fun cookies(source: Map<String, String>) = source.mapValues { (name, secret) -> if (name in ACCOUNT_COOKIE_NAMES) transform(secret) else secret }
        fun servers(source: List<ServerCookie>) = source.map { if (it.name in ACCOUNT_COOKIE_NAMES) it.copy(value = transform(it.value)) else it }
        return value.copy(cookies = cookies(value.cookies), serverCookies = servers(value.serverCookies), accessToken = value.accessToken?.let(transform),
            refreshToken = transform(value.refreshToken), accounts = value.accounts.map { stored ->
                stored.copy(session = stored.session.copy(sessData = transform(stored.session.sessData), csrf = transform(stored.session.csrf),
                    accessToken = transform(stored.session.accessToken), refreshToken = transform(stored.session.refreshToken)),
                    cookies = cookies(stored.cookies), serverCookies = servers(stored.serverCookies))
            })
    }

    @Serializable
    private data class ServerCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
        val persistent: Boolean,
    ) {
        fun toCookie(): Cookie? = runCatching {
            require(isBilibiliHost(domain))
            val builder = Cookie.Builder().name(name).value(value).path(path)
            if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
            if (persistent) builder.expiresAt(expiresAt)
            if (secure) builder.secure()
            if (httpOnly) builder.httpOnly()
            builder.build()
        }.getOrNull()

        companion object {
            fun fromCookie(cookie: Cookie) = ServerCookie(cookie.name, cookie.value, cookie.domain, cookie.path,
                cookie.expiresAt, cookie.secure, cookie.httpOnly, cookie.hostOnly, cookie.persistent)
        }
    }

    companion object {
        internal fun temporary() = DesktopSessionStore(defaultPath(), persistent = false)
        private val ACCOUNT_COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
        private val VISITOR_COOKIE_NAMES = setOf("buvid3", "buvid4", "buvid_fp", "b_nut", "b_lsid", "sid")
        private val SPI_COOKIE_NAMES = setOf("buvid3", "buvid4")
        internal val PERSISTED_COOKIE_NAMES = ACCOUNT_COOKIE_NAMES + VISITOR_COOKIE_NAMES
        internal fun isBilibiliHost(host: String): Boolean = host == "bilibili.com" || host.endsWith(".bilibili.com")
        private fun belongsToResponseHost(cookie: Cookie, host: String): Boolean = isBilibiliHost(cookie.domain) &&
            (cookie.domain == host || !cookie.hostOnly && host.endsWith(".${cookie.domain}"))
        private fun defaultPath(): Path {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), ".local", "share").toString()
            return Path.of(base, "BiliPai", "session.json")
        }
    }
}
