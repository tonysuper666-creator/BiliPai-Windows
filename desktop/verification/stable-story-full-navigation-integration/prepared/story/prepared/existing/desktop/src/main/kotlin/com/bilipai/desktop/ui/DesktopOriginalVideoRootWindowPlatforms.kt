package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Root constructs these views once for the retained Assembly. The Section's
 * remembered carrier is passed unchanged to bootstrap, Holder and Fullscreen.
 * Neither route disposal nor PiP visibility closes a native/global Window owner.
 */
internal class DesktopOriginalVideoRootWindowPlatformsImpl(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val nativeSection: DesktopOriginalVideoSectionWindowsPlatform,
    override val holder: DesktopOriginalVideoHolderPlatform,
    override val fullscreen: DesktopOriginalFullscreenPlatform,
    override val tablet: DesktopOriginalTabletAudioPlatform,
    override val audio: DesktopOriginalAudioModePlatform,
    override val music: DesktopOriginalMusicUiPlatform,
    override val content: DesktopOriginalVideoContentBindings,
    override val portrait: DesktopOriginalPortraitPlatform,
    override val subtitleMode: DesktopOriginalSubtitleModeBinding,
    private val windows: DesktopOriginalVideoWindowsWindowPort,
    private val displays: DesktopWindowsVideoDisplayCapabilities,
    private val retireCaptureProtection: () -> Unit,
) : DesktopOriginalVideoRootWindowPlatforms, AutoCloseable {
    private val closed = AtomicBoolean()
    override val storyFeeds = DesktopOriginalStoryFeedOwners(assembly, portrait)
    init {
        require(holder.section === nativeSection && fullscreen.section === nativeSection && portrait.section === nativeSection)
        require(holder.settingsContext === nativeSection.settingsContext)
    }
    @Composable override fun InitialNativeSurface(modifier: Modifier) {
        check(!closed.get())
        nativeSection.InitialNativeSurface(modifier)
    }
    override suspend fun awaitNativeInitialization() {
        check(!closed.get()); nativeSection.awaitNativeInitialization()
    }
    /** Called after actual Assembly/gate drain, on EDT and outside Store/entry/
     * native monitors. Global player, PiP and shared CaptureOwner remain alive. */
    override fun close() {
        check(EventQueue.isDispatchThread())
        if (!closed.compareAndSet(false, true)) return
        storyFeeds.close()
        subtitleMode.close()
        windows.close()
        displays.close()
        retireCaptureProtection()
    }
}

/** Fullscreen is another foreground layout of the same Section, including its
 * extra controls. The sole portal's identity nesting marker prevents the inner
 * Section from attaching/invoking the same movable carrier twice in one frame.
 */
internal class DesktopOriginalVideoRootFullscreenPlatform(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    override val section: DesktopOriginalVideoSectionWindowsPlatform,
    private val windows: DesktopOriginalVideoWindowsWindowPort,
    private val acquireDanmaku: (DesktopOriginalMpvSectionControl) -> AutoCloseable,
    private val isPipActive: () -> Boolean,
    private val predictiveBackGeneration: () -> Int,
) : DesktopOriginalFullscreenPlatform {
    override fun acquireFullscreen(player: DesktopOriginalMpvSectionControl?): AutoCloseable {
        require(player == null || player === assembly.section)
        return windows.acquireFullscreen()
    }
    override fun acquireKeepAwake(enabled: Boolean) = windows.acquireKeepAwake(enabled)
    override fun recoverSurface(player: DesktopOriginalMpvSectionControl) {
        require(player === assembly.section)
        section.recoverViewport("fullscreen", true, isPipActive(), predictiveBackGeneration())
    }
    override fun acquireDanmakuPlayer(player: DesktopOriginalMpvSectionControl): AutoCloseable {
        require(player === assembly.section); return acquireDanmaku(player)
    }
    @Composable override fun Surface(player: DesktopOriginalMpvSectionControl?, foreground: @Composable () -> Unit) {
        require(player == null || player === assembly.section)
        section.RenderPlayerForeground(foreground)
    }
}
