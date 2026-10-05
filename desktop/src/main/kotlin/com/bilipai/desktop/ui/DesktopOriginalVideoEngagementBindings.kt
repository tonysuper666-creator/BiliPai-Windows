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
internal class DesktopOriginalVideoEngagementPresentation private constructor(
    private val owns: () -> Boolean,
    private val admission: (() -> Unit) -> Boolean,
    internal val feedbackSource: DesktopWindowsVideoFeedbackSource?,
    private val feedbackOwns: () -> Boolean,
    private val feedbackAdmission: (() -> Unit) -> Boolean,
) {
    private var brandEvents: com.android.purebilibili.core.events.BrandSuccessEvents? = null
    fun mountBrandFeedback(events: com.android.purebilibili.core.events.BrandSuccessEvents) {
        check(brandEvents == null || brandEvents === events); brandEvents = events
    }
    private val feedbackRetired = java.util.concurrent.atomic.AtomicBoolean(false)
    // Exact old two-lambda API, including trailing-lambda callers.
    constructor(owns: () -> Boolean, admission: (() -> Unit) -> Boolean) : this(owns, admission, null, owns, admission)
    constructor(sourceOwner: DesktopOriginalVideoAcceptedPublication,
        subject: com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot,
        owns: () -> Boolean, admission: (() -> Unit) -> Boolean) :
        this(owns, admission, DesktopWindowsVideoFeedbackSource(sourceOwner, subject), owns, admission)
    constructor(sourceOwner: DesktopOriginalVideoAcceptedPublication,
        subject: com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot,
        owns: () -> Boolean, admission: (() -> Unit) -> Boolean,
        feedbackOwns: () -> Boolean, feedbackAdmission: (() -> Unit) -> Boolean) :
        this(owns, admission, DesktopWindowsVideoFeedbackSource(sourceOwner, subject), feedbackOwns, feedbackAdmission)
    fun isOwned(): Boolean = owns()
    /** Exact source/account/entry lifetime. Temporary hide and an ephemeral
     * command window do not retire an admitted operation or confirmed receipt.
     * A real retirement cannot resurrect on a later ABA. */
    fun isFeedbackOwned(): Boolean {
        if (feedbackRetired.get()) return false
        if (!feedbackOwns()) { feedbackRetired.set(true); return false }
        return !feedbackRetired.get()
    }
    fun retireFeedbackIfInvalid() { isFeedbackOwned() }
    fun admitFeedback(action: () -> Unit): Boolean {
        if (!isFeedbackOwned()) return false
        var applied = false
        return feedbackAdmission {
            if (isFeedbackOwned()) { action(); applied = true }
        } && applied
    }
    fun admit(action: () -> Unit): Boolean {
        if (!isOwned()) return false
        var applied = false
        return admission {
            if (isOwned()) { action(); applied = true }
        } && applied
    }
    /** Capture a bounded start permit synchronously, before original launch.
     * Nested original work inherits its actual active caller; background UI can
     * never create a new admitted operation by borrowing this presentation. */
    val context: CoroutineContext get() {
        val parent = active.get()?.takeIf { it.presentation === this }
        val started = if (parent == null) admit {} else
            parent.started && parent.job?.isActive != false && isFeedbackOwned()
        return Element(this, started)
    }

    private class Element(val presentation: DesktopOriginalVideoEngagementPresentation, val started: Boolean) :
        ThreadContextElement<Active?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Element>
        override fun updateThreadContext(context: CoroutineContext): Active? = active.get().also {
            active.set(Active(presentation, context[Job], started))
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Active?) {
            if (oldState == null) active.remove() else active.set(oldState)
        }
    }
    private data class Active(val presentation: DesktopOriginalVideoEngagementPresentation, val job: Job?, val started: Boolean)
    companion object {
        private val active = ThreadLocal<Active?>()
        fun confirmBrandFollow(following: Boolean) {
            val request = active.get() ?: return
            val caller = request.job ?: return
            if (!request.started || !caller.isActive) return
            val presentation = request.presentation
            val events = presentation.brandEvents ?: return
            val origin = DesktopBrandSuccessOrigin(caller, presentation::isFeedbackOwned, presentation::admitFeedback)
            events.followChanged(origin, following)
        }
        fun capture(): DesktopOriginalVideoEngagementPresentation? = active.get()?.presentation
        fun currentIsOwned(): Boolean = active.get()?.let {
            it.started && it.job?.isActive != false && it.presentation.isFeedbackOwned()
        } ?: true
        fun assertCurrent() {
            if (!currentIsOwned()) throw CancellationException("Original engagement presentation retired")
        }
        /** Only bounded original state/event publication belongs inside this gate. */
        fun commitCurrent(action: () -> Unit) {
            assertCurrent()
            val captured = capture()
            if (captured == null) action()
            else if (!captured.admitFeedback { assertCurrent(); action() })
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
