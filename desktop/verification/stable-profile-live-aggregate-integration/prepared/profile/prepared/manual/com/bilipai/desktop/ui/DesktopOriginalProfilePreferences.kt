package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.appendSplashWallpaperHistory
import com.android.purebilibili.core.store.decodeSplashWallpaperHistory
import com.android.purebilibili.core.store.encodeSplashWallpaperHistory
import com.android.purebilibili.core.ui.wallpaper.ProfileWallpaperTransform
import com.android.purebilibili.core.ui.wallpaper.sanitizeProfileWallpaperTransform
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Windows storage seam for original Profile/Splash/Privacy/Triple keys in SettingsManager.
 * No new backing/store/namespace is created: constructor MUST receive the actual Root store.
 * Same entry/epoch admission encloses every original read-dependent mutation and sync cache.
 * Theme delegates the required existing Root original theme setter (including dark-style
 * migration, theme_cache and live theme update), rather than writing a partial new theme model. */
internal class DesktopOriginalProfilePreferences(
    private val store: DesktopPluginStore,
    private val owns: () -> Boolean,
    private val withOwnedAdmission: ((() -> Unit) -> Boolean),
    private val setActualOriginalTheme: suspend (AppThemeMode) -> Unit,
) : DesktopProfilePreferences {
    init { for (name in listOf("settings", "privacy_mode", "splash_prefs")) store.requireObjectNamespace(name) }
    private val values = store.snapshot("settings")
    private fun fkey(name: String) = DesktopPreferenceKey(name) { (it as? JsonPrimitive)?.floatOrNull }
    private fun <T> read(block: (DesktopPreferenceSnapshot) -> T): Flow<T> = values.map(block).distinctUntilChanged()
    private fun ensureOwned() { if (!owns()) throw CancellationException("Profile settings owner retired") }
    private suspend fun edit(block: () -> Unit) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); ensureOwned()
        if (!withOwnedAdmission(block)) throw CancellationException("Profile settings owner retired")
        currentCoroutineContext().ensureActive(); ensureOwned()
    }
    private suspend fun write(key: String, value: JsonElement?) = edit { store.update("settings", mapOf(key to value)) }
    override fun getPrivacyModeEnabled() = read { it[booleanPreferencesKey("privacy_mode_enabled")] ?: false }
    override fun isPrivacyModeEnabledSync() = store.snapshot("privacy_mode").value[booleanPreferencesKey("enabled")] ?: false
    override suspend fun setPrivacyModeEnabled(enabled: Boolean) = edit {
        store.update("settings", mapOf("privacy_mode_enabled" to JsonPrimitive(enabled)))
        store.update("privacy_mode", mapOf("enabled" to JsonPrimitive(enabled)))
    }
    override suspend fun setThemeMode(mode: AppThemeMode) {
        currentCoroutineContext().ensureActive(); ensureOwned(); setActualOriginalTheme(mode)
        currentCoroutineContext().ensureActive(); ensureOwned()
    }
    override fun getShowProfileEditButton() = read { it[booleanPreferencesKey("show_profile_edit_button")] ?: false }
    override fun getProfileBgUri() = read { it[stringPreferencesKey("profile_bg_uri")] }
    override suspend fun setProfileBgUri(uri: String?) = write("profile_bg_uri", uri?.let(::JsonPrimitive))
    override fun getProfileBgAlignment(isTablet: Boolean) = read {
        it[fkey(if (isTablet) "profile_bg_alignment_tablet" else "profile_bg_alignment_mobile")] ?: 0f
    }
    override fun getProfileBgTransform(isTablet: Boolean) = read { preferences ->
        sanitizeProfileWallpaperTransform(ProfileWallpaperTransform(
            scale = if (isTablet) preferences[fkey("profile_bg_scale_tablet")] ?: 1f else preferences[fkey("profile_bg_scale_mobile")] ?: 1f,
            offsetX = if (isTablet) preferences[fkey("profile_bg_offset_x_tablet")] ?: 0f else preferences[fkey("profile_bg_offset_x_mobile")] ?: 0f,
            offsetY = if (isTablet) preferences[fkey("profile_bg_alignment_tablet")] ?: 0f else preferences[fkey("profile_bg_alignment_mobile")] ?: 0f,
        ))
    }
    override suspend fun setProfileBgTransform(isTablet: Boolean, transform: ProfileWallpaperTransform) {
        val safeTransform = sanitizeProfileWallpaperTransform(transform)
        edit {
            val scaleKey = if (isTablet) "profile_bg_scale_tablet" else "profile_bg_scale_mobile"
            val offsetXKey = if (isTablet) "profile_bg_offset_x_tablet" else "profile_bg_offset_x_mobile"
            val offsetYKey = if (isTablet) "profile_bg_alignment_tablet" else "profile_bg_alignment_mobile"
            store.update("settings", mapOf(scaleKey to JsonPrimitive(safeTransform.scale),
                offsetXKey to JsonPrimitive(safeTransform.offsetX), offsetYKey to JsonPrimitive(safeTransform.offsetY)))
        }
    }
    override suspend fun resetProfileBgTransform() = edit {
        store.update("settings", listOf("profile_bg_scale_mobile", "profile_bg_scale_tablet", "profile_bg_offset_x_mobile",
            "profile_bg_offset_x_tablet", "profile_bg_alignment_mobile", "profile_bg_alignment_tablet").associateWith { null })
    }
    override fun getSplashAlignment(isTablet: Boolean) = read {
        it[fkey(if (isTablet) "splash_alignment_tablet" else "splash_alignment_mobile")] ?: 0f
    }
    override suspend fun setSplashAlignment(isTablet: Boolean, bias: Float) = edit {
        val coerced = bias.coerceIn(-1f, 1f)
        val key = if (isTablet) "splash_alignment_tablet" else "splash_alignment_mobile"
        val prefsKey = if (isTablet) "alignment_tablet" else "alignment_mobile"
        store.update("settings", mapOf(key to JsonPrimitive(coerced)))
        store.update("splash_prefs", mapOf(prefsKey to JsonPrimitive(coerced)))
    }
    override suspend fun setSplashWallpaperUri(uri: String) = edit {
        var encodedHistory = ""
        store.updateFromSnapshot("settings") { preferences ->
            val existingHistory = decodeSplashWallpaperHistory(preferences[stringPreferencesKey("splash_wallpaper_history")] ?: "")
            val updatedHistory = appendSplashWallpaperHistory(existingHistory, uri)
            encodedHistory = encodeSplashWallpaperHistory(updatedHistory)
            mapOf("splash_wallpaper_uri" to JsonPrimitive(uri), "splash_wallpaper_history" to JsonPrimitive(encodedHistory))
        }
        store.update("splash_prefs", mapOf("wallpaper_uri" to JsonPrimitive(uri), "wallpaper_history" to JsonPrimitive(encodedHistory)))
    }
    override suspend fun setSplashEnabled(enabled: Boolean) = edit {
        store.update("settings", mapOf("splash_enabled" to JsonPrimitive(enabled)))
        store.update("splash_prefs", mapOf("enabled" to JsonPrimitive(enabled)))
    }
    override suspend fun setSplashRandomEnabled(enabled: Boolean) = edit {
        store.update("settings", mapOf("splash_random_enabled" to JsonPrimitive(enabled)))
        store.update("splash_prefs", mapOf("random_enabled" to JsonPrimitive(enabled)))
    }
    override suspend fun setHomeWallpaperUri(uri: String) = write("home_wallpaper_uri", JsonPrimitive(uri))
    override fun getTripleJumpEnabled() = read { it[booleanPreferencesKey("triple_jump_enabled")] ?: false }
    override suspend fun setTripleJumpEnabled(enabled: Boolean) = write("triple_jump_enabled", JsonPrimitive(enabled))
}
