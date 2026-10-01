// Source: app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt
// Stable target: 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589
package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Offset
import com.android.purebilibili.core.util.CardPositionManager
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.navigation3.*

internal fun desktopOriginalHomeSourceMetadata(navigation3ReturnSession: BiliPaiReturnSessionState) = resolveBiliPaiNavSourceMetadata(
    sourceKey = navigation3ReturnSession.transitionSession?.sourceKey
        ?: navigation3ReturnSession.lastVideoSourceKey,
    sourceRoute = navigation3ReturnSession.transitionSession?.sourceRoute
        ?: navigation3ReturnSession.lastVideoSourceRoute,
    clickedBoundsRecorded = navigation3ReturnSession.transitionSession
        ?.hasUsableSourceGeometry
        ?: false,
    cardFullyVisible = navigation3ReturnSession.transitionSession
        ?.cardFullyVisible
        ?: false,
    cardSourceDirection = navigation3ReturnSession.transitionSession
        ?.cardSourceDirection
        ?: navigation3ReturnSession.lastCardSourceDirection,
    sourceCornerDp = navigation3ReturnSession.transitionSession?.sourceCornerDp,
    coverIdentity = navigation3ReturnSession.transitionSession?.coverIdentity,
    sourceBounds = navigation3ReturnSession.transitionSession?.cardBounds,
    sourceCoverBounds = navigation3ReturnSession.transitionSession?.coverBounds,
    sourceLayout = navigation3ReturnSession.transitionSession?.sourceLayout
        ?: com.android.purebilibili.core.ui.transition.VideoCardSourceLayout.COVER_ONLY,
    sourceChromeSnapshot = navigation3ReturnSession.transitionSession?.sourceChromeSnapshot,
)

internal fun desktopOriginalHomeCardSourceDirection(): BiliPaiNavCardSourceDirection {
    return resolveBiliPaiNavCardSourceDirection(
        clickedBoundsRecorded = CardPositionManager.lastClickedCardBounds != null,
        cardFullyVisible = CardPositionManager.isCardFullyVisible,
        isSingleColumnCard = CardPositionManager.isSingleColumnCard,
        normalizedCenterX = CardPositionManager.lastClickedCardCenter?.x
    )
}

internal fun desktopOriginalHomeTransitionSession(
    bvid: String,
    source: BiliPaiVideoSource,
    coverIdentity: String?,
    navigationHostOriginInRoot: Offset,
) = VideoCardTransitionSession.create(
    bvid = bvid,
    source = source,
    cardBounds = CardPositionManager.lastClickedCardBounds,
    coverBounds = CardPositionManager.lastClickedCoverBounds,
    sourceCornerDp = CardPositionManager.lastClickedVideoSourceCornerDp,
    cardSourceDirection = desktopOriginalHomeCardSourceDirection(),
    coverIdentity = coverIdentity,
    cardFullyVisible = CardPositionManager.isCardFullyVisible,
    isSingleColumnCard = CardPositionManager.isSingleColumnCard,
    sourceLayout = CardPositionManager.lastClickedVideoSourceLayout,
    sourceChromeSnapshot = CardPositionManager.lastClickedVideoSourceChromeSnapshot,
    hostOriginInRoot = navigationHostOriginInRoot,
)

internal fun desktopOriginalHomePrearmOpening(
    session: VideoCardTransitionSession,
    sharedVideoCardTransitionEnabled: Boolean,
    relatedVideoTransitionEnabled: Boolean,
    systemReduceMotion: Boolean,
    videoCardTransitionClock: VideoCardTransitionClock,
) {
    val transitionEnabledForSource = resolveVideoCardTransitionEnabledForSource(
        cardTransitionEnabled = sharedVideoCardTransitionEnabled,
        relatedVideoTransitionEnabled = relatedVideoTransitionEnabled,
        sourceRoute = session.sourceRoute,
    )
    val hasUsableSourceBounds = session.cardBounds
        ?.let { it.width > 1f && it.height > 1f } == true
    if (
        shouldUseMiuixVideoCardMorph(
            cardTransitionEnabled = transitionEnabledForSource,
            reduceMotion = systemReduceMotion,
            sourceRoute = session.sourceRoute,
            hasUsableSourceBounds = hasUsableSourceBounds,
        )
    ) {
        // Activate the source-cover session before NavDisplay mounts the destination.
        // Waiting for its stack-observer effect exposes the black player Surface for one frame.
        videoCardTransitionClock.beginOpeningIfNeeded(session.sourceRoute)
    }
}
