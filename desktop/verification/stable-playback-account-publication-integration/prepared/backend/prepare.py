from pathlib import Path
import hashlib,importlib.util,json,re,sys,textwrap
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def load(p,n):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
parser=load(REPO/'desktop/tools/sync-upstream.py','playback_parser')
media=load(REPO/'desktop/tools/extract-upstream-media.py','playback_selector')
changes=[]
def modify(rel,steps):
 s=read(REPO/rel);original=s;rows=[]
 for old,new,count in steps:
  assert s.count(old)==count,(rel,old[:100],s.count(old),count)
  rows.append(dict(before=old,after=new,occurrences=count));s=s.replace(old,new)
 assert s!=original,rel
 write(HERE/'proof-only'/rel,s)
 changes.append(dict(path=rel,baseLF=sha(original),candidateLF=sha(s),hunks=rows,proofWholeFileNotInstallPayload=True))
 return s
BASE='desktop/src/main/kotlin/com/bilipai/desktop/'

# The original isolated CookieJar body is selected unchanged except declaration visibility/name.
src=read(REPO/'app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt')
tokens=parser.kotlin_tokens(src);start,end=parser.kotlin_structure(tokens,'class','PlaybackAccountCookieJar')
begin=src.rfind('\n',0,tokens[start][1])+1
jar=src[begin:tokens[end][2]]
jar=jar.replace('private class PlaybackAccountCookieJar','internal class DesktopOriginalPlaybackAccountCookieJar',1)
manual='''package com.bilipai.desktop.data

import com.android.purebilibili.core.store.StoredAccountSession

/** Nonsecret proof of authorization. This is owned by the existing encrypted SessionStore. */
data class DesktopPlaybackAuthorizationReceipt internal constructor(val accountEpoch: Long, val revision: Long)

internal class DesktopPlaybackAuthorization internal constructor(
    val receipt: DesktopPlaybackAuthorizationReceipt,
    val playbackAccount: StoredAccountSession?,
    internal val cookieJar: DesktopOriginalPlaybackAccountCookieJar?,
) {
    override fun toString() = "DesktopPlaybackAuthorization(receipt=$receipt, dedicated=${cookieJar != null})"
}

'''+jar+'\n'
write(HERE/'prepared'/BASE/'data/DesktopPlaybackAuthorization.kt',manual)

store_members='''    // Original playback_mid preference is kept in THIS encrypted file, not another account store.
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
    internal fun playbackRequestCookies(authorization: DesktopPlaybackAuthorization, url: HttpUrl,
        stillOwned: () -> Boolean): Map<String, String> = withPlaybackAuthorizationAdmission(authorization.receipt, stillOwned) {
        authorization.cookieJar?.loadForRequest(url)?.associate { it.name to it.value } ?: currentCookies()
    }

'''
ss=read(REPO/BASE/'data/DesktopSessionStore.kt')
steps=[('    private val serverCookies = saved.serverCookies',store_members+'    private val serverCookies = saved.serverCookies',1),
 ('        val accounts: List<SavedAccount> = emptyList(),','        val accounts: List<SavedAccount> = emptyList(),\n        @kotlinx.serialization.SerialName("playback_mid") val playbackAccountMid: Long? = null,',1),
 ('SavedSession(accounts = accountRecords(saved), loginBuvid = saved.loginBuvid)','SavedSession(accounts = accountRecords(saved), loginBuvid = saved.loginBuvid, playbackAccountMid = saved.playbackAccountMid)',1),
 ('        val next = if (saved.account?.mid == mid) SavedSession(accounts = remaining, loginBuvid = saved.loginBuvid) else saved.copy(accounts = remaining)',
  '        val playbackMid = saved.playbackAccountMid?.takeUnless { it == mid }\n        val next = if (saved.account?.mid == mid) SavedSession(accounts = remaining, loginBuvid = saved.loginBuvid, playbackAccountMid = playbackMid) else saved.copy(accounts = remaining, playbackAccountMid = playbackMid)',1),
 ('            val now = System.currentTimeMillis()',
  '''            requestPlaybackAuthorization.get()?.let { authorization ->
                if (!isPlaybackAuthorizationCurrent(authorization.receipt)) return
                authorization.cookieJar?.let { it.saveFromResponse(url, cookies); return }
            }
            val now = System.currentTimeMillis()''',1),
 ('            requestGuestBuvid3.get()?.let { visitor ->',
  '''            requestPlaybackAuthorization.get()?.let { authorization ->
                if (!isPlaybackAuthorizationCurrent(authorization.receipt)) throw java.io.IOException("Playback authorization retired")
                authorization.cookieJar?.let { return@synchronized it.loadForRequest(url) }
            }
            requestGuestBuvid3.get()?.let { visitor ->''',1)]
# Refresh the revision at every existing persisted SavedSession assignment. No mirrored account list.
for assignment in ['saved = next','saved = newSaved','saved = cleared']:
 # Count only original sites; the newly added set method already explicitly refreshes.
 count=ss.count(assignment)
 steps.append((assignment,assignment+'; refreshPlaybackAuthorizationLocked()',count+(1 if assignment=='saved = next' else 0)))
steps.append(('        persist(next); saved = next; refreshPlaybackAuthorizationLocked()\n        refreshPlaybackAuthorizationLocked(force = true)',
              '        persist(next); saved = next\n        refreshPlaybackAuthorizationLocked(force = true)',1))
modify(BASE+'data/DesktopSessionStore.kt',steps)

repo_members='''    internal val playbackAuthorizationRevision: StateFlow<Long> get() = sessions.playbackAuthorizationRevision
    internal fun storedAccountSessions() = sessions.storedAccountSessions()
    internal fun activeAccountMid() = sessions.activeAccountMid()
    internal fun getPlaybackAccountMid() = sessions.getPlaybackAccountMid()
    internal fun getPlaybackAccount() = sessions.getPlaybackAccount()
    internal fun setPlaybackAccountMid(mid: Long?, expectedEpoch: Long, stillOwned: () -> Boolean): Boolean =
        sessions.setPlaybackAccountMid(mid, expectedEpoch, stillOwned)
    internal fun capturePlaybackAuthorization(expectedEpoch: Long, stillOwned: () -> Boolean) =
        sessions.capturePlaybackAuthorization(expectedEpoch, stillOwned)
    internal fun assertPlaybackAuthorization(authorization: DesktopPlaybackAuthorization, stillOwned: () -> Boolean) =
        sessions.withPlaybackAuthorizationAdmission(authorization.receipt, stillOwned) { Unit }
    internal fun isPlaybackSourceCurrent(source: PlaybackSource): Boolean =
        source.authorizationReceipt?.let(sessions::isPlaybackAuthorizationCurrent) == true
    internal suspend fun signPlaybackWebParams(params: Map<String, String>, authorization: DesktopPlaybackAuthorization,
        stillOwned: () -> Boolean, includeRiskFingerprint: Boolean = false): Map<String, String> {
        assertPlaybackAuthorization(authorization, stillOwned)
        val navApi = ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", authorization.receipt.accountEpoch, stillOwned)
        val signed = sign(params, includeRiskFingerprint = includeRiskFingerprint, requestApi = navApi,
            expectedEpoch = authorization.receipt.accountEpoch, stillOwned = stillOwned)
        assertPlaybackAuthorization(authorization, stillOwned)
        return signed
    }
    internal fun <T> withPlaybackSourceAdmission(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
        val receipt = source.authorizationReceipt ?: throw kotlinx.coroutines.CancellationException("Missing playback authorization receipt")
        return sessions.withPlaybackAuthorizationAdmission(receipt, stillOwned, block)
    }
    /** Uses this client's existing dispatcher/pool/transport and the same Store CookieJar. */
    internal fun ownedPlaybackCallFactory(authorization: DesktopPlaybackAuthorization, stillOwned: () -> Boolean): okhttp3.Call.Factory =
        okhttp3.Call.Factory { request ->
            if (!sessions.isPlaybackAuthorizationCurrent(authorization.receipt) || !stillOwned()) throw java.io.IOException("Playback authorization retired")
            client.newCall(request.newBuilder().tag(DesktopSessionEpoch::class.java,
                DesktopSessionEpoch(authorization.receipt.accountEpoch, stillOwned, playbackAuthorization = authorization)).build())
        }
    internal fun <T> ownedPlaybackService(type: Class<T>, authorization: DesktopPlaybackAuthorization, stillOwned: () -> Boolean): T =
        Retrofit.Builder().baseUrl("https://api.bilibili.com/").callFactory(ownedPlaybackCallFactory(authorization, stillOwned))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(type)

'''
rs=read(REPO/BASE/'data/DesktopRepository.kt')
before_play=media.function(rs,'playback',parser)
after_play=before_play
def play_change(old,new,count=1):
 global after_play
 assert after_play.count(old)==count,(old,after_play.count(old));after_play=after_play.replace(old,new)
play_change('    ensureVisitorSession()', '''    val epoch = sessions.generation
    val requestJob = currentCoroutineContext()[kotlinx.coroutines.Job]
    val owned = { requestJob?.isActive != false }
    ensureVisitorSession(epoch, owned)
    currentCoroutineContext().ensureActive()
    val authorization = capturePlaybackAuthorization(epoch, owned)
    val playbackApi = ownedPlaybackService(BilibiliApi::class.java, authorization, owned)
    val primaryNavApi = ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, owned)
    fun assertCurrent() = assertPlaybackAuthorization(authorization, owned)''')
play_change('    val epoch = sessions.generation\n    val cacheKey', '    val cacheKey')
play_change('        av1Supported = resolveEffectiveAv1Support(true, blockedSnapshot))','        av1Supported = resolveEffectiveAv1Support(true, blockedSnapshot), authorizationRevision = authorization.receipt.revision)')
play_change('    if (forceRefresh) playbackCache.invalidateVideo(epoch, details.bvid, part.cid)\n    else playbackCache.get(cacheKey)?.let { cached ->',
'''    val cached = sessions.withPlaybackAuthorizationAdmission(authorization.receipt, owned) {
        if (forceRefresh) { playbackCache.invalidateVideo(epoch, details.bvid, part.cid); null } else playbackCache.get(cacheKey)
    }
    cached?.let { cached ->''')
play_change('            if (epoch != sessions.generation) throw BiliApiException(-101, "账号已切换，请重新加载")\n            return@withContext it',
            '            assertCurrent()\n            return@withContext it.copy(authorizationReceipt = authorization.receipt)')
play_change('        if (epoch != sessions.generation) throw BiliApiException(-101, "账号已切换，请重新加载")\n        playbackCache.put(cacheKey, requireNotNull(data), targetQuality)\n        return source',
'''        sessions.withPlaybackAuthorizationAdmission(authorization.receipt, owned) { playbackCache.put(cacheKey, requireNotNull(data), targetQuality) }
        return source.copy(authorizationReceipt = authorization.receipt)''')
play_change('            if (retryDelay > 0) delay(retryDelay)','            if (retryDelay > 0) delay(retryDelay)\n            assertCurrent()')
play_change('val params = sign(buildPlayUrlWbiBaseParams(details.bvid, part.cid, targetQuality), forceRefresh = refreshSignature)',
            'val params = sign(buildPlayUrlWbiBaseParams(details.bvid, part.cid, targetQuality), forceRefresh = refreshSignature, requestApi = primaryNavApi, expectedEpoch = epoch, stillOwned = owned)\n                assertCurrent()')
play_change('val response = api.getPlayUrl(params)','val response = playbackApi.getPlayUrl(params)\n                assertCurrent()')
play_change('val response = api.getPlayUrlLegacy(details.bvid, part.cid, qn = quality)','assertCurrent()\n        val response = playbackApi.getPlayUrlLegacy(details.bvid, part.cid, qn = quality)\n        assertCurrent()')
play_change('lastError = error','assertCurrent()\n                lastError = error',2)
after_play=after_play.replace('        assertCurrent()\n                lastError = error\n    }','        assertCurrent()\n        lastError = error\n    }')
tag='private data class DesktopSessionEpoch(val value: Long, val stillOwned: () -> Boolean = { true }, val guestBuvid3: String? = null)'
steps=[(tag,tag[:-1]+', val playbackAuthorization: DesktopPlaybackAuthorization? = null)',1),
 ('    // Original guest CookieJar identity generation expression',repo_members+'    // Original guest CookieJar identity generation expression',1),
 ('if (expectedEpoch != sessions.generation || !requestOwner.stillOwned())',
  'if (expectedEpoch != sessions.generation || !requestOwner.stillOwned() || requestOwner.playbackAuthorization?.let { !sessions.isPlaybackAuthorizationCurrent(it.receipt) } == true)',2),
 ('                else sessions.homeRequestCookies(expectedEpoch, requestOwner.stillOwned)',
  '                else requestOwner.playbackAuthorization?.let { sessions.playbackRequestCookies(it, original.url, requestOwner.stillOwned) } ?: sessions.homeRequestCookies(expectedEpoch, requestOwner.stillOwned)',1),
 ('            sessions.requestGeneration.set(expectedEpoch)','            requestOwner.playbackAuthorization?.let { sessions.requestPlaybackAuthorization.set(it) }\n            sessions.requestGeneration.set(expectedEpoch)',1),
 ('                sessions.requestGuestBuvid3.remove()','                sessions.requestPlaybackAuthorization.remove()\n                sessions.requestGuestBuvid3.remove()',1),
 ('epoch != sessions.generation || !requestOwner.stillOwned()',
  'epoch != sessions.generation || !requestOwner.stillOwned() || requestOwner.playbackAuthorization?.let { !sessions.isPlaybackAuthorizationCurrent(it.receipt) } == true',2),
 (textwrap.indent(before_play,'    '),textwrap.indent(after_play,'    '),1)]
modify(BASE+'data/DesktopRepository.kt',steps)

ms=read(REPO/BASE/'data/DesktopMediaRepository.kt')
before=media.function(ms,'bangumiPlaybackInfo',parser);after=before
after=after.replace('    repository.ensureSession()', '''    val epoch = repository.sessionEpoch
    val requestJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
    val owned = { requestJob?.isActive != false }
    val visitorApi = repository.ownedHomeService(com.android.purebilibili.core.network.BuvidApi::class.java,
        "https://api.bilibili.com/", epoch, owned)
    repository.ensureOwnedHomeSession(epoch, owned, visitorApi)
    val authorization = repository.capturePlaybackAuthorization(epoch, owned)
    fun assertCurrent() = repository.assertPlaybackAuthorization(authorization, owned)
    assertCurrent()
    val playbackApi = repository.ownedPlaybackService(com.android.purebilibili.core.network.BangumiApi::class.java, authorization, owned)''')
after=after.replace('repository.signWebParams(params, includeRiskFingerprint = season.isCourse)',
                    'repository.signPlaybackWebParams(params, authorization, owned, includeRiskFingerprint = season.isCourse)')
after=after.replace('        block().use { decodeBangumiPlayUrlPayload(it.string(), json) }',
'''        assertCurrent()
        val value = block().use { decodeBangumiPlayUrlPayload(it.string(), json) }
        assertCurrent()
        value''')
after=after.replace('    } catch (_: Exception) { null }','    } catch (_: Exception) { assertCurrent(); null }')
after=after.replace('api.bangumi.','playbackApi.')
after=after.replace('    val source = selectBangumi(video, season, episode, quality)',
'''    assertCurrent()
    val source = selectBangumi(video, season, episode, quality).copy(authorizationReceipt = authorization.receipt)''')
modify(BASE+'data/DesktopMediaRepository.kt',[(textwrap.indent(before,'    '),textwrap.indent(after,'    '),1)])
modify(BASE+'data/DesktopPlaybackCache.kt',[
 ('        val av1Supported: Boolean = true)','        val av1Supported: Boolean = true, val authorizationRevision: Long = 0)',1),
 ('entries.keys.removeAll { it.accountEpoch != key.accountEpoch }','entries.keys.removeAll { it.accountEpoch != key.accountEpoch || it.authorizationRevision != key.authorizationRevision }',1)])
modify(BASE+'data/DesktopModels.kt',[
 ('    val audioSelection: com.android.purebilibili.feature.video.playback.audio.AudioSelectionDecision? = null,',
  '    val audioSelection: com.android.purebilibili.feature.video.playback.audio.AudioSelectionDecision? = null,\n    val authorizationReceipt: DesktopPlaybackAuthorizationReceipt? = null,',1)])
write(HERE/'local-hunks.json',json.dumps(changes,ensure_ascii=False,indent=2)+'\n')
write(HERE/'source-inventory.json',json.dumps(dict(originalCookieJar=dict(path='app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt',sha256LF=sha(src),selectedBodySha256LF=sha(jar.replace('internal class DesktopOriginalPlaybackAccountCookieJar','private class PlaybackAccountCookieJar',1)),adaptation='declaration name/visibility only; full original body'),newManual=dict(path=BASE+'data/DesktopPlaybackAuthorization.kt',sha256LF=sha(manual)),existingSourceHunks=[dict(path=row['path'],baseLF=row['baseLF'],candidateLF=row['candidateLF']) for row in changes],newClient=False,newStore=False,newAccountList=False,newCache=False,preparedOnly=True),ensure_ascii=False,indent=2)+'\n')
print('prepared '+str(len(changes))+' exact existing-file delta families + one selected/manual source')
