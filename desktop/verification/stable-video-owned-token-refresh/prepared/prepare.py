from pathlib import Path
import hashlib,json,difflib
P=Path(__file__).resolve().parent;MAIN=P.parents[2];C=MAIN.parent/'BiliPai-v023';BRIDGE=MAIN/'desktop/.local/stable-video-repository-core-ports-parity'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n').decode()
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def save(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
rel='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt';base=read(BRIDGE/'proof-only'/rel)
assert sha(base)=='da3e69796b62bbaf9f1e399229ea3a8a99ea4bd4102f140df04ceb611f68b320'
text=base;hunks=[]
def hunk(label,before,after):
 global text
 assert text.count(before)==1,(label,text.count(before));text=text.replace(before,after,1);hunks.append(dict(label=label,before=before,after=after))
hunk('reuse-existing-no-cookie-validation-retrofit',
'''    private val validationPassportApi = Retrofit.Builder().baseUrl("https://passport.bilibili.com/")
        .client(client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(PassportApi::class.java)
''',
'''    private val validationPassportRetrofit = Retrofit.Builder().baseUrl("https://passport.bilibili.com/")
        .client(client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val validationPassportApi = validationPassportRetrofit.create(PassportApi::class.java)
''')
start=text.index('    internal suspend fun installLogin(');end=text.index('    suspend fun switchAccount(',start)
before=text[start:end]
after='''    internal suspend fun installLogin(cookies: Map<String, String>, credentials: DesktopAppCredentials? = null,
        snapshot: DesktopSessionStore.CookieSnapshot? = null, expectedMid: Long? = null,
        expectedActiveMid: Long? = null, requestReceipt: DesktopPlaybackAuthorizationReceipt? = null,
        stillOwned: () -> Boolean = { true }, commitIfCurrent: ((() -> Unit) -> Boolean)? = null): AccountSummary = withContext(Dispatchers.IO) {
        authMutex.withLock {
            val epoch = requestReceipt?.accountEpoch ?: sessions.generation
            fun assertRequest() {
                if (requestReceipt != null) sessions.withPlaybackAuthorizationAdmission(requestReceipt, stillOwned) { Unit }
                else if (!stillOwned()) throw CancellationException("Login request retired")
            }
            currentCoroutineContext().ensureActive(); assertRequest()
            if (expectedActiveMid != null && account.value?.mid != expectedActiveMid) throw BiliApiException(-101, "账号已切换，请重新操作")
            val validationApi = if (requestReceipt == null) validationPassportApi else {
                // Same existing NO_COOKIES validation transport, tagged before newCall.
                val current = { stillOwned() && sessions.isPlaybackAuthorizationCurrent(requestReceipt) }
                validationPassportRetrofit.newBuilder().callFactory(playbackReceiptCalls(requestReceipt, current,
                    validationPassportRetrofit.callFactory())).build().create(PassportApi::class.java)
            }
            val summary = validateCookieHeader(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }, validationApi)
            currentCoroutineContext().ensureActive(); assertRequest()
            if (expectedMid != null) require(summary.mid == expectedMid) { "登录返回的账号与验证结果不一致" }
            if (expectedActiveMid != null && account.value?.mid != expectedActiveMid) throw BiliApiException(-101, "账号已切换，请重新操作")
            if (epoch != sessions.generation) throw BiliApiException(-101, "账号已变化，请重新登录")
            if (requestReceipt == null) {
                resetAuthentication()
                sessions.saveAccount(cookies, summary, imported = true, credentials = credentials, preserveAccessToken = false, snapshot = snapshot)
            } else {
                val callerContext = currentCoroutineContext()
                sessions.withPlaybackAuthorizationAdmission(requestReceipt, stillOwned) {
                    val commit = requireNotNull(commitIfCurrent) { "Owned token install requires the Root entry gate" }
                    if (!commit {
                        callerContext.ensureActive()
                        if (!stillOwned()) throw CancellationException("Token install entry retired")
                        // Short synchronous cache/Store commit only. Do not cancel the
                        // shared dispatcher or stop any already-running native source.
                        resetAuthenticationCaches()
                        sessions.saveAccount(cookies, summary, imported = true, credentials = credentials,
                            preserveAccessToken = false, snapshot = snapshot)
                    }) throw CancellationException("Token install entry retired")
                }
            }
            summary
        }
    }

'''
hunk('owned-login-validation-and-terminal-install',before,after)
hunk('split-cache-invalidation-from-dispatcher-cancellation',
'''    private fun resetAuthentication() {
        client.dispatcher.cancelAll()
        visitorInitialized = false
''',
'''    private fun resetAuthentication() {
        client.dispatcher.cancelAll()
        resetAuthenticationCaches()
    }
    private fun resetAuthenticationCaches() {
        visitorInitialized = false
''')
start=text.index('    private suspend fun validateCookieHeader(');end=text.index('    private suspend fun sign(',start)
before=text[start:end]
after=before.replace('private suspend fun validateCookieHeader(header: String): AccountSummary',
 'private suspend fun validateCookieHeader(header: String, validationApi: PassportApi = validationPassportApi): AccountSummary').replace('val response = validationPassportApi.validateCookieSession(header)','val response = validationApi.validateCookieSession(header)')
assert after!=before
hunk('same-original-cookie-validation-body-owned-service-parameter',before,after)
write(P/'baseline'/rel,base);write(P/'proof-only'/rel,text)
save(P/'repository-local-hunks.json',dict(path=rel,baseLFsha256=sha(base),candidateLFsha256=sha(text),dependsOnBridge41=True,hunks=hunks))
write(P/'repository-local.patch',''.join(difflib.unified_diff(base.splitlines(True),text.splitlines(True),fromfile=rel,tofile=rel)))
rel='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopLoginRepository.kt';base=read(C/rel);text=base;hunks=[]
before='''    suspend fun refreshTvToken(): AccountSummary = action {
        val account = repository.requireAccount(); val cookies = repository.authCookies()
        val credentials = repository.appCredentials() ?: throw BiliApiException(-101, "此账号没有 App 授权，请重新扫码")
        require(credentials.platform == "tv" && credentials.refreshToken.isNotBlank()) { "此授权不支持 TV 刷新，请重新登录" }
        val params = mapOf("access_key" to credentials.accessToken, "refresh_token" to credentials.refreshToken,
            "appkey" to AppSignUtils.TV_APP_KEY, "ts" to AppSignUtils.getTimestamp().toString())
        val response = api.refreshToken(AppSignUtils.signForTvLogin(params)); check(response.code, response.message)
        val data = response.data ?: throw BiliApiException(-1, "授权刷新结果为空")
        require(data.accessToken.isNotBlank()) { "授权刷新没有返回 token" }
        val updated = cookies + data.cookieInfo?.cookies.orEmpty().associate { it.name to it.value }.filterKeys { it in DesktopSessionStore.PERSISTED_COOKIE_NAMES }
        repository.installLogin(updated, DesktopAppCredentials(data.accessToken, data.refreshToken, "tv", expiry(data.expiresIn)),
            expectedMid = account.mid, expectedActiveMid = account.mid)
    }
'''
after='''    suspend fun refreshTvToken(): AccountSummary = action { refreshTvTokenBody(api) }

    /** Original TokenRefreshHelper availability, projected from the SAME primary Store.
     * Caller invokes this inside its existing receipt admission. */
    internal fun originalPlaybackTokenRefreshAvailable(): Boolean = repository.appCredentials()?.let {
        it.platform == "tv" && it.refreshToken.isNotBlank()
    } == true

    /** The sole login actor, with caller Job + Root entry + captured authorization.
     * Success is terminal: the new credentials invalidate the supplied old receipt. */
    internal suspend fun refreshTvTokenForOriginalPlayback(receipt: DesktopPlaybackAuthorizationReceipt,
        stillOwned: () -> Boolean, commitIfCurrent: ((() -> Unit) -> Boolean)): Boolean {
        val requestJob = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        val alive = { requestJob.isActive && stillOwned() }
        return try {
            action {
                repository.withPlaybackReceiptAdmission(receipt, alive) {
                    val authorization = repository.capturePlaybackAuthorization(receipt.accountEpoch, alive)
                    if (authorization.playbackAccount != null) throw kotlinx.coroutines.CancellationException("Selected playback account does not refresh primary token")
                }
                if (!repository.withPlaybackReceiptAdmission(receipt, alive) { originalPlaybackTokenRefreshAvailable() }) return@action false
                val current = { alive() && repository.isPlaybackReceiptCurrent(receipt) }
                val ownedApi = repository.ownedHomeService(PassportApi::class.java, "https://passport.bilibili.com/", receipt.accountEpoch, current)
                refreshTvTokenBody(ownedApi, receipt, alive, commitIfCurrent)
                // Do not assert the OLD receipt after successful replacement. The raw
                // binding will cancel its old load and Root will capture a new one.
                true
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Transport may wrap a retired owner in IOException. Cancellation must
            // not become ordinary false and continue an obsolete fallback request.
            repository.withPlaybackReceiptAdmission(receipt, alive) { Unit }
            false // Original TokenRefreshHelper ordinary failure on a CURRENT owner.
        }
    }

    private suspend fun refreshTvTokenBody(requestApi: PassportApi, receipt: DesktopPlaybackAuthorizationReceipt? = null,
        stillOwned: () -> Boolean = { true }, commitIfCurrent: ((() -> Unit) -> Boolean)? = null): AccountSummary {
        val account = if (receipt == null) repository.requireAccount() else repository.withPlaybackReceiptAdmission(receipt, stillOwned) { repository.requireAccount() }
        val cookies = if (receipt == null) repository.authCookies() else repository.withPlaybackReceiptAdmission(receipt, stillOwned) { repository.authCookies() }
        val credentials = (if (receipt == null) repository.appCredentials() else repository.withPlaybackReceiptAdmission(receipt, stillOwned) { repository.appCredentials() })
            ?: throw BiliApiException(-101, "此账号没有 App 授权，请重新扫码")
        require(credentials.platform == "tv" && credentials.refreshToken.isNotBlank()) { "此授权不支持 TV 刷新，请重新登录" }
        val params = mapOf("access_key" to credentials.accessToken, "refresh_token" to credentials.refreshToken,
            "appkey" to AppSignUtils.TV_APP_KEY, "ts" to AppSignUtils.getTimestamp().toString())
        val response = requestApi.refreshToken(AppSignUtils.signForTvLogin(params)); check(response.code, response.message)
        if (receipt != null) repository.withPlaybackReceiptAdmission(receipt, stillOwned) { Unit }
        val data = response.data ?: throw BiliApiException(-1, "授权刷新结果为空")
        require(data.accessToken.isNotBlank()) { "授权刷新没有返回 token" }
        val updated = cookies + data.cookieInfo?.cookies.orEmpty().associate { it.name to it.value }.filterKeys { it in DesktopSessionStore.PERSISTED_COOKIE_NAMES }
        return repository.installLogin(updated, DesktopAppCredentials(data.accessToken, data.refreshToken, "tv", expiry(data.expiresIn)),
            expectedMid = account.mid, expectedActiveMid = account.mid, requestReceipt = receipt, stillOwned = stillOwned, commitIfCurrent = commitIfCurrent)
    }
'''
hunk('same-refresh-actor-body-and-required-owned-tail',before,after)
write(P/'baseline'/rel,base);write(P/'proof-only'/rel,text)
save(P/'login-local-hunks.json',dict(path=rel,baseLFsha256=sha(base),candidateLFsha256=sha(text),hunks=hunks))
write(P/'login-local.patch',''.join(difflib.unified_diff(base.splitlines(True),text.splitlines(True),fromfile=rel,tofile=rel)))
for family in ['repository','login']:
 d=json.loads(read(P/(family+'-local-hunks.json')));t=read(P/'proof-only'/d['path'])
 for h in reversed(d['hunks']):assert t.count(h['after'])==1;t=t.replace(h['after'],h['before'],1)
 assert sha(t)==d['baseLFsha256']
save(P/'source-inverse-result.json',dict(passed=True,repositoryHunks=4,loginHunks=1,dependsOnFrozenBridge41=True))
print('owned TV refresh 4 Repository + 1 sole Login actor exact hunks prepared')
