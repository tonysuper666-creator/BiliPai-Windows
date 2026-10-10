package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DesktopOriginalHomeSettingsManager
import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.DesktopOriginalTabletAudioSettings
import com.android.purebilibili.core.store.DesktopOriginalPortraitSettings
import com.android.purebilibili.core.store.PlaybackCompletionBehavior
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.android.purebilibili.feature.settings.share.*
import com.bilipai.desktop.ui.DesktopOriginalPlaybackPreferenceOperation
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Callable JSON compatibility backend for the actual Root/page preference context.
 * Original schema/theme migration/section preview are reused unchanged. This does not reset
 * the document, import deviceDebug or touch account data. The existing ZIP backup is separate.
 * UI mounting and Android saved-profile management remain future work.
 */
class DesktopSettingsShareService(private val context: DesktopOriginalPlayerSettingsContext) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /** IO stays outside Root admission; owner and caller are checked before and after it. */
    suspend fun readImportSession(path: Path): SettingsShareImportSession {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        context.requireCurrent()
        val raw = withContext(Dispatchers.IO) {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Settings JSON must be a regular file" }
            require(Files.size(path) <= MAX_JSON_BYTES) { "Settings JSON exceeds the size limit" }
            Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.allocate(MAX_JSON_BYTES + 1)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) caller.ensureActive()
                require(buffer.position() <= MAX_JSON_BYTES) { "Settings JSON exceeds the size limit" }
                buffer.flip()
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString()
            }
        }
        caller.ensureActive()
        context.requireCurrent()
        return readImportSession(raw)
    }

    fun readImportSession(rawJson: String): SettingsShareImportSession {
        context.requireCurrent()
        require(rawJson.length <= MAX_JSON_BYTES && rawJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_JSON_BYTES) {
            "Settings JSON exceeds the size limit"
        }
        val decoded = json.decodeFromString<SettingsShareProfile>(rawJson)
        require(decoded.schemaVersion == SETTINGS_SHARE_SCHEMA_VERSION) { "Unsupported settings-share schema" }
        require(decoded.app == "BiliPai") { "This is not a BiliPai settings-share file" }
        val profile = decoded.copy(sections = normalizeThemeSelectionForImport(decoded.sections))
        // Keep the original flatten order: later sections win when the same key is repeated.
        val accepted = DesktopSettingsShareWindowsCatalog.normalize(flattenSettingsShareSections(profile.sections))
        val preview = resolveSettingsShareImportPreview(profile,
            DesktopSettingsShareWindowsCatalog.definitions.filter { it.storageKey in accepted })
        return SettingsShareImportSession(profile, preview, rawJson)
    }

    /** Keys absent from the allowlist and values rejected by strict type/enum validation are visible in preview. */
    fun inspectImport(session: SettingsShareImportSession): DesktopSettingsShareInspection {
        val verified = verifySession(session)
        val raw = flattenSettingsShareSections(verified.profile.sections)
        val accepted = DesktopSettingsShareWindowsCatalog.normalize(raw)
        return DesktopSettingsShareInspection(verified,
            raw.keys.filterNot(accepted::containsKey).associateWith { key ->
                if (key in DesktopSettingsShareWindowsCatalog.fields) "invalid type or value" else "not mapped for Windows"
            }, accepted.keys.intersect(DesktopSettingsShareWindowsCatalog.baselineDanmakuKeys))
    }

    suspend fun applyImport(session: SettingsShareImportSession): SettingsShareApplyResult {
        currentCoroutineContext().ensureActive()
        val verified = verifySession(session)
        val accepted = DesktopSettingsShareWindowsCatalog.normalize(flattenSettingsShareSections(verified.profile.sections))
        if (accepted.isEmpty()) return SettingsShareApplyResult(emptyList(), verified.preview.skippedKeys)
        // One original journal: all canonical fields and original mirrors replay on each fresh
        // Store CAS snapshot. Disk/fsync happens outside the actual Root/page permit gate.
        DesktopOriginalPlaybackPreferenceOperation.run(context, context::isCurrentForOriginalWrite) {
            context.settingsDataStore.edit { values ->
                values.changes.putAll(accepted)
                if ("theme_selection_v1" in accepted) {
                    values.changes["ui_preset"] = null
                    values.changes["android_native_variant_v1"] = null
                }
                if ("android_native_liquid_glass_enabled" in accepted) {
                    values.changes["windows_liquid_glass_default_v1"] = JsonPrimitive(true)
                }
            }
            // Only imported fields project into theme_cache; no stale captured unrelated values.
            if (listOf("theme_mode_v2", "dark_theme_style_v1", "app_language_v1").any(accepted::containsKey)) {
                context.getSharedPreferences("theme_cache", DesktopOriginalPlayerSettingsContext.MODE_PRIVATE).edit().apply {
                    accepted["theme_mode_v2"]?.let { putInt("theme_mode", it.jsonPrimitive.int) }
                    accepted["dark_theme_style_v1"]?.let { putInt("dark_theme_style", it.jsonPrimitive.int) }
                    accepted["app_language_v1"]?.let { putInt("app_language", it.jsonPrimitive.int) }
                }.apply()
            }
            applyOriginalMirrorSetters(accepted)
        }
        return SettingsShareApplyResult(accepted.keys.sorted(), verified.preview.skippedKeys)
    }

    /** Export only the vetted canonical Windows subset. No device, account, path or cache block is exported. */
    fun buildExportArtifact(profileName: String, appVersion: String, now: Instant = Instant.now()): SettingsShareExportArtifact {
        context.requireCurrent()
        require(profileName.length <= 200)
        require(appVersion.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid app version" }
        val raw = DesktopSettingsShareWindowsCatalog.normalize(context.pluginContext.store.preferences("settings"))
        val profile = buildSettingsShareProfile(profileName, appVersion, now.toString(), raw,
            DesktopSettingsShareWindowsCatalog.definitions)
        context.requireCurrent()
        return SettingsShareExportArtifact(buildSettingsShareFileName(appVersion, now.toEpochMilli()),
            json.encodeToString(profile), profile)
    }

    private fun verifySession(session: SettingsShareImportSession): SettingsShareImportSession {
        val verified = readImportSession(session.rawJson)
        require(verified.profile == session.profile && verified.preview == session.preview) {
            "Settings import session changed after preview"
        }
        return verified
    }

    private suspend fun applyOriginalMirrorSetters(values: Map<String, JsonElement>) {
        values["hw_decode"]?.let { DesktopOriginalPlaybackSettingsPreferences.setHwDecode(context, it.jsonPrimitive.boolean) }
        values["auto_play"]?.let { DesktopOriginalPlaybackSettingsPreferences.setAutoPlay(context, it.jsonPrimitive.boolean) }
        values["playback_completion_behavior"]?.let {
            DesktopOriginalPlaybackSettingsPreferences.setPlaybackCompletionBehavior(context,
                PlaybackCompletionBehavior.fromValue(it.jsonPrimitive.int))
        }
        values["playback_speed_options"]?.let {
            // Reuse the original whole selections/cache recipe on every fresh CAS snapshot.
            // Options are already staged in this same canonical journal, so the original
            // reconciler sees imported options together with current default/last values.
            val preferences = context.settingsDataStore.edit { fresh ->
                DesktopOriginalVideoPlayerSettings.reconcilePlaybackSpeedSelections(fresh)
            }
            DesktopOriginalVideoPlayerSettings.syncPlaybackSpeedCache(context, preferences)
        }
        values["default_playback_speed"]?.let { DesktopOriginalVideoPlayerSettings.setDefaultPlaybackSpeed(context, it.jsonPrimitive.float) }
        values["long_press_speed"]?.let { DesktopOriginalVideoPlayerSettings.setLongPressSpeed(context, it.jsonPrimitive.float) }
        values["remember_last_playback_speed"]?.let { DesktopOriginalVideoPlayerSettings.setRememberLastPlaybackSpeed(context, it.jsonPrimitive.boolean) }
        values["stop_playback_on_exit"]?.let { DesktopOriginalPlaybackSettingsPreferences.setStopPlaybackOnExit(context, it.jsonPrimitive.boolean) }
        values["background_playback_enabled"]?.let { DesktopOriginalPlaybackSettingsPreferences.setBackgroundPlaybackEnabled(context, it.jsonPrimitive.boolean) }
        values["auto_highest_quality"]?.let { DesktopOriginalPlaybackSettingsPreferences.setAutoHighestQuality(context, it.jsonPrimitive.boolean) }
        values["wifi_default_quality"]?.let { DesktopOriginalPlaybackSettingsPreferences.setWifiQuality(context, it.jsonPrimitive.int) }
        values["video_codec_preference"]?.let { DesktopOriginalPlaybackSettingsPreferences.setVideoCodec(context, it.jsonPrimitive.content) }
        values["video_second_codec_preference"]?.let { DesktopOriginalPlaybackSettingsPreferences.setVideoSecondCodec(context, it.jsonPrimitive.content) }
        values["audio_quality_preference"]?.let { DesktopOriginalPortraitSettings.setAudioQuality(context, it.jsonPrimitive.int) }
        values["default_audio_quality"]?.let { DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(context, it.jsonPrimitive.int) }
        values["comment_default_sort_mode"]?.let { DesktopOriginalTabletAudioSettings.setCommentDefaultSortMode(context, it.jsonPrimitive.int) }
        values["show_online_count"]?.let { DesktopOriginalHomeSettingsManager.setShowOnlineCount(context, it.jsonPrimitive.boolean) }
        values["video_note_enabled"]?.let { DesktopOriginalPlaybackSettingsPreferences.setVideoNoteEnabled(context, it.jsonPrimitive.boolean) }
        values["video_note_default_collapsed"]?.let { DesktopOriginalPlaybackSettingsPreferences.setVideoNoteDefaultCollapsed(context, it.jsonPrimitive.boolean) }
        values["resume_playback_prompt_enabled"]?.let { DesktopOriginalPlaybackSettingsPreferences.setResumePlaybackPromptEnabled(context, it.jsonPrimitive.boolean) }
        values["space_played_video_locate_prompt_enabled"]?.let { DesktopOriginalPlaybackSettingsPreferences.setSpacePlayedVideoLocatePromptEnabled(context, it.jsonPrimitive.boolean) }
        values["data_saver_mode"]?.let { DesktopOriginalPlaybackSettingsPreferences.setDataSaverMode(context,
            DesktopOriginalPlaybackSettingsPreferences.DataSaverMode.fromValue(it.jsonPrimitive.int)) }
        // This original reply setter has a PluginContext signature; its mirror is projected into
        // the same journal here rather than starting an independent unguarded store transaction.
        values["comment_collapsed_reply_preview_limit"]?.let {
            context.getSharedPreferences("comment_preview_cache", DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
                .edit().putInt("comment_collapsed_reply_preview_limit", it.jsonPrimitive.int).apply()
        }
    }

    companion object { const val MAX_JSON_BYTES = 1_048_576 }
}

/** The original preview plus honest Windows limitations for a future import UI. */
data class DesktopSettingsShareInspection(
    val session: SettingsShareImportSession,
    val skippedReasons: Map<String, String>,
    /** Original shared defaults only; existing explicit portrait/landscape overrides remain effective. */
    val baselineDanmakuKeys: Set<String>,
)
