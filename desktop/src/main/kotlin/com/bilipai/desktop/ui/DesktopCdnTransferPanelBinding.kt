package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.plugin.CdnRegionPlugin
import com.android.purebilibili.feature.plugin.CdnTransferRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Captured view of the mounted Root's existing settings, Runtime and native subject.
 * No Store, scope, plugin/provider, network/client, playback counter or observer is created. */
internal class DesktopCdnTransferPanelBinding(
    private val window: DesktopOriginalVideoRootWindowEnvironment,
    private val shell: DesktopOriginalVideoShellOwner,
    private val owner: DesktopOriginalVideoOwnerAssembly,
    private val settings: DesktopOriginalPlayerSettingsContext,
    private val expected: DesktopOriginalVideoAcceptedPublication?,
) {
    val plugin: CdnRegionPlugin? = window.runtime.plugins.value
        .filter { it.enabled }.map { it.plugin }.filterIsInstance<CdnRegionPlugin>().firstOrNull()
    init { require(settings.pluginContext.store === window.runtime.store); assertCurrent() }
    private fun current(): Boolean = window.owns() && owner.owns() && shell.slot.currentAssembly() === owner &&
        owner.native.current() === expected && (plugin == null || window.runtime.plugins.value.any { it.plugin === plugin && it.enabled })
    fun assertCurrent() { settings.requireCurrent(); if (!current()) throw CancellationException("CDN settings entry/source retired") }
    fun canUseParallelDownload(): Boolean { assertCurrent(); return plugin?.canUseParallelDownload() == true }
    fun mutate(action: () -> Unit) { assertCurrent(); settings.commit { assertCurrent(); action() }; assertCurrent() }
    suspend fun setParallelDownloadEnabled(enabled: Boolean) {
        currentCoroutineContext().ensureActive(); assertCurrent()
        val captured = plugin ?: throw CancellationException("CDN plugin is disabled")
        window.runtime.runCapturedPlaybackPluginSettings(captured, ::current,
            { action -> settings.commit { assertCurrent(); action() }; true }) {
            captured.setParallelDownloadEnabled(enabled)
        }
        currentCoroutineContext().ensureActive(); assertCurrent()
    }
}

@Composable internal fun rememberDesktopCdnTransferPanelBinding(): DesktopCdnTransferPanelBinding {
    val window = LocalDesktopOriginalVideoRootWindowEnvironment.current
    val shell = LocalDesktopOriginalVideoShellOwner.current
    val settings = LocalDesktopOriginalPlayerSettingsContext.current
    val owner = shell.slot.requireAssembly()
    // Existing flows cause source/enable changes to rebuild this captured view. This adds no poller.
    val nativeState by owner.section.nativePlayer.state.collectAsState()
    val providers by window.runtime.plugins.collectAsState()
    val expected = owner.native.current()
    return remember(window, shell, settings, owner, expected, providers) {
        // Observe the actual existing value; diagnostics do not invent a native media ACK.
        nativeState
        DesktopCdnTransferPanelBinding(window, shell, owner, settings, expected)
    }
}
