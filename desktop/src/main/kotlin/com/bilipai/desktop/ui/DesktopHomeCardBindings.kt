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

/** Root supplies the existing original global Manager; Library is only an unavailable-owner compatibility input. */
internal val LocalDesktopHomeCardProgress=staticCompositionLocalOf<((String,Long)->Long?)?>{null}
internal fun desktopHomeCardProgressReader(
    library:com.bilipai.desktop.DesktopLibrary,
    originalPosition:((String,Long)->Long?)?=null,
    owned:()->Boolean={true},
):(String,Long)->Long? = {bvid,cid->
    if(!owned()) 0L else {
        // Render retirement is safe zero, not Library fallback or an exception through composition.
        val original=try { originalPosition?.invoke(bvid,cid) }
            catch(retired:kotlinx.coroutines.CancellationException) { 0L }
            catch(retired:com.bilipai.desktop.data.BiliApiException) {
                if(retired.apiCode == -101) 0L else throw retired
            }
        val position=original ?: library.resumeCard(bvid)?.takeIf{cid>0 && it.preferredCid==cid}?.progressSeconds
            ?.takeIf{it>=0}?.toLong()?.times(1000L)
        if(owned()) position else 0L
    }
}
/** Created by the current repository/epoch owner, not a second process-global network module. */
internal val LocalDesktopVideoCardOnlineStore=staticCompositionLocalOf<VideoCardOnlineCountStore?>{null}
