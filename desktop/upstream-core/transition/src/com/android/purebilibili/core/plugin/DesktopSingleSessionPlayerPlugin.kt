// Transition compat: upstream player plugins now hand out one PlayerPluginSession per playback (plugin-sdk
// PlayerPluginApi.createSession). The legacy feature plugin copies (SponsorBlockPlugin) and the desktop player dispatch
// still carry the old shape, where the plugin object is the single playback session. Delete together with those copies
// when the video feature moves to upstream's PlayerPluginSessionHost (M4a).
package com.android.purebilibili.core.plugin

import com.android.purebilibili.plugin.sdk.PlayerPluginApi
import com.android.purebilibili.plugin.sdk.PlayerPluginSession

/** A legacy player plugin that is its own and only playback session; the desktop player runs one playback at a time. */
interface DesktopSingleSessionPlayerPlugin : BuiltinPlugin, PlayerPluginApi, PlayerPluginSession {
    override fun createSession(): PlayerPluginSession = this
}

/** The enabled player plugins as the desktop dispatch drives them. A plugin that needs independent sessions has no desktop
 * dispatch yet and fails loudly instead of being skipped. */
fun PluginManager.getEnabledDesktopPlayerPlugins(): List<DesktopSingleSessionPlayerPlugin> =
    getEnabledPlayerPlugins().map { plugin ->
        plugin as? DesktopSingleSessionPlayerPlugin ?: throw com.bilipai.desktop.compat.UnsupportedDesktopCapability(
            "player plugin sessions (${plugin.id})", "the desktop player dispatch drives single-session legacy plugins until M4a")
    }
