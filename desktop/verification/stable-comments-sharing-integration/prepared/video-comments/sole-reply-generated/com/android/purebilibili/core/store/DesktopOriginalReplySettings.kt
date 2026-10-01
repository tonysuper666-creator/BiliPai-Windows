// GENERATED from app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt; do not edit.
// LF-normalized SHA-256: 680005e1f25e8a365d30f0c78c988765e7d2140008c57d9bf31d859c5b835b1c
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

    private val KEY_COMMENT_DEFAULT_SORT_MODE = intPreferencesKey("comment_default_sort_mode")

    fun getCommentDefaultSortMode(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences ->
            val value = preferences[KEY_COMMENT_DEFAULT_SORT_MODE] ?: 3
            if (value == 2 || value == 3) value else 3
        }

    fun getCommentDefaultSortModeSync(context: Context): Int {
        val value = context.getSharedPreferences("comment_settings", Context.MODE_PRIVATE)
            .getInt("default_sort_mode", 3)
        return if (value == 2 || value == 3) value else 3
    }

    private val KEY_COMMENT_FRAUD_DETECTION_ENABLED =
        booleanPreferencesKey("comment_fraud_detection_enabled")

    fun getCommentFraudDetectionEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_COMMENT_FRAUD_DETECTION_ENABLED] ?: true }

    private val KEY_COMMENT_MEMBER_DECORATIONS_ENABLED =
        booleanPreferencesKey("comment_member_decorations_enabled")

    fun getCommentMemberDecorationsEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences -> preferences[KEY_COMMENT_MEMBER_DECORATIONS_ENABLED] ?: false }

}
