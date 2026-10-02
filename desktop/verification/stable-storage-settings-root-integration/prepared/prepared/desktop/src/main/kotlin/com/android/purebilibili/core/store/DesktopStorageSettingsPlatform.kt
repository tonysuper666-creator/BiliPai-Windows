package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal class DesktopStorageMirrorEditor {
    internal val changes = linkedMapOf<String, JsonElement?>()
    fun putString(key: String, value: String?) = apply { changes[key] = value?.let(::JsonPrimitive) }
}
/** Original dual write is one accepted same-Store replacement; no Android prefs/second backing. */
internal suspend fun editStorageSettingsAndCommitPrefs(
    context: DesktopOriginalPlayerSettingsContext, name: String,
    editSettings: DesktopOriginalPlayerPreferenceValues.() -> Unit,
    editPrefs: DesktopStorageMirrorEditor.() -> Unit,
) = withContext(Dispatchers.IO) {
    require(name == "download_prefs")
    val caller = currentCoroutineContext()
    fun checkRequest() { caller.ensureActive(); context.requireCurrent(); com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal() }
    context.pluginContext.store.updateOriginalNamespacesFromSnapshot("settings", ::checkRequest,
        { context.preferenceWritePermit(::checkRequest) }) { snapshot ->
        val values = DesktopOriginalPlayerPreferenceValues(snapshot).apply(editSettings)
        val mirror = DesktopStorageMirrorEditor().apply(editPrefs)
        Unit to mapOf("settings" to values.changes.toMap(), name to mirror.changes.toMap())
    }
}
