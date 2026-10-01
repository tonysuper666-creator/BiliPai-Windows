package com.bilipai.desktop.ui

import androidx.compose.ui.unit.IntRect
import com.android.purebilibili.core.util.AppDisplayContext
import com.android.purebilibili.feature.video.screen.VideoDetailSystemBarsApplySpec
import com.bilipai.desktop.player.PictureInPictureController
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Required resources are the existing Root HWND, shared DWM/Home policy, PiP
 * controller and same-native brightness authority. This entry owns registration
 * handles only; neither its disposal nor deferred exit closes a global player.
 * Root constructs one instance per retained assembly, outside all entry gates. */
internal class DesktopOriginalVideoWindowsWindowPort(
    private val root: Window,
    private val owner: DesktopOriginalVideoOwnerAssembly,
    private val isRootCurrent: () -> Boolean,
    private val isFullscreen: () -> Boolean,
    private val setFullscreen: (Boolean) -> Unit,
    private val pip: PictureInPictureController,
    private val clientPolicy: DesktopHomeWindowsClientPolicy,
    private val chrome: DesktopWindowsProfileChrome,
    private val clearViewportBrightness: (Long) -> Boolean,
    private val onDeferredExit: (DesktopOriginalVideoAcceptedPublication?, Boolean) -> Unit,
    private val diagnostic: (String) -> Unit,
) : DesktopOriginalVideoHolderWindowPort, AutoCloseable {
    private val closed = AtomicBoolean()
    private val sequence = AtomicLong()
    private val wake = ConcurrentHashMap<Long, AutoCloseable>()
    private var chromeLease: AutoCloseable? = null // EDT only, same shared DWM authority.
    @Volatile private var requestedOrientation = -1 // Original SCREEN_ORIENTATION_UNSPECIFIED.
    private var autoPipSubject: Subject? = null // EDT only.
    private val event = object : WindowAdapter() {
        override fun windowIconified(event: WindowEvent) {
            val subject = autoPipSubject ?: return
            if (subject.owns() && !pip.active.value && owner.section.playWhenReady)
                pip.open(root, subject.accepted?.nativeSource?.source?.title ?: "BiliPai")
        }
    }

    private inner class Subject(val accepted: DesktopOriginalVideoAcceptedPublication?) {
        fun owns(): Boolean = !closed.get() && isRootCurrent() && owner.owns() &&
            if (accepted == null) owner.native.current() == null else owner.native.isCurrent(accepted)
    }
    private fun capture() = Subject(owner.native.current())
    private fun onEdt(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) action()
        else { val task = FutureTask(action); EventQueue.invokeAndWait(task); task.get() }
    }
    init { onEdt { root.addWindowListener(event) } }

    override val presentation = object : DesktopOriginalVideoHolderPresentation {
        override val currentRequestedOrientation: Int get() = requestedOrientation
        override val isLandscape: Boolean get() = root.width > root.height
        override val isInMultiWindowMode: Boolean get() = !isFullscreen() &&
            (root !is Frame || root.extendedState and Frame.MAXIMIZED_BOTH != Frame.MAXIMIZED_BOTH)
        override val isChangingConfigurations: Boolean get() = false // Desktop resize does not recreate the route/Activity.
        override val rotationSensor: DesktopOriginalVideoHolderRotationSensor? get() = null
        override fun requestOrientation(requestedOrientation: Int, displayContext: AppDisplayContext?) {
            val subject = capture()
            onEdt {
                if (!subject.owns()) return@onEdt
                this@DesktopOriginalVideoWindowsWindowPort.requestedOrientation = requestedOrientation
                when (requestedOrientation) {
                    0, 6, 8, 11 -> setFullscreen(true) // Landscape presentation, not monitor rotation.
                    -1, 1, 7, 9, 12 -> setFullscreen(false)
                    else -> diagnostic("Windows keeps the current presentation for sensor/user orientation $requestedOrientation")
                }
            }
        }
    }
    override val supportsPictureInPicture: Boolean get() = !closed.get() && isRootCurrent()

    override fun acquireKeepAwake(enabled: Boolean): AutoCloseable {
        if (!enabled) return AutoCloseable {} // Original explicitly declines keeping the screen awake.
        val subject = capture()
        val lease = try { DesktopWindowsVideoWakeLease.acquire(subject::owns) { diagnostic(it.message ?: it.javaClass.simpleName) } }
        catch (failure: IllegalStateException) {
            diagnostic(failure.message ?: "Windows keep-awake unavailable")
            return AutoCloseable {} // Explicit failed Windows capability, never advertised as accepted.
        }
        val token = sequence.incrementAndGet()
        wake[token] = lease
        if (!subject.owns()) wake.remove(token)?.close()
        return AutoCloseable { wake.remove(token)?.close() }
    }
    override fun updatePictureInPicture(player: DesktopOriginalMpvSectionControl?, sourceBounds: IntRect?,
        autoEnterEnabled: Boolean, seamlessResizeEnabled: Boolean) {
        val subject = capture()
        onEdt {
            if (!subject.owns() || (player != null && player !== owner.section)) return@onEdt
            autoPipSubject = if (autoEnterEnabled && subject.accepted != null) subject else null
            subject.accepted?.nativeSource?.source?.title?.let(pip::updateTitle)
            if (seamlessResizeEnabled || sourceBounds != null)
                diagnostic("Windows floating player uses its existing aspect/resize owner; Android source-rect/seamless hints are unavailable")
        }
    }
    override fun resetAutoEnterPictureInPicture() = onEdt { autoPipSubject = null }
    override fun releaseEntryBrightness() {
        val subject = capture()
        if (subject.owns()) subject.accepted?.sourceVersion?.let(clearViewportBrightness)
    }
    override fun releaseEntryKeepAwake() {
        wake.keys.toList().forEach { wake.remove(it)?.close() }
    }
    override fun restoreEntryWindowChrome() = onEdt {
        // A shared authority removes only this registration and resolves current
        // remaining Root requests/theme. It never restores an old saved color.
        chromeLease?.close(); chromeLease = null
    }
    override fun captureEntryWindowChrome(): AutoCloseable = AutoCloseable { restoreEntryWindowChrome() }
    override fun applySystemBars(spec: VideoDetailSystemBarsApplySpec) {
        val subject = capture()
        onEdt {
            if (!subject.owns()) return@onEdt
            val next = chrome.acquire(true, spec.lightStatusBars) {
                check(subject.owns()) { "Video chrome source retired" }
            }
            val old = chromeLease; chromeLease = next; old?.close()
            // Actual Windows title mode/client background; no Android bar exists.
            diagnostic("Video system bars mapped to the Root Windows client/title policy (${spec.hiddenBars})")
        }
    }
    override fun acquireEdgeToEdge(): AutoCloseable {
        val subject = capture()
        if (subject.owns()) clientPolicy.ensureEdgeToEdge()
        return AutoCloseable { if (subject.owns()) clientPolicy.ensureEdgeToEdge() }
    }
    override fun deferEntryExit(navigationExit: Boolean, originalRequestedOrientation: Int?) {
        val subject = capture()
        EventQueue.invokeLater {
            if (!subject.owns()) return@invokeLater
            resetAutoEnterPictureInPicture()
            originalRequestedOrientation?.let { presentation.requestOrientation(it, null) }
            if (subject.owns()) onDeferredExit(subject.accepted, navigationExit)
        }
    }

    /** Same Window fullscreen lease, also used by the fullscreen platform view. */
    fun acquireFullscreen(): AutoCloseable {
        val subject = capture()
        var previous = false
        var registered = false
        onEdt { if (subject.owns()) { previous = isFullscreen(); setFullscreen(true); registered = true } }
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) onEdt {
                if (registered && subject.owns()) setFullscreen(previous)
            }
        }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            releaseEntryKeepAwake()
            restoreEntryWindowChrome()
            onEdt { autoPipSubject = null; root.removeWindowListener(event) }
        }
    }
}
