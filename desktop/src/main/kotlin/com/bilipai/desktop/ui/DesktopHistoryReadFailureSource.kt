package com.bilipai.desktop.ui

import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.list.ListUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.data.DesktopHomeNavRequestReceipt
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Original repository arguments, before its unchanged cursor/type/keyword normalization. */
internal sealed interface DesktopHistoryReadParameters {
    data class List(val ps: Int, val max: Long, val viewAt: Long,
        val business: String?, val type: String?) : DesktopHistoryReadParameters
    data class Search(val page: Int, val keyword: String) : DesktopHistoryReadParameters
}

/** A borrowed actual History request. No scope, credential, transport or lifetime owner.
 * Admission is the existing primary Store monitor followed by the originating entry gate.
 * Normal caller completion may retain a displayed error; cancellation always retires it. */
internal class DesktopHistoryReadSource(
    val environment: DesktopFavoriteEnvironment,
    val route: BiliPaiNavKey,
    val caller: Job,
    val parameters: DesktopHistoryReadParameters,
    val primaryInstallation: DesktopHomeNavRequestReceipt,
    private val admission: (Boolean, () -> Unit) -> Boolean,
) {
    init { require(route == BiliPaiNavKey.History || route is BiliPaiNavKey.HistorySearch) }
    fun publish(block: () -> Unit): Boolean = admission(true, block)
    fun inspect(block: () -> Unit): Boolean = admission(false, block)
}

/** Per-invocation immutable context; never a latest-request map or ThreadLocal. */
internal class DesktopHistoryReadContext(val source: DesktopHistoryReadSource) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopHistoryReadContext>
}

internal class DesktopHistoryReadApiException(
    val code: Int,
    val source: DesktopHistoryReadSource?,
    message: String,
) : Exception(message)

/** Only a genuine nonzero API response supplies a code. Non-VM recap callers have no origin. */
internal suspend fun desktopHistoryReadApiFailure(environment: DesktopFavoriteEnvironment,
    code: Int, message: String, parameters: DesktopHistoryReadParameters): Exception {
    val source = currentCoroutineContext()[DesktopHistoryReadContext]?.source?.takeIf {
        it.environment === environment && it.parameters == parameters && it.caller.isActive
    }
    return DesktopHistoryReadApiException(code, source, message)
}

/** Actual original error slot; no UI data-model field or error-text identity. */
internal enum class DesktopHistoryReadFailureSlot { FIRST, LOAD_MORE }

/** Initial terminal UI object is evidence at binding; subsequent item-only copies preserve its slot. */
internal data class DesktopHistoryReadFailure(
    val code: Int,
    val message: String?,
    val source: DesktopHistoryReadSource,
    val searchGeneration: Long,
    val searchQuery: String,
    val displayedState: ListUiState,
    val slot: DesktopHistoryReadFailureSlot,
)

internal data class DesktopHistoryReadEpisode(
    val source: DesktopHistoryReadSource,
    val searchGeneration: Long,
    val searchQuery: String,
    val failure: DesktopHistoryReadFailure? = null,
)
