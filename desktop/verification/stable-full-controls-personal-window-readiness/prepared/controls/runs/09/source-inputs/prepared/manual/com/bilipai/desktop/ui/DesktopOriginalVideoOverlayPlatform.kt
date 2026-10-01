package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import com.android.purebilibili.core.plugin.CastPluginApi
import com.android.purebilibili.core.plugin.CastPluginRoute
import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.feature.video.share.VideoSharePayload

/** Required windows/service boundaries of the complete original overlay.
 * Bind only the existing Root transport, cast proxy/discovery, clipboard and share owner.
 */
internal interface DesktopOriginalVideoOverlayPlatform {
    fun networkTypeLabel(): String
    fun copyText(label: String, text: String)
    fun feedback(message: String)
    fun readPanelRefreshRate(): Float?
    fun exportPlayerDiagnostic(content: String): String?
    suspend fun getTvCastPlayData(aid: Long, cid: Long, qn: Int): PlayUrlData?
    fun castProxyUrl(url: String): String
    fun registerCastDashManifest(manifest: String): String
    fun ensureCastProxyStarted()
    val dashContentType: String
    @Composable fun CastDevices(onDismissRequest: () -> Unit, onPluginCastDeviceSelected: (CastPluginApi, CastPluginRoute) -> Unit)
    @Composable fun Share(payload: VideoSharePayload, onDismiss: () -> Unit)
}

/** Platform adapter only; retains each original feedback message and trigger. */
internal object DesktopOriginalPlayerFeedback {
    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 1
    fun makeText(context: DesktopOriginalPlayerSettingsContext, text: String, duration: Int): PendingFeedback {
        require(duration == LENGTH_SHORT || duration == LENGTH_LONG)
        return PendingFeedback(context, text)
    }
    class PendingFeedback(private val context: DesktopOriginalPlayerSettingsContext, private val text: String) {
        fun show() { context.commit { context.videoOverlay.feedback(text) } }
    }
}

@Composable
internal fun DesktopOriginalPlayerDeviceListDialog(onDismissRequest: () -> Unit, onPluginCastDeviceSelected: (CastPluginApi, CastPluginRoute) -> Unit) {
    LocalDesktopOriginalPlayerSettingsContext.current.videoOverlay.CastDevices(onDismissRequest, onPluginCastDeviceSelected)
}

@Composable
internal fun DesktopOriginalPlayerVideoShareSheetHost(payload: VideoSharePayload?, onDismiss: () -> Unit) {
    if (payload != null) LocalDesktopOriginalPlayerSettingsContext.current.videoOverlay.Share(payload, onDismiss)
}
