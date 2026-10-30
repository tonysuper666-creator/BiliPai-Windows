// Replaces upstream app/src/main/java/com/android/purebilibili/feature/space/SpaceBannerFusionEffect.kt sha256=4d9f9f8a137ec4b59087b4fba9e3001f53003d1886424979b9e87c51d779e280
// Upstream hosts the banner in ProfileAvatarFusionView, an Android View that shrinks it into the camera cutout while the
// page scrolls. A window has no cutout, so the desktop draws the banner as upstream does with fusion disabled; the banner
// still forwards drag and wheel scrolling to the page like upstream's fused host.
package com.android.purebilibili.feature.space

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun SpaceBannerFusionEffect(
    collapseFractionProvider: () -> Float,
    scrollOffsetPxProvider: () -> Float,
    maxParallaxTranslationPx: Float,
    scrollState: ScrollableState,
    modifier: Modifier = Modifier,
    fusionEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!fusionEnabled) {
        Box(modifier = modifier) { content() }
        return
    }
    Box(modifier = modifier.scrollable(scrollState, Orientation.Vertical, reverseDirection = true)) { content() }
}
