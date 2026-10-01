from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
rel='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBlockedUpRepository.kt'
base=(REPO/rel).read_text(encoding='utf-8');s=base
def replace(old,new):
 global s
 assert s.count(old)==1,old;s=s.replace(old,new,1)
replace('''    suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String,
        relationSource: BlockedUpRelationSource = BlockedUpRelationSource.PROFILE,
        expectedSessionEpoch: Long = repository.sessionEpoch): BlockedUpWriteResult = withContext(Dispatchers.IO) {
        require(mid > 0)''',
 '''    suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String,
        relationSource: BlockedUpRelationSource = BlockedUpRelationSource.PROFILE,
        expectedSessionEpoch: Long = repository.sessionEpoch,
        stillOwned: () -> Boolean = { true },
        commitLocal: ((() -> Unit) -> Boolean)? = null,
        ownedApi: BilibiliApi? = null,
        ownedCsrf: (() -> String?)? = null,
        ensureOwnedSession: (suspend () -> Unit)? = null): BlockedUpWriteResult = withContext(Dispatchers.IO) {
        require(mid > 0)''')
replace('''            currentCoroutineContext().ensureActive()
            store.upsert(BlockedUp(mid = mid, name = name, face = face))
            sync(mid, true, relationSource, expectedSessionEpoch)''',
'''            currentCoroutineContext().ensureActive()
            if (!stillOwned()) throw CancellationException("Home block owner retired")
            val insert = { store.upsert(BlockedUp(mid = mid, name = name, face = face)) }
            if (commitLocal == null) insert()
            else if (!commitLocal(insert)) throw CancellationException("Home local block owner retired")
            sync(mid, true, relationSource, expectedSessionEpoch, stillOwned, ownedApi, ownedCsrf, ensureOwnedSession)''')
replace('''    private suspend fun sync(mid: Long, blocked: Boolean, source: BlockedUpRelationSource, epoch: Long): BlockedUpWriteResult {
        ensureEpoch(epoch)
        val csrf = try { repository.requireCsrf() } catch (_: BiliApiException) { null }''',
'''    private suspend fun sync(mid: Long, blocked: Boolean, source: BlockedUpRelationSource, epoch: Long,
        stillOwned: () -> Boolean = { true }, ownedApi: BilibiliApi? = null,
        ownedCsrf: (() -> String?)? = null, ensureOwnedSession: (suspend () -> Unit)? = null): BlockedUpWriteResult {
        ensureEpoch(epoch)
        if (!stillOwned()) throw CancellationException("Home block owner retired")
        val csrf = if (ownedCsrf != null) ownedCsrf() else try { repository.requireCsrf() } catch (_: BiliApiException) { null }''')
replace('''            ensureSession(); ensureEpoch(epoch)
            val (act, reSrc) = desktopBlockedRelationArguments(blocked, source)
            val response = api(epoch).modifyRelation(mid, act, csrf, reSrc)
            currentCoroutineContext().ensureActive(); ensureEpoch(epoch)''',
'''            if (ensureOwnedSession != null) ensureOwnedSession() else ensureSession()
            ensureEpoch(epoch)
            if (!stillOwned()) throw CancellationException("Home block owner retired")
            val (act, reSrc) = desktopBlockedRelationArguments(blocked, source)
            val response = (ownedApi ?: api(epoch)).modifyRelation(mid, act, csrf, reSrc)
            currentCoroutineContext().ensureActive(); ensureEpoch(epoch)
            if (!stillOwned()) throw CancellationException("Home block owner retired")''')
replace('''        } catch (error: Exception) {
            ensureEpoch(epoch)
            result(blocked, BilibiliBlockedListRemoteStatus.FAILED, safeReadError(error).message)''',
'''        } catch (error: Exception) {
            ensureEpoch(epoch)
            if (!stillOwned()) throw CancellationException("Home block owner retired")
            result(blocked, BilibiliBlockedListRemoteStatus.FAILED, safeReadError(error).message)''')
out=HERE/'prepared/blocked-delta'/rel;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(s,encoding='utf-8',newline='\n')
(HERE/'home-blocked-owner.patch').write_text(''.join(difflib.unified_diff(base.splitlines(True),s.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel)),encoding='utf-8',newline='\n')
(HERE/'blocked-delta-pins.json').write_text(json.dumps({'path':rel,'baseLF':hashlib.sha256(base.encode()).hexdigest(),'desiredLF':hashlib.sha256(s.encode()).hexdigest(),'scope':'optional owner-bound admission only; same local-first algorithm/default PROFILE/remote result/error mapping'},indent=2)+'\n',encoding='utf-8')
