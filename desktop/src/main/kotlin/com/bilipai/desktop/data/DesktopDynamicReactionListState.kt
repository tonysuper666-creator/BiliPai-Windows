package com.bilipai.desktop.data

import com.android.purebilibili.core.network.grpc.DynamicReactionPage
import com.android.purebilibili.core.network.grpc.DynamicReactionUser
import com.android.purebilibili.data.model.response.DynamicItem
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** State of the complete original ReactionSheet load policy. Server action text
 * and positive MID are retained; this is not a new dynamic feed or account cache. */
internal data class DesktopDynamicReactionListSnapshot(
    val users: List<DynamicReactionUser> = emptyList(),
    val offset: String = "",
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val title: String = "赞和转发",
)

/** One immutable dynamic item and the caller's actual retained page/account gate.
 * The caller must close this lifetime when its source or sheet is disposed.
 * Network work is outside admission. No process-global client, token or scope.
 */
internal class DesktopDynamicReactionListState internal constructor(
    val item: DynamicItem,
    private val fetchPage: suspend (DynamicItem, String) -> DynamicReactionPage,
    private val stillOwned: () -> Boolean,
    private val commitIfCurrent: (() -> Unit) -> Boolean,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(DesktopDynamicReactionListSnapshot())
    val state = mutableState.asStateFlow()
    private var requestTicket: Any? = null
    private var requestJob: Job? = null

    fun isOwned(): Boolean = alive.get() && stillOwned()

    private fun publish(caller: Job? = null, action: () -> Unit): Boolean {
        var applied = false
        val admitted = commitIfCurrent {
            synchronized(stateLock) {
                if (isOwned() && caller?.isActive != false) { action(); applied = true }
            }
        }
        return admitted && applied
    }

    /** Initial request, pagination and explicit error retry use the same cursor.
     * Cancellation of this request cancels the existing asynchronous OkHttp call. */
    suspend fun load(): Unit = coroutineScope {
        currentCoroutineContext().ensureActive()
        val caller = checkNotNull(currentCoroutineContext()[Job])
        val ticket = Any()
        var cursor = ""
        var started = false
        if (!publish(caller) {
                val before = mutableState.value
                if (!before.loading && before.hasMore) {
                    cursor = before.offset
                    requestTicket = ticket
                    requestJob = caller
                    mutableState.value = before.copy(loading = true, error = null)
                    started = true
                }
            } || !started) return@coroutineScope
        try {
            val requestSource = DesktopDynamicGrpcRequestSource(
                stillOwned = {
                    isOwned() && caller.isActive && synchronized(stateLock) { requestTicket === ticket }
                },
                admission = { action ->
                    var performed = false
                    val admitted = publish(caller) {
                        if (requestTicket === ticket) { action(); performed = true }
                    }
                    admitted && performed
                },
            )
            val page = withContext(requestSource) { fetchPage(item, cursor) }
            currentCoroutineContext().ensureActive()
            if (!publish(caller) {
                    if (requestTicket === ticket) {
                        val before = mutableState.value
                        mutableState.value = before.copy(
                            users = (before.users + page.users).distinctBy { it.mid to it.action },
                            hasMore = page.hasMore && page.offset.isNotBlank() && page.offset != cursor,
                            offset = page.offset,
                            loaded = true,
                            title = page.title.takeIf { it.isNotBlank() } ?: before.title,
                        )
                    }
                }) throw CancellationException("Dynamic reaction source retired")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val message = reactionError(failure)
            if (!publish(caller) {
                    if (requestTicket === ticket)
                        mutableState.value = mutableState.value.copy(error = message)
                }) throw CancellationException("Dynamic reaction source retired")
        } finally {
            // A cancelled request releases its own loading flag only while the
            // same page is still admitted; a successor request never gets cleared.
            publish {
                if (requestTicket === ticket) {
                    requestTicket = null
                    requestJob = null
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
    }

    /** Original four-row threshold. UI observes cursor as well as this decision
     * so a short page can load again without requiring another scroll event. */
    fun shouldLoadMore(lastVisibleIndex: Int, totalItemsCount: Int): Boolean {
        if (!isOwned()) return false
        val snapshot = state.value
        val nearEnd = totalItemsCount > 0 &&
            lastVisibleIndex >= (totalItemsCount - 4).coerceAtLeast(0)
        return snapshot.loaded && snapshot.hasMore && !snapshot.loading &&
            snapshot.error == null && nearEnd
    }

    suspend fun retry() = load()

    override fun close() {
        val pending = synchronized(stateLock) {
            if (!alive.getAndSet(false)) return
            requestJob.also { requestJob = null; requestTicket = null }
        }
        // Cancellation can run OkHttp/coroutine handlers and must stay outside
        // Store/page/state monitors. Only this load's coroutineScope is cancelled.
        pending?.cancel()
    }

    private fun reactionError(failure: Exception): String {
        val message = failure.message ?: "加载失败"
        return when {
            "-101" in message -> "此接口需要 App 登录凭据，请检查当前登录方式。\n$message"
            "-352" in message -> "请求被风控拦截，可稍后重试。\n$message"
            "-403" in message -> "服务器拒绝访问该列表。\n$message"
            else -> message
        }
    }
}
