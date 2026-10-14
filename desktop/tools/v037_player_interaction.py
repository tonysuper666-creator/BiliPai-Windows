"""Complete fixed v037 interaction methods on the existing Windows request owner."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='273e0f492c93c1437cbb2c56d1ce113398945e7c182f47ba68bf694c81dbf3af'

def fixed_sources():
    root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-player-interaction-seed'
    raw=(root/'manifest.json').read_bytes()
    if hashlib.sha256(raw).hexdigest()!=MANIFEST_SHA:raise ValueError('Fixed interaction manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT:raise ValueError('Unknown interaction closure')
    result={}
    for row in manifest['files']:
        raw=(root/row['filename']).read_bytes()
        if len(raw)!=row['bytes'] or hashlib.sha256(raw).hexdigest()!=row['sha256Bytes'] or hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=row['gitBlob']:raise ValueError('Fixed interaction input changed')
        result[row['filename']]=raw.decode('utf-8')
    return result

def admitted_cache_calls(text,parser):
    tokens=parser.kotlin_tokens(text);ranges=[]
    allowed={'interactionCache':{'read','queryVersion','publish','knownValues','beginOperation'},
             'followingGuard':{'snapshotVersion','accept','beginOperation'}}
    for i in range(len(tokens)-3):
        owner,dot,name,opening=[t[0] for t in tokens[i:i+4]]
        if owner not in allowed or dot!='.' or name not in allowed[owner] or opening!='(':continue
        depth=1;j=i+4
        while depth:
            if j>=len(tokens):raise ValueError('Unbalanced original cache call')
            depth+=(tokens[j][0]=='(')-(tokens[j][0]==')');j+=1
        ranges.append((tokens[i][1],tokens[j-1][2]))
    for begin,end in reversed(ranges):
        text=text[:begin]+'withInteractionAdmission { '+text[begin:end]+' }'+text[end:]
    return text,len(ranges)

def extend_same_producer(g):
    from v029_comment_search import whole_proof
    raw=fixed_sources();out=g['OUTPUT'];parser=g['parser'];select=g['selected'];rows=g['OUTPUTS']
    original=raw['ActionRepository.kt']
    interior=original[original.index('object ActionRepository {')+len('object ActionRepository {'):original.rfind('}')]
    names=['followUser','favoriteVideo','getDefaultFolderId','likeVideo','dislikeVideo','coinVideo',
      'tripleAction','toggleWatchLater','checkLikeStatus','checkFavoriteStatus','checkFollowStatus',
      'checkCoinStatus','followingStatusRevision','acceptFollowingSnapshot','cachedLikeStatus',
      'cachedFollowStatus','likeStatusVersion','followStatusVersion','mergeKnownFollowingMids',
      'queryLikeStatus','queryFollowStatus','TripleResult']
    methods=select(interior,names);original_methods=methods
    methods=methods.replace('TokenManager.csrfCache','readCsrf()').replace('TokenManager.midCache','readMid()').replace('TokenManager.sessDataCache','readSessData()').replace('TokenManager.accessTokenCache','readAccessToken()')
    methods=methods.replace('com.android.purebilibili.core.util.Logger','Logger').replace('android.util.Log.e','Logger.e')
    methods=methods.replace('_followStateChanges.tryEmit(FollowStateChange(mid = mid, isFollowing = follow))',
      'DesktopOriginalVideoEngagementPresentation.commitCurrent { assertOwned(); confirmFollow(FollowStateChange(mid = mid, isFollowing = follow)) }')
    methods=methods.replace('com.android.purebilibili.core.events.BrandSuccessEvents.followChanged(follow)',
      'DesktopOriginalVideoEngagementPresentation.confirmBrandFollow(follow)')
    methods=methods.replace('com.android.purebilibili.core.events.BrandSuccessEvents.favoriteSaved()',
      'DesktopOriginalVideoEngagementPresentation.confirmBrandFavorite()')
    methods=methods.replace('result.onSuccess { WatchLaterRefreshBus.notifyChanged() }',
      'result.onSuccess { withInteractionAdmission { WatchLaterRefreshBus.notifyChanged() } }')
    methods=methods.replace('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n            currentCoroutineContext().ensureActive(); assertOwned()')
    methods=methods.replace('                if (response.code == 0) {','                currentCoroutineContext().ensureActive(); assertOwned()\n                if (response.code == 0) {')
    methods=methods.replace('                when (response.code) {','                currentCoroutineContext().ensureActive(); assertOwned()\n                when (response.code) {')
    methods=methods.replace('                when {','                currentCoroutineContext().ensureActive(); assertOwned()\n                when {')
    methods=methods.replace('            val data = response.data','            currentCoroutineContext().ensureActive(); assertOwned()\n            val data = response.data')
    methods=methods.replace('            val response = api.getFavFolders(mid)','            currentCoroutineContext().ensureActive(); assertOwned()\n            val response = api.getFavFolders(mid)\n            currentCoroutineContext().ensureActive(); assertOwned()')
    methods=methods.replace('} catch (e: Exception) {','} catch (e: Exception) {\n                if (e is CancellationException) throw e\n                assertOwned()')
    methods,admitted=admitted_cache_calls(methods,parser)
    assert admitted>=10,admitted
    helper_start=interior.index('    private fun <T> Result<T>.rethrowCancellation()')
    helper_end=interior.index('\n    suspend fun queryLikeStatus',helper_start)
    cancellation_helper=interior[helper_start:helper_end]
    header="""package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.core.refresh.WatchLaterRefreshBus
import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionLog as Logger
import com.bilipai.desktop.ui.DesktopOriginalVideoEngagementPresentation
import kotlinx.coroutines.*

/** Original methods, one existing account repository and owner-bound API. */
internal class DesktopOriginalVideoEngagementProtocol(
    private val api: BilibiliApi,
    private val readCsrf: () -> String?,
    private val readMid: () -> Long?,
    private val readSessData: () -> String?,
    private val readAccessToken: () -> String?,
    private val ownerCheckpoint: () -> Unit,
    private val confirmFollow: (FollowStateChange) -> Unit,
    private val folderProtocol: DesktopOriginalFavoriteFolderProtocol,
    private val interactionCache: VideoInteractionStatusCache,
    private val followingGuard: FollowingSnapshotGuard,
    private val currentSession: () -> Any,
    private val interactionAdmission: ((() -> Unit) -> Unit),
) {
    private val UserActionRepository = DesktopOriginalUserActionRepository(api, readCsrf, readMid, ::assertOwned)
    private val WatchLaterRepository = DesktopOriginalVideoWatchLaterMutations(api, readCsrf, ::assertOwned)
    private fun assertOwned() {
        DesktopOriginalVideoEngagementPresentation.assertCurrent()
        ownerCheckpoint()
    }
    private fun <T> withInteractionAdmission(block: () -> T): T {
        var result: Result<T>? = null
        interactionAdmission { assertOwned(); result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
    fun interactionSession(): Any = withInteractionAdmission { currentSession() }
    suspend fun getFavoriteFolders(aid:Long?=null):Result<List<FavFolder>> = folderProtocol.getFavoriteFolders(aid)
    suspend fun updateFavoriteFolders(aid:Long,addFolderIds:Set<Long>,removeFolderIds:Set<Long>):Result<Boolean> = folderProtocol.updateFavoriteFolders(aid,addFolderIds,removeFolderIds)
"""
    protocol=header+cancellation_helper+'\n'+methods+'\n}\n'
    protocol_path='com/android/purebilibili/data/repository/DesktopOriginalVideoEngagementProtocol.kt'
    old=[r for r in rows if r['path']==protocol_path];assert len(old)==1
    existing=g['wide'](out/protocol_path);assert hashlib.sha256(existing.read_bytes()).hexdigest()==old[0]['sha256LF']
    g['write'](existing,protocol)
    old[0].update(origin='app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt',
      mode='fixed-v037-complete-original-interaction-protocol-existing-Root-epoch-admission',fixedCommit=COMMIT,
      sha256LF=hashlib.sha256(protocol.encode()).hexdigest(),physicalLines=len(protocol.splitlines()))
    proof=whole_proof(original_methods,methods)
    proof.update(upstreamCommit=COMMIT,originalDeclarations=names,admittedCacheCalls=admitted,
      allLikeFollowCoinTripleMutationBodiesRetained=True,cacheSharedByActualRepository=True,
      accountEpochAdmissionRequired=True,sourceQualified=False,mainCompiled=False)
    g['save'](out/'v037-original-interaction-protocol-proof.json',proof)
    cache=select(original,['VideoInteractionStatusCache','FollowingSnapshotGuard'])
    cache_text='package com.android.purebilibili.data.repository\n\nimport java.util.LinkedHashMap\n\n'+cache
    user_original=raw['UserActionRepository.kt'];user=user_original
    user=user.replace('import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.network.BilibiliApi')
    user=user.replace('import com.android.purebilibili.core.store.TokenManager\n','')
    user=user.replace('object UserActionRepository {',"""internal class DesktopOriginalUserActionRepository(
    private val api: BilibiliApi,
    private val readCsrf: () -> String?,
    private val readMid: () -> Long?,
    private val ownerCheckpoint: () -> Unit,
) {
    private fun assertOwned() = ownerCheckpoint()""")
    user=user.replace('NetworkModule.api.','api.').replace('TokenManager.csrfCache','readCsrf()').replace('TokenManager.midCache','readMid()')
    user=user.replace('= dataRequest {','= dataRequest {\n        assertOwned()')
    user=user.replace('        if (response.code != 0)','        assertOwned()\n        if (response.code != 0)')
    assert 'TokenManager' not in user and 'NetworkModule' not in user
    g['save'](out/'v037-original-user-action-transport-proof.json',whole_proof(user_original,user))
    watch_original=raw['WatchLaterRepository.kt']
    watch_interior=watch_original[watch_original.index('object WatchLaterRepository {')+len('object WatchLaterRepository {'):watch_original.rfind('}')]
    watch_methods=select(watch_interior,['requireCsrf','add','remove'])
    original_watch_methods=watch_methods
    watch_methods=watch_methods.replace('TokenManager.csrfCache','readCsrf()')
    watch_methods=watch_methods.replace('= dataRequest {','= dataRequest {\n        assertOwned()')
    watch_methods=watch_methods.replace('        if (response.code != 0)','        assertOwned()\n        if (response.code != 0)')
    watch_text="""package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
/** Three complete original write methods on the same owned API; no list/cache/store. */
internal class DesktopOriginalVideoWatchLaterMutations(
    private val api: BilibiliApi,
    private val readCsrf: () -> String?,
    private val assertOwned: () -> Unit,
) {
"""+watch_methods+'\n}\n'
    g['save'](out/'v037-original-watchlater-mutation-proof.json',whole_proof(original_watch_methods,watch_methods))
    for path,text,origin in [
      ('com/android/purebilibili/data/repository/DesktopOriginalVideoInteractionStatusCache.kt',cache_text,'app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt'),
      ('com/android/purebilibili/data/repository/DesktopOriginalUserActionRepository.kt',user,'core-data/src/main/java/com/android/purebilibili/data/repository/UserActionRepository.kt'),
      ('com/android/purebilibili/data/repository/DesktopOriginalVideoWatchLaterMutations.kt',watch_text,'core-data/src/main/java/com/android/purebilibili/data/repository/WatchLaterRepository.kt')]:
        assert not any(r['path']==path for r in rows)
        g['write'](out/path,text)
        rows.append(dict(path=path,origin=origin,mode='fixed-v037-complete-original-existing-Root-request-owner',fixedCommit=COMMIT,sha256LF=hashlib.sha256(text.encode()).hexdigest(),physicalLines=len(text.splitlines()),generated=True))
    fragment=g['wide'](out/'video-operations-members.fragment').read_text(encoding='utf-8')
    before=fragment
    anchor='    internal fun originalVideoEngagementActions(analytics:'
    assert fragment.count(anchor)==1
    creation="""    private fun createOriginalVideoEngagementProtocol(): com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol {
        val owner = repository.dynamicCacheSessionGuard.dynamicCacheOwner()
        return com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol(api,
            { assertOwned(); repository.requireCsrf() },
            { assertOwned(); repository.account.value?.mid },
            { assertOwned(); repository.authCookies()["SESSDATA"] },
            { assertOwned(); repository.accessTokenCredentials().first }, ::assertOwned,
            { change -> assertOwned(); repository.followStateEvents.confirm(checkNotNull(owner),change) },
            com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol(api,
                { assertOwned(); repository.account.value?.mid }, { assertOwned(); repository.requireCsrf() }, ::assertOwned),
            repository.videoInteractionStatusCache, repository.videoFollowingSnapshotGuard,
            { assertOwned(); expectedEpoch },
            { block -> repository.withPrimaryPlaybackAdmission(expectedEpoch, ::isOwned, block) })
    }
    private val originalEngagementProtocol by lazy { createOriginalVideoEngagementProtocol() }
    internal fun originalVideoInteractionStatus() = originalEngagementProtocol
"""
    fragment=fragment.replace(anchor,creation+anchor,1)
    start=fragment.index('        val owner = repository.dynamicCacheSessionGuard.dynamicCacheOwner()',fragment.index(anchor))
    end=fragment.index('        val original = ',start)
    fragment=fragment[:start]+'        val protocol = originalEngagementProtocol\n'+fragment[end:]
    assert fragment.count('DesktopOriginalVideoEngagementProtocol(api,')==1
    g['write'](out/'video-operations-members.fragment',fragment)
    g['save'](out/'v037-original-interaction-root-operations-proof.json',whole_proof(before,fragment))


def selected_source(path,canonical):
    names={
      'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt':'VideoEngagementViewModel.kt',
      'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoActionSection.kt':'VideoActionSection.kt',
      'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoInfoSection.kt':'VideoInfoSection.kt',
    }
    return fixed_sources()[names[path]] if path in names else canonical
