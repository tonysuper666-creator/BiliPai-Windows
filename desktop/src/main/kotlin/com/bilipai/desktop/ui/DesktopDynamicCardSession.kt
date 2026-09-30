package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.feature.dynamic.DynamicLikeRequestGate
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One page/credential owner for the original singleton catalog and like gate.
 * Cards keep their own cancellable tasks; disposing one card never retires this
 * shared catalog. Root closes the session when the containing page/epoch ends.
 */
internal class DesktopDynamicCardSession(
    private val repository: DesktopRepository,
    val expectedEpoch: Long = repository.sessionEpoch,
    private val stillOwned: () -> Boolean = { true },
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val operations = DesktopDynamicCardOperations(repository, expectedEpoch, ::isOwned)
    val emotes: DesktopDynamicEmotes = operations.emotes
    val likeGate = DynamicLikeRequestGate()
    private val confirmedLikes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val likeOverrides = confirmedLikes.asStateFlow()
    fun confirmLike(id: String, liked: Boolean) {
        if (isOwned()) confirmedLikes.update { it + (id to liked) }
    }
    fun isOwned(): Boolean = alive.get() && stillOwned() && repository.sessionEpoch == expectedEpoch
    fun matches(candidate: DesktopRepository, epoch: Long): Boolean =
        candidate === repository && epoch == expectedEpoch && isOwned()
    override fun close() { alive.set(false) }
}

internal val LocalDesktopDynamicCardSession = staticCompositionLocalOf<DesktopDynamicCardSession?> { null }

/** The shared gate must be released even if the card's job never starts. */
internal fun launchDesktopDynamicLike(
    gate: DynamicLikeRequestGate, scope: CoroutineScope, id: String,
    block: suspend CoroutineScope.() -> Unit,
): Job? {
    if (!gate.tryAcquire(id)) return null
    return scope.launch(block = block).also { job ->
        job.invokeOnCompletion { gate.release(id) }
    }
}
