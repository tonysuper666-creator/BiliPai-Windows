// GENERATED from app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt; do not edit.
// LF-normalized SHA-256: b43c112cfda44780b29d3a5419f6ce452bf7c0e4859cb0145636eae3f3ec2010
package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalReplySettings {
    private val KEY_SUB_REPLY_LOADED_COUNT_ENABLED =
        booleanPreferencesKey("sub_reply_loaded_count_enabled")

    private val KEY_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT =
        intPreferencesKey("comment_collapsed_reply_preview_limit")

    const val DEFAULT_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT = 3

    private const val COMMENT_PREVIEW_CACHE_PREFS = "comment_preview_cache"

    private const val CACHE_KEY_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT = "comment_collapsed_reply_preview_limit"

    fun normalizeCommentCollapsedReplyPreviewLimit(value: Int): Int = value.coerceIn(1, 10)

    fun getCommentCollapsedReplyPreviewLimit(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences ->
            normalizeCommentCollapsedReplyPreviewLimit(
                preferences[KEY_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT]
                    ?: DEFAULT_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT
            )
        }

    suspend fun setCommentCollapsedReplyPreviewLimit(context: Context, value: Int) {
        val normalized = normalizeCommentCollapsedReplyPreviewLimit(value)
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT] = normalized
        }
        context.getSharedPreferences(COMMENT_PREVIEW_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(CACHE_KEY_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT, normalized)
            .apply()
    }

    fun getCommentCollapsedReplyPreviewLimitSync(context: Context): Int {
        return normalizeCommentCollapsedReplyPreviewLimit(
            context.getSharedPreferences(COMMENT_PREVIEW_CACHE_PREFS, Context.MODE_PRIVATE)
                .getInt(
                    CACHE_KEY_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT,
                    DEFAULT_COMMENT_COLLAPSED_REPLY_PREVIEW_LIMIT
                )
        )
    }

    fun getSubReplyLoadedCountEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_SUB_REPLY_LOADED_COUNT_ENABLED] ?: false }

    suspend fun setSubReplyLoadedCountEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_SUB_REPLY_LOADED_COUNT_ENABLED] = enabled
        }
    }

}
