package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.FeedVerticalStaggeredGrid
import com.android.purebilibili.core.util.resolveWindowWidthSizeClass
import com.android.purebilibili.feature.home.*
import com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences

/** Original Home grid mode/policy on the actual desktop viewport; no synthetic fold posture. */
@Composable
internal fun DesktopHomeCardGrid(state:LazyStaggeredGridState,modifier:Modifier=Modifier,
    useLowQualityCover:Boolean=false,
    content:LazyStaggeredGridScope.(DesktopHomeGridCardFrame)->Unit) {
    val preferences=checkNotNull(LocalDesktopHomeCardPreferences.current){"Root shared Home card preferences are not mounted"}
    val settings by preferences.settings.collectAsState(preferences.initialSettings())
    val density=LocalDensity.current.density
    val windowWidthDp=(LocalWindowInfo.current.containerSize.width/density).dp
    val widthClass=resolveWindowWidthSizeClass(windowWidthDp)
    BoxWithConstraints(modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        // Desktop navigation occupies part of the window; use its real feed viewport, not hidden sidebar pixels.
        val contentWidth=maxWidth.coerceAtMost(resolveHomeFeedMaxContentWidth())
        val columns=resolveHomeFeedGridColumns(contentWidth.value.toInt(),displayMode=0,
            fixedColumnCount=resolveHomeFeedStoredColumnCount(widthClass,settings.gridColumnCountCompact,settings.gridColumnCount),
            cardWidthPreset=settings.homeFeedCardWidthPreset,widthSizeClass=widthClass)
        val layout=resolveHomeFeedCardLayout(settings.homeFeedCardStyle,columns,widthClass)
        val cardWidthDp=resolveDesktopHomeGridCardWidthDp(contentWidth.value,columns,layout.outerPaddingDp,layout.itemSpacingDp)
        val frame=DesktopHomeGridCardFrame(layout,columns,cardWidthDp,
            resolveHomeCoverRequestSpec(cardWidthDp,density,useLowQualityCover))
        FeedVerticalStaggeredGrid(columns=StaggeredGridCells.Fixed(columns),state=state,
            modifier=Modifier.width(contentWidth).fillMaxHeight(),
            contentPadding=PaddingValues(layout.outerPaddingDp.dp),
            verticalItemSpacing=layout.verticalItemSpacingDp.dp,
            horizontalArrangement=Arrangement.spacedBy(layout.itemSpacingDp.dp),content={content(frame)})
    }
}
