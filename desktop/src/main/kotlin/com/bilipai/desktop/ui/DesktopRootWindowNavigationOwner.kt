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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.data.DesktopLoginInstallationReceipt

internal enum class DesktopLoginReturnOrigin { ROUTE, MODAL, ACCOUNT_ADD }
internal class DesktopLoginReturnTicket internal constructor(
    val sourceEpoch: Long, val sourceMid: Long?, val destination: BiliPaiNavKey,
    val origin: DesktopLoginReturnOrigin,
)
internal data class DesktopAcceptedLoginReturn(
    val ticket: DesktopLoginReturnTicket, val installation: DesktopLoginInstallationReceipt,
)
internal class DesktopLoginReturnBinding internal constructor(
    val sourceEpoch: Long,
    val owns: () -> Boolean,
    val installed: (DesktopLoginInstallationReceipt) -> Unit,
    val cancel: () -> Unit,
)

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
    private val loginLock = Any()
    private var loginTicket: DesktopLoginReturnTicket? = null
    private val acceptedLogin = MutableStateFlow<DesktopAcceptedLoginReturn?>(null)
    // Retain the last receipt after consume/cancel: removing a ticket must not restart Root installation.
    internal val installedLoginReturn = acceptedLogin.asStateFlow()
    internal fun beginLoginReturn(epoch: Long, mid: Long?, destination: BiliPaiNavKey,
        origin: DesktopLoginReturnOrigin): DesktopLoginReturnBinding? {
        check(EventQueue.isDispatchThread())
        if (!owns() || destination == BiliPaiNavKey.Login || destination == BiliPaiNavKey.Onboarding) return null
        val readKey = when (destination) {
            is BiliPaiNavKey.VideoDetail -> destination.copy(openId = 0L)
            is BiliPaiNavKey.Search -> destination.copy(openId = 0L)
            is BiliPaiNavKey.LiveAreaDetail -> destination.copy(openId = 0L)
            // A launchId identifies a retired transient media launch. Still bind the login,
            // but reopen the existing MainHost rather than replay that old launch.
            is BiliPaiNavKey.ExternalMedia -> BiliPaiNavKey.MainHost
            is BiliPaiNavKey.PluginsSettings -> destination.copy(importUrl = null)
            else -> destination
        }
        return synchronized(loginLock) {
            if (!owns()) return@synchronized null
            val ticket = DesktopLoginReturnTicket(epoch, mid, readKey, origin)
            loginTicket = ticket
            binding(ticket)
        }
    }
    private fun binding(ticket: DesktopLoginReturnTicket) = DesktopLoginReturnBinding(ticket.sourceEpoch,
        { synchronized(loginLock) { owns() && loginTicket === ticket && acceptedLogin.value?.ticket !== ticket } },
        { receipt -> synchronized(loginLock) {
            if (owns() && loginTicket === ticket && receipt.sourceEpoch == ticket.sourceEpoch &&
                receipt.sourceMid == ticket.sourceMid && receipt.acceptedEpoch >= receipt.sourceEpoch && receipt.acceptedMid > 0L) {
                if (ticket.sourceMid != null && ticket.sourceMid != receipt.acceptedMid) loginTicket = null
                else acceptedLogin.value = DesktopAcceptedLoginReturn(ticket, receipt)
            }
        } },
        { synchronized(loginLock) { if (loginTicket === ticket) loginTicket = null } })
    internal fun pendingLoginBinding(epoch: Long): DesktopLoginReturnBinding? = synchronized(loginLock) {
        loginTicket?.takeIf { owns() && it.sourceEpoch == epoch }?.let(::binding)
    }
    internal fun isLoginReturnPending(value: DesktopAcceptedLoginReturn): Boolean = synchronized(loginLock) {
        owns() && loginTicket === value.ticket && acceptedLogin.value === value
    }
    internal fun consumeLoginReturn(value: DesktopAcceptedLoginReturn, epoch: Long, mid: Long?): BiliPaiNavKey? = synchronized(loginLock) {
        if (!owns() || loginTicket !== value.ticket || acceptedLogin.value !== value) return@synchronized null
        if (value.installation.acceptedEpoch != epoch || value.installation.acceptedMid != mid) {
            loginTicket = null; return@synchronized null
        }
        loginTicket = null
        value.ticket.destination
    }
    internal fun cancelLoginReturnForSource(epoch: Long, mid: Long?) = synchronized(loginLock) {
        if (loginTicket?.let { it.sourceEpoch == epoch && it.sourceMid == mid } == true) loginTicket = null
    }
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
        synchronized(loginLock) { loginTicket = null; acceptedLogin.value = null }
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
