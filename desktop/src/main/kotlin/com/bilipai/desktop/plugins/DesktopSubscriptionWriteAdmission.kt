package com.bilipai.desktop.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Stateless operation context. The Root remains the sole account/page admission authority. */
object DesktopSubscriptionWriteAdmission {
    private class Operation(
        val checkRequest: () -> Unit,
        val commit: ((() -> Unit) -> Boolean),
        val cleanup: ((() -> Unit) -> Boolean),
    )
    private val current = ThreadLocal<Operation?>()

    suspend fun <T> withOwned(
        stillOwned: () -> Boolean,
        commitIfCurrent: ((() -> Unit) -> Boolean),
        block: suspend () -> T,
    ): T {
        val requestContext = currentCoroutineContext()
        fun checkOwner() {
            if (!stillOwned()) throw CancellationException("Subscription owner retired")
        }
        fun checkRequest() { requestContext.ensureActive(); checkOwner() }
        fun commit(requestRequired: Boolean, action: () -> Unit): Boolean {
            if (requestRequired) checkRequest() else checkOwner()
            var entered = false
            val accepted = commitIfCurrent {
                if (requestRequired) checkRequest() else checkOwner()
                action()
                entered = true
            }
            if (!accepted || !entered) throw CancellationException("Subscription admission rejected")
            return true
        }
        checkRequest()
        val operation = Operation(::checkRequest, { commit(true, it) }, { commit(false, it) })
        return withContext(current.asContextElement(operation)) { block().also { checkRequest() } }
    }

    /** Used only immediately around the existing AtomicFile final replacement/publication. */
    fun commitOrOriginal(action: () -> Unit) {
        val operation = current.get()
        if (operation == null) action() else operation.commit(action)
    }

    /** Busy cleanup may run after its Job cancels, while the same Root owner still exists. */
    fun cleanupOrOriginal(action: () -> Unit) {
        val operation = current.get()
        if (operation == null) action() else operation.cleanup(action)
    }

    /** Root admission only mints the existing Store permit; it performs no file IO or Store locking. */
    internal fun acquirePreferencePermit(store: DesktopPluginStore): DesktopPluginStore.OriginalPreferenceWritePermit {
        var permit: DesktopPluginStore.OriginalPreferenceWritePermit? = null
        commitOrOriginal { permit = DesktopPluginStore.OriginalPreferenceWritePermit(store) }
        return checkNotNull(permit) { "Subscription preference admission rejected" }
    }

    /** Settings writes already hold Root admission before entering the original backing monitor. */
    fun checkCurrentRequestOrOriginal() { current.get()?.checkRequest?.invoke() }

    /** Repository request eligibility joins the same Root admission; no-scope plugin IO is unchanged. */
    suspend fun <T> whileCurrent(eligible: () -> Boolean, block: suspend () -> T): T {
        val parent = current.get() ?: return block()
        fun check() {
            parent.checkRequest()
            if (!eligible()) throw CancellationException("Subscription request replaced")
        }
        val operation = Operation(::check,
            { action -> parent.commit { check(); action() } },
            { action -> parent.cleanup { if (eligible()) action() } })
        check()
        return withContext(current.asContextElement(operation)) { block().also { check() } }
    }
}
