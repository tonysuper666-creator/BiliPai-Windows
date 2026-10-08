package com.bilipai.desktop.ui

import com.bilipai.desktop.data.DesktopHomeNavRequestReceipt
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/** Platform facade over the SAME global backing. The receipt is captured when this actual
 * retained Root is installed, including its successful UI credential-installation stamp.
 * It never adopts a new account/stamp on an old poll or resurrects an old Root. */
internal class DesktopMessageNotificationContext private constructor(
    internal val repository: DesktopRepository,
    internal val store: DesktopPluginStore,
    internal val root: DesktopHomeRetainedRoot,
    internal val routes: DesktopOriginalRootRouteAssembly,
    private val receipt: DesktopHomeNavRequestReceipt,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    internal val epoch get() = root.capturedEpoch
    internal val mid get() = root.entry.gate.mid
    val applicationContext get() = this
    val settings = DesktopMessageNotificationDataStore(this, "message_notification_settings")
    val state = DesktopMessageNotificationDataStore(this, "message_notification_state")

    private fun retained() = !closed.get() && root.isCurrentOwner() && routes.owns()
    internal fun admit(block: () -> Unit): Boolean {
        var applied = false
        repository.withCurrentHomeNavRequest(receipt, ::retained) {
            root.entry.gate.commit { if (retained()) { block(); applied = true } }
        }
        return applied
    }
    internal fun isCurrent(): Boolean = admit {}
    internal fun hasAuthenticatedSession(): Boolean {
        var authenticated = false
        admit {
            authenticated = mid != null && !repository.ownedHomeCookie("SESSDATA", epoch, ::retained).isNullOrBlank()
        }
        return authenticated
    }
    internal fun check() {
        if (!isCurrent()) throw CancellationException("Message notification Root retired")
    }
    override fun close() { closed.set(true) }

    companion object {
        internal fun captureOrNull(repository: DesktopRepository, store: DesktopPluginStore,
            root: DesktopHomeRetainedRoot, routes: DesktopOriginalRootRouteAssembly): DesktopMessageNotificationContext? =
            try {
                val receipt = repository.captureHomeNavRequest(root.capturedEpoch, root.entry.gate.mid) {
                    root.isCurrentOwner() && routes.owns()
                }
                DesktopMessageNotificationContext(repository, store, root, routes, receipt)
                    .also { it.check() }
            } catch (_: CancellationException) { null }
    }
}

/** Reads/writes the original key types. No second preference file, snapshot cache or owner.
 * Original edit callbacks may be retried by the existing backing CAS and must stay pure. */
internal class DesktopMessageNotificationPreferenceEditor(private val initial: DesktopPreferenceSnapshot) {
    internal val edits = linkedMapOf<String, JsonElement?>()
    operator fun <T> get(key: DesktopPreferenceKey<T>): T? =
        if (edits.containsKey(key.name)) edits[key.name]?.let(key.decode) else initial[key]
    operator fun <T> contains(key: DesktopPreferenceKey<T>) = get(key) != null
    operator fun <T> set(key: DesktopPreferenceKey<T>, value: T) {
        if (initial[key] == value) { edits.remove(key.name); return }
        edits[key.name] = when (value) {
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> error("Unsupported original message notification preference type")
        }
    }
    fun <T> remove(key: DesktopPreferenceKey<T>) {
        if (initial[key] == null) edits.remove(key.name) else edits[key.name] = null
    }
}

internal class DesktopMessageNotificationDataStore(
    private val context: DesktopMessageNotificationContext,
    private val namespace: String,
) {
    val data: Flow<DesktopPreferenceSnapshot> = context.store.snapshot(namespace).map {
        currentCoroutineContext().ensureActive(); context.check(); it
    }
    suspend fun edit(block: (DesktopMessageNotificationPreferenceEditor) -> Unit) = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        fun check() { caller.ensureActive(); context.check() }
        check()
        // Match DataStore's no-op edit behavior: default-off/reset of an absent baseline
        // must not stage a file on every Root installation. Callbacks below are original,
        // pure key edits; nonempty edits are recomputed by the existing backing CAS.
        val preview = DesktopMessageNotificationPreferenceEditor(context.store.snapshot(namespace).value)
        block(preview)
        check()
        if (preview.edits.isEmpty()) return@withContext
        context.store.updateOriginalFromSnapshot(namespace, ::check, {
            var permit: DesktopPluginStore.OriginalPreferenceWritePermit? = null
            if (!context.admit { caller.ensureActive(); permit = DesktopPluginStore.OriginalPreferenceWritePermit(context.store) })
                throw CancellationException("Message notification preference source retired")
            requireNotNull(permit)
        }) { snapshot ->
            val editor = DesktopMessageNotificationPreferenceEditor(snapshot)
            block(editor)
            Unit to editor.edits.toMap()
        }
        // An already accepted same-backing rename runs outside Root gates. Subsequent delivery
        // must independently recheck this receipt/caller; it cannot claim an atomic OS toast.
        check()
    }
}
