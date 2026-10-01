package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Candidate transport view of the caller's existing global settings backing.
 * Original key/string/null semantics are retained. Android's DataStore and
 * image_save_prefs sync mirror map to one Windows backing here; no cache or
 * second store is created. Root supplies the actual settings lifetime gate.
 * Directory selection, file URI serialization, KnownFolder default and image
 * encoding are separate pending platform consumers, not claims of this facade. */
internal class DesktopImageSaveLocationPreferences(
    private val store: DesktopPluginStore,
    private val withOwnedSettingsCommit: ((() -> Unit) -> Boolean),
) {
    private val KEY_IMAGE_SAVE_TREE_URI = DesktopPreferenceKey<String>("image_save_tree_uri") {
        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull
    }

    fun getImageSaveTreeUri(): Flow<String?> = store.snapshot("settings").map { preferences ->
        preferences[KEY_IMAGE_SAVE_TREE_URI]
    }

    fun getImageSaveTreeUriSync(): String? =
        DesktopPreferenceSnapshot(store.preferences("settings"))[KEY_IMAGE_SAVE_TREE_URI]

    suspend fun setImageSaveTreeUri(uri: String?) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val committed = withOwnedSettingsCommit {
            context.ensureActive()
            store.update("settings", mapOf("image_save_tree_uri" to uri?.let(::JsonPrimitive)))
        }
        if (!committed) throw CancellationException("图片保存位置设置已结束")
    }
}
