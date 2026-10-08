package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.repository.DesktopOriginalCapturedVideoSearch
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistDomain
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema as Schema
import com.android.purebilibili.data.repository.SearchRepository
import com.bilipai.desktop.audio.DesktopAudioRepository
import com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** All entry points borrow Root's existing account, Store and public audio client.
 * A matching batch and its nested checkpoint saves retain one captured request.
 * Capture occurs in the real caller Job, before any original network operation.
 */
internal interface DesktopOriginalExternalPlaylistRequest {
    val primaryApi: com.android.purebilibili.core.network.BilibiliApi
    val primarySearchApi: com.android.purebilibili.core.network.SearchApi
    fun assertCurrent()
    fun admitCurrentMutation(action: () -> Unit): Boolean
}

internal class DesktopOriginalExternalPlaylistBinding(
    private val context: DesktopOriginalPlayerSettingsContext,
    private val audioRepository: DesktopAudioRepository,
    private val captureRequest: suspend () -> DesktopOriginalExternalPlaylistRequest,
) : DesktopOriginalExternalPlaylistActions {
    private val retainedRequest = ThreadLocal<DesktopOriginalExternalPlaylistRequest?>()

    private suspend fun <T> request(block: suspend (DesktopOriginalExternalPlaylistDomain) -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        context.requireCurrent()
        DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
        val retained = retainedRequest.get()
        val binding = retained ?: captureRequest()
        val callerJob = requireNotNull(caller[Job])
        fun checkRequest() {
            caller.ensureActive()
            context.requireCurrent()
            binding.assertCurrent()
        }
        checkRequest()
        val search = DesktopOriginalCapturedVideoSearch(binding.primarySearchApi, binding.primaryApi, ::checkRequest)
        val domain = DesktopOriginalExternalPlaylistDomain(
            DesktopOriginalExternalPlaylistHttp(audioRepository.externalPlaylistCalls, callerJob, ::checkRequest), search)
        if (retained != null) return block(domain).also { checkRequest() }
        return withContext(retainedRequest.asContextElement(binding)) {
            DesktopSubscriptionWriteAdmission.withOwned(
                stillOwned = { try { checkRequest(); true } catch (_: CancellationException) { false } },
                commitIfCurrent = binding::admitCurrentMutation,
            ) { block(domain).also { checkRequest() } }
        }
    }

    private fun requireContext(supplied: DesktopOriginalPlayerSettingsContext) {
        require(supplied === context) { "Playlist import requires the same Root settings context" }
    }

    override suspend fun loadImportCheckpoint(context: DesktopOriginalPlayerSettingsContext): Schema.ImportCheckpoint? {
        requireContext(context)
        return request { it.loadImportCheckpoint(context) }
    }

    override suspend fun saveImportCheckpoint(context: DesktopOriginalPlayerSettingsContext, checkpoint: Schema.ImportCheckpoint) {
        requireContext(context)
        request { it.saveImportCheckpoint(context, checkpoint) }
    }

    override suspend fun clearImportCheckpoint(context: DesktopOriginalPlayerSettingsContext) {
        requireContext(context)
        request { it.clearImportCheckpoint(context) }
    }

    override suspend fun fetchPlaylist(source: Schema.Source, id: String): Result<Schema.ExternalPlaylistMeta> =
        request { it.fetchPlaylist(source, id) }

    override suspend fun matchTracks(tracks: List<Schema.ExternalTrack>, startIndex: Int,
        onProgress: suspend (completed: Int, total: Int, outcome: Schema.MatchOutcome) -> Unit): List<Schema.MatchOutcome> =
        request { domain ->
            val caller = currentCoroutineContext()
            domain.matchTracks(tracks, startIndex) { completed, total, outcome ->
                caller.ensureActive()
                DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
                onProgress(completed, total, outcome)
                caller.ensureActive()
                DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
            }
        }

    override suspend fun search(keyword: String): Result<Pair<List<VideoItem>, SearchRepository.SearchPageInfo>> =
        request { domain -> domain.searchVideo(keyword) }
}

/** The original repository consumes the response inside this lifetime. */
internal interface DesktopOriginalExternalPlaylistResponsePort {
    fun <T> withResponse(request: Request, block: (Response) -> T): T
}

/** Stateless borrowing adapter; no client, account cache, queue or Store is created.
 * Cancellation remains attached until body parsing and response closure finish.
 */
@OptIn(InternalCoroutinesApi::class)
internal class DesktopOriginalExternalPlaylistHttp(
    private val calls: Call.Factory,
    private val requestJob: Job,
    private val checkCurrent: () -> Unit,
) : DesktopOriginalExternalPlaylistResponsePort {
    private fun checkpoint() { requestJob.ensureActive(); checkCurrent() }

    override fun <T> withResponse(request: Request, block: (Response) -> T): T {
        checkpoint()
        val call = calls.newCall(request)
        val cancellation = requestJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (requestJob.isCancelled) call.cancel()
        }
        try {
            checkpoint()
            return call.execute().use { response ->
                checkpoint()
                val owned = response.newBuilder().body(CheckedPlaylistBody(response.body, ::checkpoint)).build()
                block(owned).also { checkpoint() }
            }
        } catch (failure: Exception) {
            checkpoint()
            throw failure
        } finally {
            cancellation.dispose()
        }
    }
}

private class CheckedPlaylistBody(private val borrowed: ResponseBody, check: () -> Unit) : ResponseBody() {
    private val checked: BufferedSource = object : ForwardingSource(borrowed.source()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            check()
            val count = super.read(sink, byteCount)
            check()
            return count
        }
    }.buffer()
    override fun contentType() = borrowed.contentType()
    override fun contentLength() = borrowed.contentLength()
    override fun source(): BufferedSource = checked
}
