package com.bilipai.desktop.ui

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.OnBackCompletedFallback
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import java.awt.EventQueue
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicBoolean

/** One actual main-window owner, retained above every route and account epoch.
 *
 * All lifecycle, saved-state, ViewModel and navigation operations run on Swing's EDT. The
 * original Miuix host owns entry stores below this store. Destroying the window clears them;
 * covering a page, resizing, minimizing or changing accounts does not create another root.
 */
internal class DesktopRootWindowNavigationOwner(
    private val window: Window,
    private val rootAlive: () -> Boolean,
    private val onBackFallback: () -> Unit,
) : SavedStateRegistryOwner, ViewModelStoreOwner, HasDefaultViewModelProviderFactory,
    NavigationEventDispatcherOwner, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = SavedStateViewModelFactory()
    override val defaultViewModelCreationExtras: CreationExtras = MutableCreationExtras().apply {
        this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@DesktopRootWindowNavigationOwner
        this[VIEW_MODEL_STORE_OWNER_KEY] = this@DesktopRootWindowNavigationOwner
    }
    override val navigationEventDispatcher = NavigationEventDispatcher(
        onBackCompletedFallback = object : OnBackCompletedFallback {
            override fun onBackCompletedFallback() { if (owns()) onBackFallback() }
        },
    )
    private val backInput = DirectNavigationEventInput()
    private val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val focusListener = PropertyChangeListener { refreshOnEdt() }
    private val windowListener = object : WindowAdapter() {
        override fun windowOpened(e: WindowEvent) = refreshOnEdt()
        override fun windowActivated(e: WindowEvent) = refreshOnEdt()
        override fun windowDeactivated(e: WindowEvent) = refreshOnEdt()
        override fun windowIconified(e: WindowEvent) = refreshOnEdt()
        override fun windowDeiconified(e: WindowEvent) = refreshOnEdt()
        override fun windowStateChanged(e: WindowEvent) = refreshOnEdt()
        // WINDOW_CLOSING is only a request; failed/cancelled app shutdown may keep the window.
        override fun windowClosed(e: WindowEvent) { close() }
    }
    private val componentListener = object : ComponentAdapter() {
        override fun componentShown(e: ComponentEvent) = refreshOnEdt()
        override fun componentHidden(e: ComponentEvent) = refreshOnEdt()
    }

    init {
        check(EventQueue.isDispatchThread()) { "Root window navigation owner must be created on the EDT" }
        savedStateController.performAttach()
        enableSavedStateHandles()
        // A fresh Windows application has no Android saved-state Bundle or fabricated registry.
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        navigationEventDispatcher.addInput(backInput)
        window.addWindowListener(windowListener)
        window.addWindowStateListener(windowListener)
        window.addComponentListener(componentListener)
        focusManager.addPropertyChangeListener("activeWindow", focusListener)
        refreshOnEdt()
    }

    fun owns(): Boolean = !closed.get() && rootAlive()

    /** Completed keyboard/system back, after Root's text, modal and fullscreen priorities.
     * Predictive pointer gestures remain the actual Miuix host's input, never synthetic progress.
     */
    fun requestBack(): Boolean {
        check(EventQueue.isDispatchThread()) { "Navigation input must be delivered on the EDT" }
        if (!owns() || !navigationEventDispatcher.isEnabled) return false
        backInput.backCompleted()
        return true
    }

    private fun refreshOnEdt() {
        check(EventQueue.isDispatchThread())
        if (closed.get()) return
        val visible = window.isDisplayable && window.isVisible &&
            ((window as? Frame)?.extendedState?.and(Frame.ICONIFIED) ?: 0) == 0
        var active = focusManager.activeWindow
        while (active != null && active !== window) active = active.owner
        val current = owns()
        registry.currentState = when {
            !current || !visible -> Lifecycle.State.CREATED
            active === window -> Lifecycle.State.RESUMED
            else -> Lifecycle.State.STARTED
        }
        navigationEventDispatcher.isEnabled = current && visible
    }

    /** Call after the retained Home/route owners drain, outside SessionStore admission monitors.
     * Immediate admission retirement also rejects already queued EDT inputs before disposal.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val dispose = {
            navigationEventDispatcher.isEnabled = false
            window.removeWindowListener(windowListener)
            window.removeWindowStateListener(windowListener)
            window.removeComponentListener(componentListener)
            focusManager.removePropertyChangeListener("activeWindow", focusListener)
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
            // The original dispatcher recursively disposes remaining child dispatchers.
            navigationEventDispatcher.removeInput(backInput)
            navigationEventDispatcher.dispose()
        }
        if (EventQueue.isDispatchThread()) dispose() else EventQueue.invokeAndWait(dispose)
    }
}
