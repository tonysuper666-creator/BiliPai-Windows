package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.data.model.response.FollowingUser
import com.android.purebilibili.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Original global local-first block semantics; authorized transport remains the shared repository. */
class DesktopBlockedUpRepository private constructor(private val repository: DesktopRepository,
    val store: DesktopBlockedUpStore, private val client: OkHttpClient,
    private val ensureSession: suspend () -> Unit, private val baseUrl: String,
    private val waitBetweenRequests: suspend (Long) -> Unit) {
    constructor(repository: DesktopRepository, store: DesktopBlockedUpStore) : this(repository, store,
        repository.httpClient, repository::ensureSession, "https://api.bilibili.com/", { delay(it) })

    val account get() = repository.account
    internal val sessionEpoch get() = repository.sessionEpoch
    private val mutationMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String,
        relationSource: BlockedUpRelationSource = BlockedUpRelationSource.PROFILE,
        expectedSessionEpoch: Long = repository.sessionEpoch,
        stillOwned: () -> Boolean = { true },
        commitLocal: ((() -> Unit) -> Boolean)? = null,
        ownedApi: BilibiliApi? = null,
        ownedCsrf: (() -> String?)? = null,
        ensureOwnedSession: (suspend () -> Unit)? = null): BlockedUpWriteResult = withContext(Dispatchers.IO) {
        require(mid > 0)
        mutationMutex.withLock {
            ensureEpoch(expectedSessionEpoch)
            currentCoroutineContext().ensureActive()
            if (!stillOwned()) throw CancellationException("Home block owner retired")
            val insert = { store.upsert(BlockedUp(mid = mid, name = name, face = face)) }
            if (commitLocal == null) insert()
            else if (!commitLocal(insert)) throw CancellationException("Home local block owner retired")
            sync(mid, true, relationSource, expectedSessionEpoch, stillOwned, ownedApi, ownedCsrf, ensureOwnedSession)
        }
    }

    suspend fun unblockUpWithBilibiliSync(mid: Long,
        relationSource: BlockedUpRelationSource = BlockedUpRelationSource.PROFILE,
        expectedSessionEpoch: Long = repository.sessionEpoch): BlockedUpWriteResult = withContext(Dispatchers.IO) {
        require(mid > 0)
        mutationMutex.withLock {
            ensureEpoch(expectedSessionEpoch)
            currentCoroutineContext().ensureActive()
            store.remove(mid)
            sync(mid, false, relationSource, expectedSessionEpoch)
        }
    }

    /** Exact original local-first relation operation with captured IO publication.
     * Local callback performs its own same-Store CAS outside Root admission. */
    internal suspend fun blockUpWithCapturedLocalWrite(mid: Long, name: String, face: String,
        relationSource: BlockedUpRelationSource, expectedSessionEpoch: Long,
        assertRequest: () -> Unit, writeLocal: (BlockedUp) -> Unit,
        ownedApi: BilibiliApi, ownedCsrf: () -> String?, ensureOwnedSession: suspend () -> Unit): BlockedUpWriteResult =
        withContext(Dispatchers.IO) {
            require(mid > 0)
            mutationMutex.withLock {
                ensureEpoch(expectedSessionEpoch); currentCoroutineContext().ensureActive(); assertRequest()
                writeLocal(BlockedUp(mid = mid, name = name, face = face))
                sync(mid, true, relationSource, expectedSessionEpoch,
                    { assertRequest(); true }, ownedApi, ownedCsrf, ensureOwnedSession)
            }
        }
    internal suspend fun unblockUpWithCapturedLocalWrite(mid: Long,
        relationSource: BlockedUpRelationSource, expectedSessionEpoch: Long,
        assertRequest: () -> Unit, writeLocal: (Long) -> Unit,
        ownedApi: BilibiliApi, ownedCsrf: () -> String?, ensureOwnedSession: suspend () -> Unit): BlockedUpWriteResult =
        withContext(Dispatchers.IO) {
            require(mid > 0)
            mutationMutex.withLock {
                ensureEpoch(expectedSessionEpoch); currentCoroutineContext().ensureActive(); assertRequest()
                writeLocal(mid)
                sync(mid, false, relationSource, expectedSessionEpoch,
                    { assertRequest(); true }, ownedApi, ownedCsrf, ensureOwnedSession)
            }
        }

    suspend fun importBlockedUps(text: String): BlockedUpImportResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        store.import(parseBlockedUpShareText(text))
    }

    /** Explicit pull/import only. The original page size, bound, pacing and mapper are reused. */
    suspend fun importFromBilibili(expectedSessionEpoch: Long = repository.sessionEpoch): Result<BlockedUpImportResult> =
        withContext(Dispatchers.IO) {
            try {
                ensureEpoch(expectedSessionEpoch)
                if (repository.authCookies()["SESSDATA"].isNullOrEmpty() || repository.account.value == null)
                    return@withContext Result.failure(BiliApiException(-101, "请先登录后再同步 B站黑名单"))
                try { store.migrateLegacyDiscoveryMids().getOrThrow() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { throw IllegalStateException("本地黑名单资料暂不可用，原记录保持不变，请修复后重试", error) }
                ensureSession(); ensureEpoch(expectedSessionEpoch)
                val api = api(expectedSessionEpoch)
                val remote = mutableListOf<FollowingUser>()
                var page = 1
                while (page <= originalBlockedListMaxPages()) {
                    ensureEpoch(expectedSessionEpoch)
                    val response = api.getRelationBlacks(pageSize = originalBlockedListPageSize(), page = page)
                    ensureEpoch(expectedSessionEpoch)
                    if (response.code != 0) throw BiliApiException(response.code,
                        response.message.ifBlank { "获取 B站黑名单成员失败: ${response.code}" })
                    val users = response.data.list
                    remote.addAll(users)
                    if (users.size < originalBlockedListPageSize()) break
                    if (response.data.total > 0 && remote.size >= response.data.total) break
                    page++
                    waitBetweenRequests(originalBlockedListPageDelay())
                }
                currentCoroutineContext().ensureActive(); ensureEpoch(expectedSessionEpoch)
                val imported = try { store.import(buildBlockedUpImportItemsFromRemoteBlacks(remote)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { throw IllegalStateException("本地黑名单未能保存，现有记录保持不变", error) }
                val refreshed = refreshBlockedUpProfiles(expectedSessionEpoch)
                Result.success(imported.copy(message = "${imported.message}；${refreshed.message}"))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { Result.failure(safeReadError(error)) }
        }

    suspend fun refreshBlockedUpProfiles(expectedSessionEpoch: Long = repository.sessionEpoch): BlockedUpMetadataRefreshResult =
        withContext(Dispatchers.IO) {
            ensureEpoch(expectedSessionEpoch)
            store.migrateLegacyDiscoveryMids().getOrThrow()
            val records = store.records.value
            var updated = 0; var deleted = 0; var failed = 0
            val now = System.currentTimeMillis()
            if (records.isNotEmpty()) { ensureSession(); ensureEpoch(expectedSessionEpoch) }
            val api = api(expectedSessionEpoch)
            records.forEachIndexed { index, up ->
                ensureEpoch(expectedSessionEpoch)
                val response = try { api.getUserCard(mid = up.mid, photo = true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { ensureEpoch(expectedSessionEpoch); null }
                currentCoroutineContext().ensureActive(); ensureEpoch(expectedSessionEpoch)
                val card = response?.data?.card
                val replacement = if (response?.code == 0 && card != null) up.copy(
                    name = card.name.ifBlank { up.name }, face = card.face.ifBlank { up.face }, sign = card.sign,
                    level = card.level_info?.current_level, vipLabel = card.vip?.label?.text.orEmpty(),
                    officialTitle = card.Official?.title.orEmpty(), follower = response.data.follower.toLong(),
                    archiveCount = response.data.archive_count, isDeleted = false, lastSyncedAt = now)
                else if (response != null) up.copy(isDeleted = true, lastSyncedAt = now) else null
                if (replacement == null) failed++
                else if (store.replaceProfileIfUnchanged(up, replacement)) {
                    if (replacement.isDeleted) deleted++ else updated++
                }
                // Removed/replaced rows stay untouched; their outdated response is not counted as a refresh.
                if (index != records.lastIndex) waitBetweenRequests(originalBlockedProfileRefreshDelay())
            }
            BlockedUpMetadataRefreshResult(updated, deleted, failed, buildBlockedUpMetadataRefreshMessage(updated, deleted, failed))
        }

    private suspend fun sync(mid: Long, blocked: Boolean, source: BlockedUpRelationSource, epoch: Long,
        stillOwned: () -> Boolean = { true }, ownedApi: BilibiliApi? = null,
        ownedCsrf: (() -> String?)? = null, ensureOwnedSession: (suspend () -> Unit)? = null): BlockedUpWriteResult {
        ensureEpoch(epoch)
        if (!stillOwned()) throw CancellationException("Home block owner retired")
        val csrf = if (ownedCsrf != null) ownedCsrf() else try { repository.requireCsrf() } catch (_: BiliApiException) { null }
        if (csrf.isNullOrBlank()) return result(blocked, BilibiliBlockedListRemoteStatus.SKIPPED_NOT_LOGGED_IN)
        return try {
            if (ensureOwnedSession != null) ensureOwnedSession() else ensureSession()
            ensureEpoch(epoch)
            if (!stillOwned()) throw CancellationException("Home block owner retired")
            val (act, reSrc) = desktopBlockedRelationArguments(blocked, source)
            val response = (ownedApi ?: api(epoch)).modifyRelation(mid, act, csrf, reSrc)
            currentCoroutineContext().ensureActive(); ensureEpoch(epoch)
            if (!stillOwned()) throw CancellationException("Home block owner retired")
            if (response.code == 0) result(blocked, BilibiliBlockedListRemoteStatus.SUCCESS)
            else result(blocked, BilibiliBlockedListRemoteStatus.FAILED, response.message.ifBlank { "接口返回 ${response.code}" })
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            ensureEpoch(epoch)
            if (!stillOwned()) throw CancellationException("Home block owner retired")
            result(blocked, BilibiliBlockedListRemoteStatus.FAILED, safeReadError(error).message)
        }
    }

    private fun result(blocked: Boolean, status: BilibiliBlockedListRemoteStatus, message: String? = null) =
        BlockedUpWriteResult(true, status, buildBlockedUpWriteMessage(blocked, status, message))

    private fun api(epoch: Long): BilibiliApi {
        val guarded = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                ensureEpoch(epoch)
                val request = chain.request()
                val body = request.body
                val once = if (request.method == "POST" && body != null)
                    request.newBuilder().method("POST", object : RequestBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun isOneShot() = true
                        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                    }).build() else request
                val response = chain.proceed(once)
                try { ensureEpoch(epoch) } catch (error: Exception) { response.close(); throw error }
                response
            }.build()
        return Retrofit.Builder().baseUrl(baseUrl).client(guarded)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)
    }

    private fun ensureEpoch(epoch: Long) {
        if (repository.sessionEpoch != epoch) throw BiliApiException(-101, "账号已变化，请重新操作")
    }
    private fun safeReadError(error: Exception): Exception = when (error) {
        is BiliApiException -> error
        is retrofit2.HttpException -> BiliApiException(error.code(), "服务器返回 HTTP ${error.code()}")
        is IllegalStateException -> if (error.message == "本地黑名单未能保存，现有记录保持不变" ||
            error.message == "本地黑名单资料暂不可用，原记录保持不变，请修复后重试") error
            else IllegalStateException("网络连接失败，请稍后重试")
        else -> IllegalStateException("网络连接失败，请稍后重试")
    }
    internal companion object {
        fun forTests(repository: DesktopRepository, store: DesktopBlockedUpStore, client: OkHttpClient,
            ensureSession: suspend () -> Unit = {}, baseUrl: String = "https://api.bilibili.com/",
            waitBetweenRequests: suspend (Long) -> Unit = { delay(it) }) =
            DesktopBlockedUpRepository(repository, store, client, ensureSession, baseUrl, waitBetweenRequests)
    }
}
