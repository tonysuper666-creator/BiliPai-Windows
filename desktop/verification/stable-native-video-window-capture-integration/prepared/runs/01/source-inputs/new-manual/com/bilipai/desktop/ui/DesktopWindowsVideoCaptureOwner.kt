package com.bilipai.desktop.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.IdentityHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DesktopWindowsVideoCaptureState(
    val requested: Boolean, val apiApplied: Boolean, val protectedWindows: Int,
    val unavailableReason: String?,
)

/** One app/Root-window affinity authority. Only the actual Root and its explicitly
 * registered original-player foreground Window are targeted. The opaque command
 * window identity remains separate and is never cast to an HWND or AWT Window.
 * SetWindowDisplayAffinity is a Windows capture-policy request, not DRM or a
 * guarantee against photographs/other capture mechanisms. No input is injected. */
internal class DesktopWindowsVideoCaptureOwner(
    private val root: Window,
    private val isRootAlive: () -> Boolean,
    private val diagnostic: (String) -> Unit,
) : AutoCloseable {
    private interface AffinityApi : StdCallLibrary {
        fun SetWindowDisplayAffinity(window: Pointer, affinity: Int): Int
    }
    private val api: AffinityApi by lazy { Native.load("user32", AffinityApi::class.java) }
    private class Target(val window: Window, val hwnd: Long, var applied: Int? = null)
    private val targets = IdentityHashMap<Window, Target>() // Actual EDT only.
    private val requests = linkedMapOf<Long, () -> Boolean>() // Actual EDT only.
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val mutableState = MutableStateFlow(DesktopWindowsVideoCaptureState(false, false, 0, "Root HWND unavailable"))
    val state: StateFlow<DesktopWindowsVideoCaptureState> = mutableState.asStateFlow()
    private val rootListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) { register(root); refresh() }
    }
    init { onEdt { root.addComponentListener(rootListener); register(root); refresh() } }

    private fun <T> onEdt(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        val task = FutureTask(action); EventQueue.invokeAndWait(task); return task.get()
    }
    private fun belongsToRoot(window: Window): Boolean {
        var current: Window? = window
        while (current != null) { if (current === root) return true; current = current.owner }
        return false
    }
    private fun register(window: Window) {
        check(EventQueue.isDispatchThread())
        if (closed.get() || !isRootAlive() || !window.isDisplayable) return
        check(belongsToRoot(window)) { "Capture target must be owned by the actual Root window" }
        val hwnd = Pointer.nativeValue(Native.getWindowPointer(window))
        if (targets[window]?.hwnd != hwnd) targets[window] = Target(window, hwnd)
    }
    /** Must receive the ACTUAL foreground Window from the sole popup owner. */
    fun onNativeWindowAvailability(window: Window, available: Boolean) = onEdt {
        if (available) { register(window); refresh() }
        else {
            val removed = targets.remove(window)
            // Clear only this unchanged HWND, never a replacement peer/window.
            if (removed != null && isRootAlive()) apply(removed, 0)
            refresh()
        }
    }
    private fun apply(target: Target, affinity: Int): String? {
        if (!target.window.isDisplayable || Pointer.nativeValue(Native.getWindowPointer(target.window)) != target.hwnd)
            return "Capture target HWND retired"
        if (target.applied == affinity) return null
        return try {
            if (api.SetWindowDisplayAffinity(Pointer(target.hwnd), affinity) == 0)
                "Windows capture policy unavailable (${Native.getLastError()})"
            else { target.applied = affinity; null }
        } catch (_: UnsatisfiedLinkError) { "Windows capture policy API unavailable" }
    }
    val orientationLocked: Boolean get() = onEdt { !closed.get() && requests.values.any { it() } }

    /** Registration and native work are performed outside Store/entry gates.
     * The predicate fixes the original entry and logical native source subject;
     * approved same-version direct recovery can retain that display policy. */
    fun acquire(stillOwned: () -> Boolean): AutoCloseable {
        val token = sequence.incrementAndGet()
        onEdt {
            if (!closed.get() && isRootAlive() && stillOwned()) {
                requests[token] = stillOwned; refresh()
            }
        }
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) onEdt {
                requests.remove(token); refresh() // Remaining CURRENT requests win.
            }
        }
    }
    fun refresh() = onEdt {
        if (closed.get() || !isRootAlive()) return@onEdt
        requests.entries.removeIf { !it.value() }
        register(root)
        val wanted = requests.isNotEmpty()
        var failure: String? = if (targets[root] == null) "Root HWND unavailable" else null
        var applied = 0
        for (target in targets.values) {
            val result = apply(target, if (wanted) 0x11 else 0)
            if (result == null) { if (wanted) applied++ } else failure = result
        }
        val previous = mutableState.value
        val next = DesktopWindowsVideoCaptureState(wanted, failure == null, applied, failure)
        mutableState.value = next
        if (failure != null && failure != previous.unavailableReason) diagnostic(failure)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) onEdt {
            requests.clear()
            if (isRootAlive()) targets.values.forEach { target ->
                apply(target, 0)?.let(diagnostic)
            }
            targets.clear(); root.removeComponentListener(rootListener)
            mutableState.value = DesktopWindowsVideoCaptureState(false, false, 0, "Root capture owner closed")
        }
    }
}
