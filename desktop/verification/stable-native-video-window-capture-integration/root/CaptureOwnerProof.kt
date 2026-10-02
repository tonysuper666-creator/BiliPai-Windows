package com.bilipai.desktop.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

private interface CaptureReadbackApi : StdCallLibrary {
    fun GetWindowDisplayAffinity(window: Pointer, affinity: IntByReference): Int
}

private fun <T> edt(action: () -> T): T {
    if (EventQueue.isDispatchThread()) return action()
    val task = FutureTask(action)
    EventQueue.invokeAndWait(task)
    return task.get()
}

fun main() {
    var assertions = 0
    fun verify(value: Boolean, label: String) {
        check(value) { label }
        assertions++
    }
    val api = Native.load("user32", CaptureReadbackApi::class.java)
    // These three hidden peers belong only to this test. No application window,
    // input event, screenshot or capture tool is opened or operated.
    val root = edt { Frame("BiliPai owned capture fixture").apply { setSize(96, 96); addNotify() } }
    val child = edt { Window(root).apply { setSize(64, 64); addNotify() } }
    val foreign = edt { Frame("BiliPai rejected capture fixture").apply { setSize(64, 64); addNotify() } }
    val rootAlive = AtomicBoolean(true)
    val diagnostics = mutableListOf<String>()
    val owner = DesktopWindowsVideoCaptureOwner(root, rootAlive::get) { diagnostics.add(it) }
    val leases = mutableListOf<AutoCloseable>()
    fun affinity(window: Window): Int = edt {
        val result = IntByReference(-1)
        check(api.GetWindowDisplayAffinity(Native.getWindowPointer(window), result) != 0) {
            "GetWindowDisplayAffinity failed: ${Native.getLastError()}"
        }
        result.value
    }
    fun state(requested: Boolean, protectedWindows: Int, label: String) {
        val current = owner.state.value
        verify(current.requested == requested && current.apiApplied &&
            current.protectedWindows == protectedWindows && current.unavailableReason == null, label)
    }
    try {
        verify(!root.isVisible && !child.isVisible && !foreign.isVisible, "fixture peers remain hidden")
        verify(root.isDisplayable && child.isDisplayable && foreign.isDisplayable, "real native peers")
        verify(affinity(root) == 0 && affinity(child) == 0 && affinity(foreign) == 0, "native baseline")
        state(false, 0, "initial state")
        verify(!owner.orientationLocked, "initial orientation unlocked")

        val firstCurrent = AtomicBoolean(true)
        val first = owner.acquire(firstCurrent::get).also(leases::add)
        state(true, 1, "root request applied")
        verify(affinity(root) == 0x11, "root native exclusion readback")
        verify(owner.orientationLocked, "requested orientation locked")
        owner.onNativeWindowAvailability(child, true)
        state(true, 2, "owned foreground included")
        verify(affinity(root) == 0x11 && affinity(child) == 0x11, "both native HWNDs excluded")

        val secondCurrent = AtomicBoolean(true)
        val second = owner.acquire(secondCurrent::get).also(leases::add)
        first.close()
        first.close()
        state(true, 2, "old and duplicate release retain new request")
        verify(affinity(root) == 0x11 && affinity(child) == 0x11, "current native policy retained")
        secondCurrent.set(false)
        owner.refresh()
        state(false, 0, "retired request removed")
        verify(affinity(root) == 0 && affinity(child) == 0, "entry retirement clears both HWNDs")
        verify(!owner.orientationLocked, "retired orientation unlocked")
        second.close()

        val thirdCurrent = AtomicBoolean(true)
        val third = owner.acquire(thirdCurrent::get).also(leases::add)
        state(true, 2, "new entry request")
        owner.onNativeWindowAvailability(child, false)
        state(true, 1, "only foreground removed")
        verify(affinity(child) == 0 && affinity(root) == 0x11, "only removed HWND cleared")
        owner.onNativeWindowAvailability(child, true)
        state(true, 2, "same owned foreground restored")
        verify(affinity(child) == 0x11, "restored native policy")

        var rejected = false
        try { owner.onNativeWindowAvailability(foreign, true) }
        catch (failure: ExecutionException) { rejected = failure.cause is IllegalStateException }
        catch (_: IllegalStateException) { rejected = true }
        verify(rejected, "unrelated fixture HWND rejected")
        verify(affinity(foreign) == 0, "foreign HWND never changed")
        state(true, 2, "foreign rejection retains actual root targets")

        val fourthCurrent = AtomicBoolean(true)
        owner.acquire(fourthCurrent::get).also(leases::add)
        thirdCurrent.set(false)
        owner.refresh()
        third.close()
        state(true, 2, "retired old entry does not remove current request")
        verify(owner.orientationLocked, "current request retains orientation lock")

        owner.close()
        verify(affinity(root) == 0 && affinity(child) == 0, "owner close clears native policy")
        verify(!owner.orientationLocked, "owner close unlocks orientation")
        val closed = owner.state.value
        verify(!closed.requested && !closed.apiApplied && closed.protectedWindows == 0 &&
            closed.unavailableReason == "Root capture owner closed", "closed state")
        leases.forEach(AutoCloseable::close)
        owner.acquire { true }.close()
        owner.onNativeWindowAvailability(child, true)
        owner.refresh()
        verify(owner.state.value == closed && affinity(root) == 0 && affinity(child) == 0,
            "late release registration and acquisition cannot revive closed owner")
        verify(diagnostics.isEmpty(), "real API applied without availability diagnostics")
        println("CaptureOwnerProof PASS $assertions assertions; real owned hidden HWNDs; zero production overrides")
    } finally {
        owner.close()
        leases.forEach(AutoCloseable::close)
        edt { child.dispose(); foreign.dispose(); root.dispose() }
    }
}
