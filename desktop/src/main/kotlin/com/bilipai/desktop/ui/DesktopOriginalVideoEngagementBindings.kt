package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoCoinBalanceLoader
import com.android.purebilibili.feature.video.viewmodel.VideoEngagementActions
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One detail entry/current subject plus Root's actual account epoch and caller scope. */
internal class DesktopOriginalVideoEngagementEnvironment(
    val context: DesktopPluginContext,
    val scope: CoroutineScope,
    val actions: VideoEngagementActions,
    val coinBalanceLoader: VideoCoinBalanceLoader,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
) {
    fun isOwned(): Boolean = isCurrent() && DesktopOriginalVideoEngagementPresentation.currentIsOwned()
    fun assertOwned() {
        if (!isOwned()) throw CancellationException("Original video engagement owner retired")
    }
    fun commit(block: () -> Unit) {
        assertOwned()
        DesktopOriginalVideoEngagementPresentation.commitCurrent {
            if (!commitIfCurrent { assertOwned(); block() })
                throw CancellationException("Original video engagement admission retired")
        }
    }
}

/** A captured presentation permission, never another subject/source authority.
 * The original domain still owns state, requests and its scope. The context
 * accompanies only the two explicitly dispatched command-card operations,
 * including their IO continuations and original nested feedback jobs.
 */
internal class DesktopOriginalVideoEngagementPresentation(
    private val owns: () -> Boolean,
    private val admission: (() -> Unit) -> Boolean,
) {
    fun isOwned(): Boolean = owns()
    fun admit(action: () -> Unit): Boolean {
        if (!isOwned()) return false
        var applied = false
        return admission {
            if (isOwned()) { action(); applied = true }
        } && applied
    }
    val context: CoroutineContext = Element(this)

    private class Element(val presentation: DesktopOriginalVideoEngagementPresentation) :
        ThreadContextElement<Active?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Element>
        override fun updateThreadContext(context: CoroutineContext): Active? = active.get().also {
            active.set(Active(presentation, context[Job]))
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Active?) {
            if (oldState == null) active.remove() else active.set(oldState)
        }
    }
    private data class Active(val presentation: DesktopOriginalVideoEngagementPresentation, val job: Job?)
    companion object {
        private val active = ThreadLocal<Active?>()
        fun capture(): DesktopOriginalVideoEngagementPresentation? = active.get()?.presentation
        fun currentIsOwned(): Boolean = active.get()?.let {
            it.job?.isActive != false && it.presentation.isOwned()
        } ?: true
        fun assertCurrent() {
            if (!currentIsOwned()) throw CancellationException("Original engagement presentation retired")
        }
        /** Only bounded original state/event publication belongs inside this gate. */
        fun commitCurrent(action: () -> Unit) {
            assertCurrent()
            val captured = capture()
            if (captured == null) action()
            else if (!captured.admit { assertCurrent(); action() })
                throw CancellationException("Original engagement presentation admission retired")
        }
    }
}

/** Root must supply its real analytics capability; no Firebase client is constructed here. */
internal interface DesktopOriginalVideoInteractionAnalytics {
    fun logLike(videoId: String, isLiked: Boolean)
    fun logDislike(videoId: String, isDisliked: Boolean)
    fun logFavorite(videoId: String, isFavorited: Boolean)
    fun logFollow(userId: String, isFollowed: Boolean)
    fun logCoin(videoId: String, coinCount: Int)
}

internal object DesktopOriginalVideoInteractionLog {
    fun d(tag: String, message: String) = java.util.logging.Logger.getLogger(tag).fine(message)
    fun e(tag: String, message: String, failure: Throwable? = null) {
        java.util.logging.Logger.getLogger(tag).log(java.util.logging.Level.FINE, message, failure)
    }
}
