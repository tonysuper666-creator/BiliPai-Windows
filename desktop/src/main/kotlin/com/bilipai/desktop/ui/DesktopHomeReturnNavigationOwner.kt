package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Offset
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.core.ui.transition.VideoCardTransitionExposure
import com.android.purebilibili.core.util.CardPositionManager
import com.android.purebilibili.navigation.isVideoCardReturnTargetRoute
import com.android.purebilibili.navigation.isVideoDetailRoute
import com.android.purebilibili.navigation.resolveVideoCardSourceRouteForNavigation
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One original return/source session per retained Home entry. No route stack, feed, account or
 * geometry store is added. Root supplies its actual current key/ancestor state and checkpoint
 * admission. A video covering Home retains this owner; account/entry/restore/window retirement
 * closes it before the original owner and requests are destroyed.
 *
 * Navigation checkpoint runs before SessionStore/entry admission; then this projection. close never obtains
 * the Store. Root navigation admission must invoke the supplied block exactly once iff accepted;
 * it must not invoke the block then return false. Its checkpoint occurs before invocation. */
internal class DesktopHomeReturnNavigationOwner(
    private val stillOwned: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val admitRootNavigation: ((() -> Unit) -> Boolean),
    private val hostOriginInRoot: () -> Offset,
    private val monotonicMillis: () -> Long,
    private val clock: VideoCardTransitionClock,
    private val sharedCardTransitionEnabled: () -> Boolean,
    private val relatedCardTransitionEnabled: () -> Boolean,
    private val reduceMotion: () -> Boolean,
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var closed = false
    private val mutableSession = MutableStateFlow(BiliPaiReturnSessionState())
    val session: StateFlow<BiliPaiReturnSessionState> = mutableSession.asStateFlow()
    private var relatedRestorePending = false
    private var relatedTransitionObserved = false

    private fun owns(): Boolean = !closed && stillOwned()
    private fun mutate(stillOwned: (() -> Boolean)? = null,
        sourceAdmission: (((() -> Unit) -> Boolean))? = null, block: () -> Unit): Boolean {
        if (!owns() || stillOwned?.invoke() == false) return false
        var accepted = false
        commitIfCurrent {
            val publish = {
                synchronized(lock) {
                    if (owns() && stillOwned?.invoke() != false) { block(); accepted = true }
                }
                Unit
            }
            // Store -> Home entry -> borrowed video entry -> Return lock.
            // Source admission never encloses the outer navigation checkpoint.
            if (sourceAdmission == null) publish() else sourceAdmission(publish)
        }
        return accepted
    }

    /** Call after original Story/offline/vertical dispatch has resolved to a genuine VideoDetail.
     * currentKey and hasVideoDetailAncestor come from Root's real navigation state, not defaults.
     * performNavigation uses the original full VideoRoute/intent, preserving CID/resume/source.
     * This call captures CardPositionManager before destination composition or async playback. */
    fun enterVideo(
        bvid: String,
        explicitSourceRoute: String?,
        coverIdentity: String?,
        currentKey: BiliPaiNavKey?,
        hasVideoDetailAncestor: Boolean,
        visibleBottomBarRoutes: Set<String>,
        performNavigation: (BiliPaiVideoSource, VideoCardTransitionSession) -> Unit,
    ): Boolean =
        enterVideoFromSource(bvid, explicitSourceRoute, coverIdentity, currentKey, hasVideoDetailAncestor,
            visibleBottomBarRoutes, null, null, performNavigation)

    /** Same complete transition/return mutation with a source-aware final admission.
     * The original navigation checkpoint precedes mutate and all source monitors. */
    internal fun enterVideoFromSource(
        bvid: String,
        explicitSourceRoute: String?,
        coverIdentity: String?,
        currentKey: BiliPaiNavKey?,
        hasVideoDetailAncestor: Boolean,
        visibleBottomBarRoutes: Set<String>,
        stillOwned: (() -> Boolean)?,
        sourceAdmission: (((() -> Unit) -> Boolean))?,
        performNavigation: (BiliPaiVideoSource, VideoCardTransitionSession) -> Unit,
    ): Boolean {
        if (!owns() || stillOwned?.invoke() == false || bvid.isBlank()) return false
        var navigated = false
        admitRootNavigation {
            mutate(stillOwned, sourceAdmission) {
                if (!owns()) return@mutate
                val matchedVisibleCardRoute = resolveVideoCardSourceRouteForNavigation(
                    currentRoute = currentKey?.toLegacyRoute(), videoBvid = bvid,
                    lastClickedVideoSourceKey = CardPositionManager.lastClickedVideoSourceKey,
                    visibleBottomBarRoutes = visibleBottomBarRoutes,
                )
                val source = resolveBiliPaiVideoSource(bvid,
                    explicitSourceRoute ?: matchedVisibleCardRoute, currentKey,
                    mutableSession.value.lastVideoSourceRoute)
                val captured = desktopOriginalHomeTransitionSession(bvid, source, coverIdentity,
                    hostOriginInRoot())
                mutableSession.value = mutableSession.value.recordTransitionSession(
                    captured, preserveCurrentSession = hasVideoDetailAncestor)
                    .markDetailEntered(monotonicMillis())
                relatedRestorePending = false
                relatedTransitionObserved = false
                desktopOriginalHomePrearmOpening(captured, sharedCardTransitionEnabled(),
                    relatedCardTransitionEnabled(), reduceMotion(), clock)
                performNavigation(source, captured)
                navigated = true
            }
        }
        return navigated
    }

    /** Real accepted back action only. Full original target-route policy decides which returns
     * affect Home/Category flags; it is not equivalent to any showVideo=false transition.
     * Root's playback-leave/mini/audio ownership action belongs in performBack before pop. */
    fun returnFromVideo(
        currentKey: BiliPaiNavKey,
        targetKey: BiliPaiNavKey?,
        isRelatedDetailPop: Boolean,
        performBack: () -> Unit,
    ): Boolean {
        if (!owns()) return false
        var returned = false
        admitRootNavigation {
            mutate {
                if (!owns()) return@mutate
                if (isVideoDetailRoute(currentKey.toLegacyRoute()) &&
                    isVideoCardReturnTargetRoute(targetKey?.toLegacyRoute()))
                    mutableSession.value = mutableSession.value.markReturning(monotonicMillis())
                relatedRestorePending = isRelatedDetailPop &&
                    (mutableSession.value.previousTransitionSessions.isNotEmpty() ||
                        mutableSession.value.previousVideoSources.isNotEmpty())
                relatedTransitionObserved = false
                performBack()
                returned = true
            }
        }
        return returned
    }

    /** Original AppNavigation's markNavigation3VideoReturnBeforeBackAction: invoked at
     * the actual NavDisplay return commit, before the physical back pop. */
    fun prepareReturnBeforeBack(currentKey: BiliPaiNavKey, targetKey: BiliPaiNavKey?): Boolean {
        mutate {
            if (isVideoDetailRoute(currentKey.toLegacyRoute()) &&
                isVideoCardReturnTargetRoute(targetKey?.toLegacyRoute()))
                mutableSession.value = mutableSession.value.markReturning(monotonicMillis())
        }
        return owns() && mutableSession.value.isQuickReturnFromDetail
    }

    /** Stable portrait replacement clears only this same original return source.
     * It must not morph the replacement into the previous video's card. */
    fun clearVideoSourceForReplacement(): Boolean = mutate {
        mutableSession.value = mutableSession.value.recordVideoSourceRoute(null)
    }

    fun consumeReturning(): Boolean = mutate {
        mutableSession.value = mutableSession.value.clearReturning()
    }

    /** Full original Article caller chooses markReturning or clearReturning before pop. */
    fun returnFromArticle(useSharedReturn: Boolean, performBack: () -> Unit): Boolean {
        if (!owns()) return false
        var returned = false
        admitRootNavigation {
            mutate {
                mutableSession.value = if (useSharedReturn)
                    mutableSession.value.markReturning(monotonicMillis())
                else mutableSession.value.clearReturning()
                performBack()
                returned = true
            }
        }
        return returned
    }

    /** Actual navigation/clock exposure, never an elapsed timer or pretend immediate idle. */
    fun onRelatedReturnExposure(animated: Boolean, exposure: VideoCardTransitionExposure): Boolean = mutate {
        val decision = resolveRelatedReturnSourceRestoreDecision(relatedRestorePending,
            relatedTransitionObserved, animated, exposure)
        relatedTransitionObserved = decision.transitionObserved
        if (decision.shouldRestore) {
            relatedRestorePending = false
            relatedTransitionObserved = false
            mutableSession.value = mutableSession.value.restorePreviousVideoSourceAfterRelatedReturn()
            CardPositionManager.restoreVideoSourceKey(mutableSession.value.lastVideoSourceKey)
        }
    }

    /** The original click snapshot determines host-local geometry. No return-time feed reads. */
    fun sourceMetadata(): BiliPaiNavSourceMetadata = synchronized(lock) {
        desktopOriginalHomeSourceMetadata(mutableSession.value)
    }
    fun sourceMetadataInCapturedHost(): BiliPaiNavSourceMetadata = synchronized(lock) {
        desktopOriginalHomeSourceMetadata(mutableSession.value).relativeToHost(
            mutableSession.value.transitionSession?.hostOriginInRoot ?: Offset.Zero)
    }

    override fun close() { synchronized(lock) { closed = true } }
}
