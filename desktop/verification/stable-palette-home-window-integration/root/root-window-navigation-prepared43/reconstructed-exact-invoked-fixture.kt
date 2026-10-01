package com.bilipai.desktop.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.awt.EventQueue
import java.awt.Frame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private class RootWindowFixtureVm(val state: SavedStateHandle, val clears: AtomicInteger) : ViewModel() {
    override fun onCleared() { clears.incrementAndGet() }
}

fun main() {
    val assertions = AtomicInteger()
    fun verify(value: Boolean) { check(value); assertions.incrementAndGet() }
    val alive = AtomicBoolean(true)
    val back = AtomicInteger()
    val clears = AtomicInteger()
    lateinit var window: Frame
    lateinit var owner: DesktopRootWindowNavigationOwner
    lateinit var vm: RootWindowFixtureVm
    val factory = viewModelFactory { initializer { RootWindowFixtureVm(createSavedStateHandle(), clears) } }
    EventQueue.invokeAndWait {
        window = Frame("BiliPai window lifecycle verification").apply {
            isFocusableWindow = false
            setBounds(60, 60, 320, 120)
        }
        owner = DesktopRootWindowNavigationOwner(window, alive::get) { back.incrementAndGet() }
        verify(owner.lifecycle.currentState == Lifecycle.State.CREATED)
        verify(!owner.requestBack())
        vm = ViewModelProvider.create(owner, factory)["retained-proof", RootWindowFixtureVm::class]
        vm.state["original-entry-value"] = 23
        window.isVisible = true
    }
    // Drain real AWT shown/active-window events without fabricating WindowEvents.
    EventQueue.invokeAndWait { }
    EventQueue.invokeAndWait {
        verify(window.isDisplayable && window.isVisible)
        verify(owner.lifecycle.currentState == Lifecycle.State.STARTED)
        verify(owner.requestBack())
        verify(back.get() == 1)
        window.isVisible = false
    }
    EventQueue.invokeAndWait { }
    EventQueue.invokeAndWait {
        verify(owner.lifecycle.currentState == Lifecycle.State.CREATED)
        verify(!owner.requestBack())
        val retained = ViewModelProvider.create(owner, factory)["retained-proof", RootWindowFixtureVm::class]
        verify(retained === vm)
        verify(retained.state.get<Int>("original-entry-value") == 23)
        verify(clears.get() == 0)
        window.isVisible = true
    }
    EventQueue.invokeAndWait { }
    EventQueue.invokeAndWait {
        val child = androidx.navigationevent.NavigationEventDispatcher(owner.navigationEventDispatcher)
        verify(owner.lifecycle.currentState == Lifecycle.State.STARTED)
        alive.set(false)
        verify(!owner.requestBack())
        verify(back.get() == 1)
        // Root closes remaining child dispatchers on the real main-window owner.
        child.isEnabled = true
    }
    // Actual off-EDT shutdown marshals cleanup to EDT and synchronously drains that cleanup.
    owner.close()
    owner.close()
    EventQueue.invokeAndWait {
        verify(owner.lifecycle.currentState == Lifecycle.State.DESTROYED)
        verify(clears.get() == 1)
        verify(!owner.owns() && !owner.requestBack())
        window.dispose()
    }
    println("PASS actual physical-window root owner / ${assertions.get()} assertions")
    println("LIMIT no focused RESUMED/minimize/predictive/Root NavDisplay/account/HTTP acceptance")
}
