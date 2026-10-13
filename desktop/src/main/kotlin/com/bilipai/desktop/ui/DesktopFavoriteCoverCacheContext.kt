package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive

/** Fixed account/Entry view of the existing global preference backing. No transport or cover map. */
class DesktopFavoriteCoverCacheContext internal constructor(
    internal val store: DesktopPluginStore,
    private val capturedMid: Long?,
    private val readMid: () -> Long?,
    private val owns: () -> Boolean,
    private val admit: (() -> Boolean, () -> Unit) -> Boolean,
) {
    internal fun isCurrent() = capturedMid != null && capturedMid > 0L && readMid() == capturedMid && owns()
    internal fun namespace(original: String): String {
        require(original == "favorite_folder_cover_cache")
        return "$original:${checkNotNull(capturedMid)}"
    }
    internal fun admitCurrent(current: () -> Boolean, action: () -> Unit): Boolean = admit(current, action)
    internal suspend fun capture(catalogCurrent: () -> Boolean): DesktopFavoriteCoverCacheRequest {
        val coroutine = currentCoroutineContext(); coroutine.ensureActive()
        val caller = checkNotNull(coroutine[Job]) { "Favorite cover request requires its actual caller" }
        return DesktopFavoriteCoverCacheRequest(this, caller, catalogCurrent).also { it.checkCurrent() }
    }
}

/** Borrows one actual original VM request, fixed Entry/account and catalog generation. */
class DesktopFavoriteCoverCacheRequest internal constructor(
    private val context: DesktopFavoriteCoverCacheContext,
    private val caller: Job,
    private val catalogCurrent: () -> Boolean,
) {
    internal fun isCurrent() = caller.isActive && context.isCurrent() && catalogCurrent()
    internal fun checkCurrent() {
        caller.ensureActive()
        if (!context.isCurrent() || !catalogCurrent()) throw CancellationException("Favorite cover request retired")
    }
    internal fun requireOwner(owner: DesktopFavoriteCoverCacheContext) {
        require(context === owner) { "Favorite cover cache belongs to another Entry" }
        checkCurrent()
    }
    internal fun readPayload(namespace: String, key: String): String? {
        checkCurrent(); require(key == "favorite_folder_cover_payload_v1")
        val result = (context.store.preferences(context.namespace(namespace))[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        checkCurrent(); return result
    }
    internal fun writePayload(namespace: String, key: String, raw: String?) {
        checkCurrent(); require(key == "favorite_folder_cover_payload_v1")
        val ownedNamespace = context.namespace(namespace)
        fun permit(): DesktopPluginStore.OriginalPreferenceWritePermit {
            checkCurrent()
            var result: DesktopPluginStore.OriginalPreferenceWritePermit? = null
            if (!context.admitCurrent(::isCurrent) {
                    checkCurrent(); result = DesktopPluginStore.OriginalPreferenceWritePermit(context.store)
                }) throw CancellationException("Favorite cover preference admission retired")
            return checkNotNull(result)
        }
        context.store.updateOriginalFromSnapshot(ownedNamespace, ::checkCurrent, ::permit) {
            Unit to mapOf(key to raw?.let(::JsonPrimitive))
        }
    }
    internal fun admit(action: () -> Unit) {
        checkCurrent()
        if (!context.admitCurrent(::isCurrent) { checkCurrent(); action() })
            throw CancellationException("Favorite cover publication retired")
    }
}
