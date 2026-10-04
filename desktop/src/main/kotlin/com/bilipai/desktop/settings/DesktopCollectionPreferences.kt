package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Original collection keys/read mappers use the existing global settings generation. */
internal class DesktopCollectionPreferenceEditor(private val snapshot: DesktopPreferenceSnapshot) {
    val values = linkedMapOf<String, JsonElement?>()
    operator fun <T> get(key: DesktopPreferenceKey<T>): T? =
        if (values.containsKey(key.name)) values[key.name]?.let(key.decode) else snapshot[key]
    operator fun <T> set(key: DesktopPreferenceKey<T>, value: T) {
        values[key.name] = when (value) {
            is String -> JsonPrimitive(value)
            else -> error("Unsupported original collection setting value")
        }
    }
}
internal class DesktopCollectionSettingsDataStore(private val context: DesktopPluginContext) {
    val data: StateFlow<DesktopPreferenceSnapshot> get() = context.store.snapshot("settings")
    suspend fun edit(block: (DesktopCollectionPreferenceEditor) -> Unit) = withContext(Dispatchers.IO) {
        val operation = DesktopCollectionPreferenceWriteOperation.requireCurrent(context)
        context.store.updateOriginalFromSnapshot("settings", operation.checkRequest,
            { operation.acquirePermit() }) { snapshot ->
            val editor = DesktopCollectionPreferenceEditor(snapshot).apply(block)
            Unit to editor.values.toMap()
        }
    }
}
internal val DesktopPluginContext.collectionSettingsDataStore get() = DesktopCollectionSettingsDataStore(this)

/** Only the original collection setters run here. Root admission mints the existing
 * Store permit; file IO and backing CAS stay outside Account/entry admission. */
internal object DesktopCollectionPreferenceWriteOperation {
    internal class Operation(
        val context: DesktopPluginContext,
        val checkRequest: () -> Unit,
        private val admit: ((() -> Unit) -> Boolean),
    ) {
        fun acquirePermit(): DesktopPluginStore.OriginalPreferenceWritePermit {
            checkRequest()
            var permit: DesktopPluginStore.OriginalPreferenceWritePermit? = null
            if (!admit {
                    checkRequest()
                    permit = DesktopPluginStore.OriginalPreferenceWritePermit(context.store)
                }) throw CancellationException("Collection settings owner retired")
            return permit ?: throw CancellationException("Collection settings admission rejected")
        }
    }

    private val current = ThreadLocal<Operation?>()
    fun requireCurrent(context: DesktopPluginContext): Operation {
        val operation = checkNotNull(current.get()) { "Original collection write requires its captured owner" }
        check(operation.context === context) { "Collection settings require the same Root Store context" }
        operation.checkRequest()
        return operation
    }

    suspend fun <T> withOwned(context: DesktopPluginContext, owned: () -> Boolean,
        admit: ((() -> Unit) -> Boolean), block: suspend () -> T): T {
        val caller = currentCoroutineContext()
        fun checkRequest() {
            caller.ensureActive()
            if (!owned()) throw CancellationException("Collection settings owner retired")
            DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
        }
        checkRequest()
        val operation = Operation(context, ::checkRequest, admit)
        return withContext(current.asContextElement(operation)) { block().also { checkRequest() } }
    }
}
