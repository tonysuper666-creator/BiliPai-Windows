package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import kotlinx.serialization.json.*

/** Explicit Windows platform choice. There is no Firebase/Crashlytics transport or credential.
 * This only feeds the existing local anonymous, enhanced-consent-gated diagnostic consumer;
 * it never writes a MID, identity token, video, URL, Cookie or account name. */
internal class DesktopHomeLocalIdentityAnalytics(
    private val globalStore:DesktopPluginStore,
    private val diagnostics:DesktopDiagnostics,
    private val isCurrent:()->Boolean,
) : DesktopHomeIdentityAnalytics {
    val firebaseTransportAvailable:Boolean get()=false
    override fun syncUserContext(mid:Long?,isVip:Boolean,privacyModeEnabled:Boolean){
        try {
            if(!isCurrent())return
            // Exact original SettingsManager key/default; read the same actual global backing.
            val consent=globalStore.preferences("settings")["analytics_enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            if(!consent)return
            val loggedIn=mid!=null && mid>0
            diagnostics.record("I","HomeUserContext","loggedIn=$loggedIn, vip=$isVip, privacy=$privacyModeEnabled")
        } catch(_:Exception) {
            // Original analytics platform failures do not interrupt Home account/list loading.
        }
    }
}
