package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.core.events.BrandSuccessEvents
import kotlinx.coroutines.Job

/** One actual caller's receipt over its existing Store -> entry/source permit.
 * Natural completion keeps the receipt; cancellation and a real retirement do not.
 * No account, media, request, queue or feedback state is reconstructed here. */
class DesktopBrandSuccessOrigin(
    private val caller: Job,
    private val stillOwned: () -> Boolean,
    private val admission: (() -> Unit) -> Boolean,
) {
    @Volatile private var retired = false
    fun isCurrent(): Boolean {
        if (retired || caller.isCancelled) return false
        if (!stillOwned()) { retired = true; return false }
        return !retired
    }
    fun admit(action: () -> Unit): Boolean {
        if (!isCurrent()) return false
        var applied = false
        return admission {
            if (isCurrent()) { action(); applied = true }
        } && applied
    }
    /** Decorative publication must not change a confirmed business result. */
    fun publish(action: () -> Unit): Boolean = try {
        var published = false
        if (caller.isActive) admit { if (caller.isActive) { action(); published = true } }
        published
    } catch (_: Exception) { false }
}

val LocalDesktopBrandSuccessEvents = staticCompositionLocalOf<BrandSuccessEvents> {
    error("Brand success feedback requires the existing Root event instance")
}
