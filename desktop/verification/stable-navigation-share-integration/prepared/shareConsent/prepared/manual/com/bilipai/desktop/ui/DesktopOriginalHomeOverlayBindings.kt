package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.android.purebilibili.feature.video.share.VideoSharePayload

/** Mounts both entire ORIGINAL renderers in Home's existing overlay port. */
internal class DesktopOriginalHomeOverlayBindings(
    private val share:DesktopVideoShareBindings,
    private val consent:DesktopCrashConsentBindings,
    private val owned:()->Boolean,
) : DesktopHomeOverlayPorts {
    @Composable override fun VideoShareSheetHost(payload:VideoSharePayload?,onDismiss:()->Unit) {
        if(owned())CompositionLocalProvider(LocalDesktopVideoShareBindings provides share) {
            com.android.purebilibili.feature.video.share.VideoShareSheetHost(payload,onDismiss={if(owned())onDismiss()})
        }
    }
    @Composable override fun CrashTrackingConsentDialog(onDismiss:()->Unit) {
        if(owned())CompositionLocalProvider(LocalDesktopCrashConsentBindings provides consent) {
            com.android.purebilibili.feature.home.components.CrashTrackingConsentDialog(onDismiss={if(owned())onDismiss()})
        }
    }
}
