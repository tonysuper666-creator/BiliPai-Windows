package com.bilipai.desktop.ui

import com.sun.jna.Native
import com.bilipai.desktop.player.DesktopWindowsHdrDisplay
import com.sun.jna.Pointer
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface DesktopWindowsVideoDisplayCapability {
    data class Available(val hdrSupported: Boolean, val hdrUserEnabled: Boolean,
        val hdrActive: Boolean, val bitsPerChannel: Int) : DesktopWindowsVideoDisplayCapability
    data class Unavailable(val nativeCode: Int?, val reason: String) : DesktopWindowsVideoDisplayCapability
}

/** The monitor containing this actual Root HWND, not the default/first screen.
 * INFO_2 distinguishes HDR from WCG-only Advanced Color. Older API/driver/remote
 * sessions report unavailable; they never become a fabricated HDR capability.
 * Window events refresh metadata without a timer, second window or MPV core. */
internal class DesktopWindowsVideoDisplayCapabilities(private val root: Window) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val mutable = MutableStateFlow<DesktopWindowsVideoDisplayCapability>(
        DesktopWindowsVideoDisplayCapability.Unavailable(null, "Root window has not been inspected"))
    val state: StateFlow<DesktopWindowsVideoDisplayCapability> = mutable.asStateFlow()
    private val listener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = refresh()
        override fun componentShown(event: ComponentEvent) = refresh()
    }
    init { onEdt { root.addComponentListener(listener); refresh() } }

    private fun onEdt(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) action()
        else { val task = FutureTask(action); EventQueue.invokeAndWait(task); task.get() }
    }

    fun refresh() {
        check(EventQueue.isDispatchThread())
        if (closed.get()) return
        mutable.value = try { inspect() }
        catch (_: UnsatisfiedLinkError) {
            DesktopWindowsVideoDisplayCapability.Unavailable(null, "Display configuration API unavailable")
        } catch (_: IllegalStateException) {
            DesktopWindowsVideoDisplayCapability.Unavailable(null, "Display configuration contract unavailable")
        }
    }

    private fun inspect(): DesktopWindowsVideoDisplayCapability {
        if (!root.isDisplayable) return DesktopWindowsVideoDisplayCapability.Unavailable(null, "Root HWND unavailable")
        val observed = DesktopWindowsHdrDisplay.query(Pointer.nativeValue(Native.getWindowPointer(root)))
        return if (observed.known) DesktopWindowsVideoDisplayCapability.Available(
            observed.hdrSupported, observed.hdrUserEnabled, observed.hdrActive, observed.bitsPerChannel)
        else DesktopWindowsVideoDisplayCapability.Unavailable(observed.nativeCode, observed.statusText)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onEdt { root.removeComponentListener(listener) }
    }
}
