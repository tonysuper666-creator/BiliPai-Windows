package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.android.purebilibili.feature.home.components.cards.VideoCardOnlineCountStore

/** Actual enclosing Compose window; no Android configuration or display is emulated. */
internal data class DesktopHomeCardWindowBounds(val screenWidthDp:Int,val screenHeightDp:Int)
internal object DesktopHomeCardWindowMetrics {
    val current:DesktopHomeCardWindowBounds
        @Composable @ReadOnlyComposable get() {
            val size=LocalWindowInfo.current.containerSize;val density=LocalDensity.current.density
            return DesktopHomeCardWindowBounds((size.width/density).toInt(),(size.height/density).toInt())
        }
}

/** Root supplies its current-account library. Null means no local checkpoint is known. */
internal val LocalDesktopHomeCardProgress=staticCompositionLocalOf<((String,Long)->Long?)?>{null}
internal fun desktopHomeCardProgressReader(library:com.bilipai.desktop.DesktopLibrary):(String,Long)->Long? = {bvid,cid->
    library.resumeCard(bvid)?.takeIf{cid>0 && it.preferredCid==cid}?.progressSeconds
        ?.takeIf{it>=0}?.toLong()?.times(1000L)
}
/** Created by the current repository/epoch owner, not a second process-global network module. */
internal val LocalDesktopVideoCardOnlineStore=staticCompositionLocalOf<VideoCardOnlineCountStore?>{null}
