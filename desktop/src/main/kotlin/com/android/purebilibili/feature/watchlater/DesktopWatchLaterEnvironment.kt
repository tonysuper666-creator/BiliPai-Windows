package com.android.purebilibili.feature.watchlater

import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.data.repository.DesktopOriginalWatchLaterRepository
import kotlinx.coroutines.*

/** Same Root API/account binding and entry scope. No transport, account, items or cache
 * authority: the whole original repository/VM keeps those algorithms. */
class DesktopWatchLaterEnvironment(
    val favorites: DesktopFavoriteEnvironment,
    private val keys: suspend (forceRefresh: Boolean) -> Result<Pair<String, String>>,
    private val failureReporter: (Throwable) -> Unit,
) {
    val scope: CoroutineScope get() = favorites.scope
    val ioScope: CoroutineScope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
    val api get() = favorites.api
    val favorite get() = favorites.favorite
    val watchLater by lazy { DesktopOriginalWatchLaterRepository(this) }
    fun owns() = favorites.isOwned()
    fun assertOwned() = favorites.assertOwned()
    fun csrf() = favorites.csrf()
    fun currentMid() = favorites.currentMid()
    fun showFeedback(message: String) { if (owns()) favorites.showFeedback(message) }
    fun reportFailure(failure: Throwable) { if (owns()) failureReporter(failure) }
    suspend fun wbiKeys(forceRefresh: Boolean): Result<Pair<String,String>> {
        assertOwned()
        val result = keys(forceRefresh)
        assertOwned()
        return result
    }
}

/** Replaces AndroidViewModel's application-created scope with the retained actual entry. */
abstract class DesktopWatchLaterScopedOwner(protected val environment: DesktopWatchLaterEnvironment) {
    protected val viewModelScope: CoroutineScope get() = environment.scope
}
