package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuType
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlayState
import com.android.purebilibili.feature.video.ui.overlay.showOriginalDesktopCommandLink
import com.android.purebilibili.feature.video.ui.overlay.completeOriginalDesktopCommandLinkConfirmation
import com.bilipai.desktop.data.BiliApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** A UI receipt, never a source/account authority. Reopened dialogs have distinct identities. */
internal class DesktopWindowsCommandLinkRequest internal constructor(
    val item: CommandDanmakuItem, val targetBvid: String,
)

/** Exact committed confirmation content; its predicate reads the actual owned dialog peer.
 * Recomposition can remount the same request, so a callback must capture this token too. */
internal class DesktopWindowsCommandLinkMount internal constructor(
    internal val request: DesktopWindowsCommandLinkRequest,
    private var stillMounted: (() -> Boolean)?,
) {
    internal fun isMounted(): Boolean = stillMounted?.invoke() == true
    internal fun close() { stillMounted = null }
}

/** Uses the original complete LINK callbacks and the existing Root presentation permit.
 * It creates no repository, request job, store or navigation authority. All entry points
 * run on Compose's UI dispatcher; the permit only publishes UI/typed navigation actions. */
internal class DesktopWindowsCommandLinkBinding(
    val sourceLease: Any,
    private val commandState: CommandDanmakuOverlayState,
    private val stillOwned: () -> Boolean,
    private val withAdmission: (() -> Unit) -> Boolean,
    private val onNavigate: (String) -> Unit,
    private val onFeedback: (String) -> Unit,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val mutablePending = MutableStateFlow<DesktopWindowsCommandLinkRequest?>(null)
    private var mounted: DesktopWindowsCommandLinkMount? = null
    val pending: StateFlow<DesktopWindowsCommandLinkRequest?> get() = mutablePending
    private fun owned() = alive.get() && stillOwned()
    fun isCurrent(request: DesktopWindowsCommandLinkRequest): Boolean = owned() && mutablePending.value === request

    private fun admit(action: () -> Unit): Boolean {
        if (!owned()) return false
        var entered = false
        var accepted = false
        try {
            val permitted = withAdmission {
                if (owned()) {
                    entered = true
                    action()
                    accepted = true
                }
            }
            return permitted && accepted
        } catch (cancelled: CancellationException) {
            if (entered || owned()) throw cancelled
        } catch (retired: BiliApiException) {
            // The legacy primary-account permit throws -101 at retirement. Do not
            // turn a stale UI callback into an error, or swallow errors inside actions.
            if (entered || retired.apiCode != -101 || owned()) throw retired
        }
        return accepted
    }

    fun open(item: CommandDanmakuItem): Boolean {
        if (item.type != CommandDanmakuType.LINK || commandState.isDismissed(item.id)) return false
        return admit {
            showOriginalDesktopCommandLink(item, onFeedback) { original, target ->
                mounted?.close(); mounted = null
                mutablePending.value = DesktopWindowsCommandLinkRequest(original, target)
            }
        }
    }

    /** Clearing this exact local receipt needs no permission to modify another owner. */
    fun dismiss(request: DesktopWindowsCommandLinkRequest) {
        if (mutablePending.value === request) {
            mutablePending.value = null
            mounted?.close(); mounted = null
        }
    }

    fun mount(request: DesktopWindowsCommandLinkRequest, stillMounted: () -> Boolean): DesktopWindowsCommandLinkMount? {
        if (!isCurrent(request)) return null
        mounted?.close()
        return DesktopWindowsCommandLinkMount(request, stillMounted).also { mounted = it }
    }

    fun unmount(receipt: DesktopWindowsCommandLinkMount) {
        receipt.close()
        if (mounted === receipt) {
            mounted = null
            dismiss(receipt.request)
        }
    }

    fun retireIfUnowned() {
        if (!owned()) {
            mutablePending.value = null
            mounted?.close(); mounted = null
        }
    }

    fun confirm(request: DesktopWindowsCommandLinkRequest, receipt: DesktopWindowsCommandLinkMount,
                dialogTargetBvid: String): Boolean {
        fun mountedCurrent() = mounted === receipt && receipt.request === request && receipt.isMounted()
        if (!isCurrent(request) || !mountedCurrent() || request.targetBvid != dialogTargetBvid) return false
        var completed = false
        val admitted = admit {
            if (mutablePending.value === request && mountedCurrent()) {
                completeOriginalDesktopCommandLinkConfirmation(request.item, dialogTargetBvid, commandState,
                    onDismiss = { dismiss(request) }, onNavigate = onNavigate)
                completed = true
            }
        }
        return admitted && completed
    }

    override fun close() {
        alive.set(false)
        mutablePending.value = null
        mounted?.close(); mounted = null
    }
}
