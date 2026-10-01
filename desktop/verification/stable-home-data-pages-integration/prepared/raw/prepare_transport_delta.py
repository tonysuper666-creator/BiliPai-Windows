from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[3];REPO=ROOT/'BiliPai-v023';PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def replace(s,old,new):assert s.count(old)==1,(old[:100],s.count(old));return s.replace(old,new)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
rows=[];patches=[]
rel='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt';src=read(REPO/rel);desired=src
desired=replace(desired,'private data class DesktopSessionEpoch(val value: Long)','private data class DesktopSessionEpoch(val value: Long, val stillOwned: () -> Boolean = { true }, val guestBuvid3: String? = null)')
desired=replace(desired,'            applyDesktopMergedRecommendationHeaders(builder, original.url, sessions.currentCookies()["buvid3"].orEmpty())','''            val requestCookies = if (requestOwner.guestBuvid3 != null) emptyMap()
                else sessions.homeRequestCookies(expectedEpoch, requestOwner.stillOwned)
            applyDesktopMergedRecommendationHeaders(builder, original.url, requestOwner.guestBuvid3 ?: requestCookies["buvid3"].orEmpty())''')
desired=replace(desired,'val buvid = original.header("X-BiliPai-Login-Buvid") ?: sessions.currentCookies()["buvid3"].orEmpty()','val buvid = original.header("X-BiliPai-Login-Buvid") ?: requestOwner.guestBuvid3 ?: requestCookies["buvid3"].orEmpty()')
desired=replace(desired,'''            val expectedEpoch = sessions.generation
            val builder = original.newBuilder().tag(DesktopSessionEpoch::class.java, DesktopSessionEpoch(expectedEpoch))''','''            // An owner-bound Call.Factory stamps this before newCall/enqueue. Preserve it here;
            // choosing the current epoch at execution would authorize an old queued request.
            val requestOwner = original.tag(DesktopSessionEpoch::class.java) ?: DesktopSessionEpoch(sessions.generation)
            val expectedEpoch = requestOwner.value
            if (expectedEpoch != sessions.generation || !requestOwner.stillOwned())
                throw java.io.IOException("Request owner retired")
            val builder = original.newBuilder().tag(DesktopSessionEpoch::class.java, requestOwner)''')
desired=replace(desired,'''            sessions.requestGeneration.set(expectedEpoch)
            val response = try { chain.proceed(builder.build()) } finally { sessions.requestGeneration.remove() }
            if (expectedEpoch != sessions.generation) { response.close(); throw BiliApiException(-101, "账号已切换，请重新加载") }''','''            sessions.requestGeneration.set(expectedEpoch)
            sessions.requestAdmission.set(requestOwner.stillOwned)
            sessions.requestGuestBuvid3.set(requestOwner.guestBuvid3)
            val response = try { chain.proceed(builder.build()) } finally {
                sessions.requestGuestBuvid3.remove()
                sessions.requestAdmission.remove()
                sessions.requestGeneration.remove()
            }
            if (expectedEpoch != sessions.generation || !requestOwner.stillOwned()) {
                response.close(); throw BiliApiException(-101, "账号已切换，请重新加载")
            }''')
desired=replace(desired,'''            val epoch = request.tag(DesktopSessionEpoch::class.java)?.value ?: sessions.generation
            if (epoch != sessions.generation) throw BiliApiException(-101, "账号已切换，请重新操作")''','''            val requestOwner = request.tag(DesktopSessionEpoch::class.java) ?: DesktopSessionEpoch(sessions.generation)
            val epoch = requestOwner.value
            if (epoch != sessions.generation || !requestOwner.stillOwned()) throw BiliApiException(-101, "账号已切换，请重新操作")''')
desired=replace(desired,'if (epoch != sessions.generation) response.newBuilder().headers(response.headers.newBuilder().removeAll("Set-Cookie").build()).build()','if (epoch != sessions.generation || !requestOwner.stillOwned()) response.newBuilder().headers(response.headers.newBuilder().removeAll("Set-Cookie").build()).build()')
desired=replace(desired,'            val replacement = if (forcedCookie != null) {','''            val replacement = if (requestOwner.guestBuvid3 != null) {
                // The original guest route has fresh visitor identity only; explicit account
                // headers cannot override its guest CookieJar boundary.
                request.newBuilder().removeHeader(FORCE_COOKIE_HEADER)
                    .header("Cookie", "buvid3=${requestOwner.guestBuvid3}").build()
            } else if (forcedCookie != null) {''')
anchor='    internal val httpClient: OkHttpClient get() = client'
members='''    // Original guest CookieJar identity generation expression, now a scalar on the SAME
    // existing transport owner. No second CookieJar/cache/client or account credential copy.
    private val homeGuestBuvid3: String by lazy { java.util.UUID.randomUUID().toString().replace("-", "") + "infoc" }

    /** Admission facade over the SAME client/dispatcher/pool/CookieJar. No client or cookie
     * state is constructed. The immutable epoch and lifetime are attached before Call creation. */
    internal fun ownedHomeCallFactory(expectedEpoch: Long, stillOwned: () -> Boolean, guest: Boolean = false): okhttp3.Call.Factory =
        okhttp3.Call.Factory { request ->
            if (expectedEpoch != sessions.generation || !stillOwned())
                throw java.io.IOException("Home request owner retired")
            client.newCall(request.newBuilder().tag(DesktopSessionEpoch::class.java,
                DesktopSessionEpoch(expectedEpoch, stillOwned, if (guest) homeGuestBuvid3 else null)).build())
        }

    /** Services over the SAME Call.Factory/client/cookie state, used once by retained Home. */
    internal fun <T> ownedHomeService(type: Class<T>, baseUrl: String, expectedEpoch: Long,
        stillOwned: () -> Boolean, guest: Boolean = false): T = Retrofit.Builder().baseUrl(baseUrl)
        .callFactory(ownedHomeCallFactory(expectedEpoch, stillOwned, guest))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(type)

    /** Reads the existing WBI cache, with its existing expiry and owner-bound nav API. */
    internal suspend fun homeWbiKeys(expectedEpoch: Long, stillOwned: () -> Boolean,
        ownedApi: BilibiliApi): Result<Pair<String, String>> {
        return try {
            if (expectedEpoch != sessions.generation || !stillOwned()) throw kotlinx.coroutines.CancellationException("Home epoch retired")
            sign(emptyMap(), requestApi = ownedApi, expectedEpoch = expectedEpoch, stillOwned = stillOwned)
            val keys = wbiMutex.withLock {
                if (expectedEpoch != sessions.generation || wbiGeneration != expectedEpoch || !stillOwned())
                    throw kotlinx.coroutines.CancellationException("Home WBI epoch retired")
                requireNotNull(wbiKeys)
            }
            Result.success(keys)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }

    internal suspend fun ensureOwnedHomeSession(expectedEpoch: Long, stillOwned: () -> Boolean,
        ownedBuvidApi: BuvidApi) = ensureVisitorSession(expectedEpoch, stillOwned,
            ownedHomeCallFactory(expectedEpoch, stillOwned), ownedBuvidApi)

    internal fun ownedHomeAccessToken(expectedEpoch: Long, stillOwned: () -> Boolean): String? =
        sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) { sessions.accessTokenCredentials().first }
    internal fun ownedHomeCookie(name: String, expectedEpoch: Long, stillOwned: () -> Boolean): String? =
        sessions.homeRequestCookies(expectedEpoch, stillOwned)[name]
    // SavedSession is synchronously read/decrypted by the existing Store constructor. This is
    // the actual same-owner restoration boundary, not a second deferred restoration cache.
    internal fun assertOwnedHomeSessionRestored(expectedEpoch: Long, stillOwned: () -> Boolean) =
        sessions.withHomeRequestAdmission(expectedEpoch, stillOwned) { Unit }

    internal fun updateHomeNavIdentity(expectedEpoch: Long, expectedMid: Long?, navMid: Long?, isVip: Boolean,
        onAuthenticationInvalidated: (Long, Long) -> Unit): Boolean =
        sessions.updateHomeNavIdentity(expectedEpoch, expectedMid, navMid, isVip, onAuthenticationInvalidated)

'''
desired=replace(desired,anchor,members+anchor)
# Both entry points retain the sole existing cache/mutex/expiry logic. Their optional parameters
# only choose the immutable request owner and the already-owner-bound view of the same client.
desired=replace(desired,'    private suspend fun ensureVisitorSession() = visitorMutex.withLock {\n        val generation = sessions.generation','''    private suspend fun ensureVisitorSession(expectedEpoch: Long? = null,
        stillOwned: () -> Boolean = { true }, callFactory: okhttp3.Call.Factory = client,
        visitorApi: BuvidApi = buvidApi) = visitorMutex.withLock {
        val generation = expectedEpoch ?: sessions.generation
        if (generation != sessions.generation || !stillOwned()) throw CancellationException("Visitor owner retired")''')
desired=replace(desired,'        client.newCall(Request.Builder().url("https://www.bilibili.com/")','        callFactory.newCall(Request.Builder().url("https://www.bilibili.com/")')
desired=replace(desired,'        val response = buvidApi.getSpi()','        val response = visitorApi.getSpi()')
desired=replace(desired,'        sessions.saveSpiCookies(visitors, generation)','''        if (generation != sessions.generation || !stillOwned()) throw CancellationException("Visitor owner retired")
        sessions.saveSpiCookies(visitors, generation)''')
desired=replace(desired,'        forceRefresh: Boolean = false): Map<String, String> {\n        val keys = wbiMutex.withLock {\n            val now = System.currentTimeMillis()\n            val generation = sessions.generation','''        forceRefresh: Boolean = false, requestApi: BilibiliApi = api, expectedEpoch: Long? = null,
        stillOwned: () -> Boolean = { true }): Map<String, String> {
        val keys = wbiMutex.withLock {
            val now = System.currentTimeMillis()
            val generation = expectedEpoch ?: sessions.generation
            if (generation != sessions.generation || !stillOwned()) throw CancellationException("WBI owner retired")''')
desired=replace(desired,'                val img = api.getNavInfo().data?.wbi_img','                val img = requestApi.getNavInfo().data?.wbi_img')
desired=replace(desired,'                wbiKeys = imageKey to subKey','''                if (generation != sessions.generation || !stillOwned()) throw CancellationException("WBI owner retired")
                wbiKeys = imageKey to subKey''')
write(HERE/'prepared/transport-delta'/rel,desired)
patches.append(''.join(difflib.unified_diff(src.splitlines(True),desired.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel)))
rows.append({'path':rel,'baseSha256Bytes':hashlib.sha256(safe(REPO/rel).read_bytes()).hexdigest(),'baseSha256LF':sha(src),'desiredSha256LF':sha(desired)})

rel='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSessionStore.kt';src=read(REPO/rel);desired=src
desired=replace(desired,'    internal val requestGeneration = ThreadLocal<Long>()','''    internal val requestGeneration = ThreadLocal<Long>()
    // The same synchronous OkHttp interceptor scope as requestGeneration, cleared in finally.
    internal val requestAdmission = ThreadLocal<() -> Boolean>()
    internal val requestGuestBuvid3 = ThreadLocal<String?>()''')
desired=replace(desired,'            if (requestGeneration.get()?.let { it != generation } == true) return','''            if (requestGeneration.get()?.let { it != generation } == true || requestAdmission.get()?.invoke() == false || requestGuestBuvid3.get() != null) return''')
desired=replace(desired,'            if (requestGeneration.get()?.let { it != generation } == true) throw BiliApiException(-101, "账号已切换，请重新加载")','''            if (requestGeneration.get()?.let { it != generation } == true || requestAdmission.get()?.invoke() == false)
                throw BiliApiException(-101, "账号已切换，请重新加载")''')
desired=replace(desired,'            removeExpired(System.currentTimeMillis())\n            val cookies = serverCookies.values.filter { it.matches(url) }.toMutableList()','''            requestGuestBuvid3.get()?.let { visitor ->
                return@synchronized listOf(Cookie.Builder().domain(url.host).name("buvid3").value(visitor).build())
            }
            removeExpired(System.currentTimeMillis())
            val cookies = serverCookies.values.filter { it.matches(url) }.toMutableList()''')
anchor='    internal fun loginIdentityBuvid(): String = synchronized(lock) { saved.loginBuvid }'
members='''    /** Original TokenManager MID/VIP cache intent is projected into this existing account.
     * Never adopt another MID or credentials, and never increment epoch for a profile flag. */
    internal fun updateHomeNavIdentity(expectedGeneration: Long, expectedMid: Long?, navMid: Long?, isVip: Boolean,
        onAuthenticationInvalidated: (Long, Long) -> Unit): Boolean = synchronized(lock) {
        if (generation != expectedGeneration || saved.account?.mid != expectedMid) return@synchronized false
        val current = saved.account
        if (current == null) return@synchronized navMid == null
        if (navMid == null) {
            // A current AUTH nav=-101 is an input to Root's existing account lifecycle, not
            // permission to silently clear/relabel the saved account or its credentials.
            onAuthenticationInvalidated(expectedGeneration, current.mid)
            return@synchronized true
        }
        if (navMid != current.mid) return@synchronized false
        if (current.isVip == isVip) return@synchronized true
        val updated = current.copy(isVip = isVip)
        var next = saved.copy(account = updated)
        next = next.copy(accounts = accountRecords(next))
        persist(next); saved = next
        mutableAccount.value = updated
        mutableAccounts.value = accountRecords(saved).map { it.toInfo() }
        true
    }

'''
members='''    internal fun <T> withHomeRequestAdmission(expectedGeneration: Long, stillOwned: () -> Boolean, block: () -> T): T = synchronized(lock) {
        if (generation != expectedGeneration || !stillOwned()) throw BiliApiException(-101, "账号已切换，请重新加载")
        block()
    }
    internal fun homeRequestCookies(expectedGeneration: Long, stillOwned: () -> Boolean): Map<String, String> =
        withHomeRequestAdmission(expectedGeneration, stillOwned) { currentCookies() }

'''+members
desired=replace(desired,anchor,members+anchor)
write(HERE/'prepared/transport-delta'/rel,desired)
patches.append(''.join(difflib.unified_diff(src.splitlines(True),desired.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel)))
rows.append({'path':rel,'baseSha256Bytes':hashlib.sha256(safe(REPO/rel).read_bytes()).hexdigest(),'baseSha256LF':sha(src),'desiredSha256LF':sha(desired)})
write(HERE/'shared-http-owner-admission.patch',''.join(patches))
write(HERE/'shared-http-owner-admission-base.json',json.dumps({'newClient':False,'newCookieState':False,'newAccountAuthority':False,'callFactoryIsFacade':True,'rows':rows},indent=2)+'\n')
print(json.dumps({'narrowSharedFiles':len(rows),'callFactory':'same existing OkHttpClient.newCall with immutable tag'}))
