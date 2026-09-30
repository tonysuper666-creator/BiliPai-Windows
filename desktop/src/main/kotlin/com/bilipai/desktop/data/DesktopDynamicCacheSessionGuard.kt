package com.bilipai.desktop.data

/** SessionStore is the only authority; callers never receive cookie material. */
internal data class DesktopDynamicCacheOwner(val mid: Long, val epoch: Long, val namespaceTag: String) {
    override fun toString(): String = "DesktopDynamicCacheOwner(mid=$mid, epoch=$epoch)"
}

internal interface DesktopDynamicCacheSessionGuard {
    fun dynamicCacheOwner(): DesktopDynamicCacheOwner?
    /** The accepted transaction and any account mutation use the same SessionStore monitor. */
    fun withCurrentDynamicCacheOwner(owner: DesktopDynamicCacheOwner, block: () -> Unit): Boolean
}
