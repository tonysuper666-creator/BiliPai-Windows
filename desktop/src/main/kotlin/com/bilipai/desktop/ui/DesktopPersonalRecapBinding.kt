package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.repository.DesktopOriginalHistoryRepository
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicReference

/** A read/request projection of one real retained History entry, never an account,
 * source-list, repository or preferences authority. The original VM owns its cache. */
internal class DesktopPersonalRecapBinding(
    val context: DesktopPluginContext,
    private val environment: DesktopFavoriteEnvironment,
    preferences: DesktopFavoritePreferences,
    private val ownsEntry: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) {
    init { require(context.store === preferences.store) { "Recap must read Root's existing Store" } }
    val enabled = preferences.personalRecapEnabled
    val history: DesktopOriginalHistoryRepository get() { checkOwner(); return environment.history }
    private val current = AtomicReference<Request?>()
    private fun owned() = ownsEntry() && environment.isOwned()
    private fun checkOwner() { if (!owned()) throw CancellationException("History recap entry retired") }

    suspend fun beginRequest(): Request {
        currentCoroutineContext().ensureActive()
        checkOwner()
        val caller = checkNotNull(currentCoroutineContext()[Job]) { "Recap requires its actual caller Job" }
        val request = Request(caller)
        current.set(request)
        try { request.check(); return request }
        catch (failure: Throwable) { current.compareAndSet(request, null); throw failure }
    }

    /** Only operation identity; all admission still comes from the actual Root entry. */
    inner class Request internal constructor(private val caller: Job) {
        fun check() {
            caller.ensureActive()
            checkOwner()
            if (current.get() !== this) throw CancellationException("History recap request replaced")
        }
        suspend fun <T> read(block: suspend () -> T): T {
            check()
            return block().also { check() }
        }
        fun publish(action: () -> Unit) {
            check()
            var entered = false
            val accepted = admit { check(); action(); entered = true }
            if (!accepted || !entered) throw CancellationException("History recap publication retired")
        }
        /** Cancellation can clear this request's loading while the same entry lives;
         * an old finally never clears a newer request's busy state or retained data. */
        fun cleanup(action: () -> Unit) {
            if (!owned() || current.get() !== this) return
            admit { if (owned() && current.get() === this) action() }
        }
        fun finish() { current.compareAndSet(this, null) }
    }
}

internal val LocalDesktopPersonalRecapBindings = staticCompositionLocalOf<DesktopPersonalRecapBinding?> { null }

@Composable internal fun requireDesktopPersonalRecapBinding(): DesktopPersonalRecapBinding =
    checkNotNull(LocalDesktopPersonalRecapBindings.current) { "Original History recap requires its current retained Root entry" }
