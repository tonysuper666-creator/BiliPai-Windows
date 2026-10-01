package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.video.danmaku.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal class DesktopOriginalDanmakuPreferences(
    private val store:DesktopPluginStore,
    val blocks:DesktopDanmakuBlockPreferences,
    private val withOwnedAdmission:((()->Unit)->Boolean),
) {
    init {store.requireObjectNamespace("settings")}
    private val DANMAKU_DEFAULTS_VERSION=5
    private val KEY_DANMAKU_CLOUD_SYNC_ENABLED=booleanPreferencesKey("danmaku_cloud_sync_enabled")
    private fun floatPreferencesKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->(value as? JsonPrimitive)?.floatOrNull}
    private fun intPreferencesKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->(value as? JsonPrimitive)?.intOrNull}
    private class Editor {
        val values=linkedMapOf<String,JsonElement?>()
        operator fun <T> set(key:DesktopPreferenceKey<T>,value:T){values[key.name]=when(value){is String->JsonPrimitive(value);is Boolean->JsonPrimitive(value);is Number->JsonPrimitive(value);else->error("Unsupported original danmaku value")}}
        operator fun <T> get(key:DesktopPreferenceKey<T>):T?=before[key]
        lateinit var before:DesktopPreferenceSnapshot
    }
    private suspend fun writeOriginal(block:(Editor)->Unit) {
        val caller=currentCoroutineContext()
        withContext(Dispatchers.IO) {
            if(!withOwnedAdmission { store.updateFromSnapshot("settings") { snapshot ->
                caller.ensureActive();val editor=Editor();editor.before=snapshot;block(editor);editor.values
            } }) throw CancellationException("Danmaku settings owner retired")
        }
    }
    fun currentSettings(scope:DanmakuSettingsScope)=mapDanmakuSettingsFromPreferences(store.snapshot("settings").value,scope)
    suspend fun setDanmakuBlockRulesRaw(value:String,scope:DanmakuSettingsScope)=blocks.setDanmakuBlockRulesRaw(value,scope)
    internal suspend fun migrateMissingOriginalLegacyValues(values:Map<String,JsonElement>) {
        writeOriginal { editor -> values.forEach { (name,value) ->
            val key=DesktopPreferenceKey<JsonElement>(name){it}
            if(editor[key]==null)editor.values[name]=value
        } }
    }
    private companion object {
    private const val DEFAULT_DANMAKU_OPACITY = DANMAKU_DEFAULT_OPACITY
    private const val DEFAULT_DANMAKU_FONT_SCALE = 1.0f
    private const val DEFAULT_DANMAKU_SPEED = 1.0f
    private const val DEFAULT_DANMAKU_AREA = 0.5f
    private const val DEFAULT_DANMAKU_FONT_WEIGHT = 5
    private const val DEFAULT_DANMAKU_STROKE_WIDTH = 1.5f
    private const val DEFAULT_DANMAKU_LINE_HEIGHT = 1.6f
    private const val DEFAULT_DANMAKU_SCROLL_DURATION_SECONDS = 7.0f
    private const val DEFAULT_DANMAKU_STATIC_DURATION_SECONDS = 4.0f
    private const val DEFAULT_DANMAKU_DUPLICATE_MERGE_WINDOW_MS = 500
    private const val DEFAULT_DANMAKU_DUPLICATE_MERGE_COUNT_THRESHOLD = 2
    }

    private fun normalizeDanmakuFontWeight(value: Int?): Int {
        return (value ?: DEFAULT_DANMAKU_FONT_WEIGHT).coerceIn(1, 9)
    }

    private fun normalizeDanmakuStrokeWidth(value: Float?): Float {
        val raw = value ?: DEFAULT_DANMAKU_STROKE_WIDTH
        if (!raw.isFinite()) return DEFAULT_DANMAKU_STROKE_WIDTH
        return raw.coerceIn(0f, 5f)
    }

    private fun normalizeDanmakuLineHeight(value: Float?): Float {
        val raw = value ?: DEFAULT_DANMAKU_LINE_HEIGHT
        if (!raw.isFinite()) return DEFAULT_DANMAKU_LINE_HEIGHT
        return raw.coerceIn(1.0f, 3.0f)
    }

    private fun normalizeDanmakuScrollDurationSeconds(value: Float?): Float {
        val raw = value ?: DEFAULT_DANMAKU_SCROLL_DURATION_SECONDS
        if (!raw.isFinite()) return DEFAULT_DANMAKU_SCROLL_DURATION_SECONDS
        return raw.coerceIn(1.0f, 50.0f)
    }

    private fun normalizeDanmakuStaticDurationSeconds(value: Float?): Float {
        val raw = value ?: DEFAULT_DANMAKU_STATIC_DURATION_SECONDS
        if (!raw.isFinite()) return DEFAULT_DANMAKU_STATIC_DURATION_SECONDS
        return raw.coerceIn(1.0f, 50.0f)
    }

    private fun normalizeDanmakuDuplicateMergeWindowMs(value: Int?): Int {
        val raw = value ?: DEFAULT_DANMAKU_DUPLICATE_MERGE_WINDOW_MS
        return raw.coerceIn(100, 3000)
    }

    private fun normalizeDanmakuDuplicateMergeCountThreshold(value: Int?): Int {
        val raw = value ?: DEFAULT_DANMAKU_DUPLICATE_MERGE_COUNT_THRESHOLD
        return raw.coerceIn(2, 10)
    }

    private fun buildScopedDanmakuKeyName(
        scope: DanmakuSettingsScope,
        suffix: String
    ): String {
        // Keep the existing fullscreen values authoritative across playback modes.
        val shared = suffix == "enabled" || suffix == "font_scale" || suffix == "area"
        val prefix = if (shared) DanmakuSettingsScope.LANDSCAPE.keyPrefix else scope.keyPrefix
        return "danmaku_${prefix}_$suffix"
    }
    
    private val KEY_DANMAKU_ENABLED = booleanPreferencesKey("danmaku_enabled")
    private val KEY_DANMAKU_OPACITY = floatPreferencesKey("danmaku_opacity")
    private val KEY_DANMAKU_FONT_SCALE = floatPreferencesKey("danmaku_font_scale")
    private val KEY_DANMAKU_SPEED = floatPreferencesKey("danmaku_speed")
    private val KEY_DANMAKU_AREA = floatPreferencesKey("danmaku_area")
    private val KEY_DANMAKU_FONT_WEIGHT = intPreferencesKey("danmaku_font_weight")
    private val KEY_DANMAKU_STROKE_WIDTH = floatPreferencesKey("danmaku_stroke_width")
    private val KEY_DANMAKU_LINE_HEIGHT = floatPreferencesKey("danmaku_line_height")
    private val KEY_DANMAKU_SCROLL_DURATION_SECONDS =
        floatPreferencesKey("danmaku_scroll_duration_seconds")
    private val KEY_DANMAKU_STATIC_DURATION_SECONDS =
        floatPreferencesKey("danmaku_static_duration_seconds")
    private val KEY_DANMAKU_SCROLL_FIXED_VELOCITY =
        booleanPreferencesKey("danmaku_scroll_fixed_velocity")
    private val KEY_DANMAKU_STATIC_TO_SCROLL =
        booleanPreferencesKey("danmaku_static_to_scroll")
    private val KEY_DANMAKU_MASSIVE_MODE = booleanPreferencesKey("danmaku_massive_mode")
    private val KEY_DANMAKU_ALLOW_SCROLL = booleanPreferencesKey("danmaku_allow_scroll")
    private val KEY_DANMAKU_ALLOW_TOP = booleanPreferencesKey("danmaku_allow_top")
    private val KEY_DANMAKU_ALLOW_BOTTOM = booleanPreferencesKey("danmaku_allow_bottom")
    private val KEY_DANMAKU_ALLOW_COLORFUL = booleanPreferencesKey("danmaku_allow_colorful")
    private val KEY_DANMAKU_ALLOW_SPECIAL = booleanPreferencesKey("danmaku_allow_special")
    private val KEY_DANMAKU_BLOCK_ATTENTION_COMMANDS =
        booleanPreferencesKey("danmaku_block_attention_commands")
    private val KEY_DANMAKU_SMART_OCCLUSION = booleanPreferencesKey("danmaku_smart_occlusion")
    private val KEY_DANMAKU_WEIGHT_FILTER_LEVEL = intPreferencesKey("danmaku_weight_filter_level")
    private val KEY_DANMAKU_FULLSCREEN_PANEL_WIDTH_MODE =
        intPreferencesKey("danmaku_fullscreen_panel_width_mode")
    private val KEY_DANMAKU_BLOCK_RULES = stringPreferencesKey("danmaku_block_rules")
    private val KEY_DANMAKU_MERGE_DUPLICATES = booleanPreferencesKey("danmaku_merge_duplicates")
    private val KEY_DANMAKU_DUPLICATE_MERGE_WINDOW_MS =
        intPreferencesKey("danmaku_duplicate_merge_window_ms")
    private val KEY_DANMAKU_DUPLICATE_MERGE_COUNT_THRESHOLD =
        intPreferencesKey("danmaku_duplicate_merge_count_threshold")
    private val KEY_DANMAKU_SEND_COLOR = intPreferencesKey("danmaku_send_color")
    private val KEY_DANMAKU_SEND_MODE = intPreferencesKey("danmaku_send_mode")
    private val KEY_DANMAKU_SEND_FONT_SIZE = intPreferencesKey("danmaku_send_font_size")
    private val KEY_DANMAKU_DEFAULTS_VERSION = intPreferencesKey("danmaku_defaults_version")
    private val KEY_HOME_VISUAL_DEFAULTS_VERSION = intPreferencesKey("home_visual_defaults_version")

    private fun keyDanmakuEnabled(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "enabled"))
    private fun keyDanmakuOpacity(scope: DanmakuSettingsScope) =
        floatPreferencesKey(buildScopedDanmakuKeyName(scope, "opacity"))
    private fun keyDanmakuFontScale(scope: DanmakuSettingsScope) =
        floatPreferencesKey(buildScopedDanmakuKeyName(scope, "font_scale"))
    private fun keyDanmakuSpeed(scope: DanmakuSettingsScope) =
        floatPreferencesKey(buildScopedDanmakuKeyName(scope, "speed"))
    private fun keyDanmakuArea(scope: DanmakuSettingsScope) =
        floatPreferencesKey(buildScopedDanmakuKeyName(scope, "area"))
    private fun keyDanmakuPortraitDisplayAreaMode() =
        intPreferencesKey(
            buildScopedDanmakuKeyName(DanmakuSettingsScope.PORTRAIT, "display_area_mode")
        )
    private fun keyDanmakuFontWeight(scope: DanmakuSettingsScope) =
        intPreferencesKey(buildScopedDanmakuKeyName(scope, "font_weight"))
    private fun keyDanmakuStrokeWidth(scope: DanmakuSettingsScope) =
        floatPreferencesKey(buildScopedDanmakuKeyName(scope, "stroke_width"))
    private fun keyDanmakuLineHeight(scope: DanmakuSettingsScope) =
        floatPreferencesKey(buildScopedDanmakuKeyName(scope, "line_height"))
    private fun keyDanmakuScrollDurationSeconds(scope: DanmakuSettingsScope) =
        floatPreferencesKey(buildScopedDanmakuKeyName(scope, "scroll_duration_seconds"))
    private fun keyDanmakuLegacyPortraitEnabled() =
        booleanPreferencesKey("danmaku_portrait_enabled")
    private fun keyDanmakuLegacyPortraitFontScale() =
        floatPreferencesKey("danmaku_portrait_font_scale")
    private fun keyDanmakuLegacyPortraitArea() =
        floatPreferencesKey("danmaku_portrait_area")

    private fun <T> readSharedDanmakuPreference(
        preferences: DesktopPreferenceSnapshot,
        scopeKey: DesktopPreferenceKey<T>,
        legacyPortraitKey: DesktopPreferenceKey<T>,
        legacyKey: DesktopPreferenceKey<T>,
        defaultValue: T
    ): T {
        return preferences[scopeKey]
            ?: preferences[legacyPortraitKey]
            ?: preferences[legacyKey]
            ?: defaultValue
    }

    private fun keyDanmakuStaticDurationSeconds(scope: DanmakuSettingsScope) =
        floatPreferencesKey(buildScopedDanmakuKeyName(scope, "static_duration_seconds"))
    private fun keyDanmakuScrollFixedVelocity(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "scroll_fixed_velocity"))
    private fun keyDanmakuStaticToScroll(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "static_to_scroll"))
    private fun keyDanmakuMassiveMode(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "massive_mode"))
    private fun keyDanmakuAllowScroll(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "allow_scroll"))
    private fun keyDanmakuAllowTop(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "allow_top"))
    private fun keyDanmakuAllowBottom(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "allow_bottom"))
    private fun keyDanmakuAllowColorful(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "allow_colorful"))
    private fun keyDanmakuAllowSpecial(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "allow_special"))
    private fun keyDanmakuSmartOcclusion(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "smart_occlusion"))
    private fun keyDanmakuBlockRules(scope: DanmakuSettingsScope) =
        stringPreferencesKey(buildScopedDanmakuKeyName(scope, "block_rules"))
    private fun keyDanmakuMergeDuplicates(scope: DanmakuSettingsScope) =
        booleanPreferencesKey(buildScopedDanmakuKeyName(scope, "merge_duplicates"))
    private fun keyDanmakuDuplicateMergeWindowMs(scope: DanmakuSettingsScope) =
        intPreferencesKey(buildScopedDanmakuKeyName(scope, "duplicate_merge_window_ms"))
    private fun keyDanmakuDuplicateMergeCountThreshold(scope: DanmakuSettingsScope) =
        intPreferencesKey(buildScopedDanmakuKeyName(scope, "duplicate_merge_count_threshold"))

    private fun <T> readScopedDanmakuPreference(
        preferences: DesktopPreferenceSnapshot,
        scopeKey: DesktopPreferenceKey<T>,
        legacyKey: DesktopPreferenceKey<T>,
        defaultValue: T
    ): T {
        return preferences[scopeKey] ?: preferences[legacyKey] ?: defaultValue
    }

    internal fun mapDanmakuSettingsFromPreferences(
        preferences: DesktopPreferenceSnapshot,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ): DanmakuSettings {
        val blockRulesRaw = readScopedDanmakuPreference(
            preferences = preferences,
            scopeKey = keyDanmakuBlockRules(scope),
            legacyKey = KEY_DANMAKU_BLOCK_RULES,
            defaultValue = ""
        )
        return DanmakuSettings(
            enabled = readSharedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuEnabled(scope),
                legacyPortraitKey = keyDanmakuLegacyPortraitEnabled(),
                legacyKey = KEY_DANMAKU_ENABLED,
                defaultValue = true
            ),
            opacity = normalizeDanmakuOpacity(
                readScopedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuOpacity(scope),
                    legacyKey = KEY_DANMAKU_OPACITY,
                    defaultValue = DEFAULT_DANMAKU_OPACITY
                )
            ),
            fontScale = normalizeDanmakuFontScale(
                readSharedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuFontScale(scope),
                    legacyPortraitKey = keyDanmakuLegacyPortraitFontScale(),
                    legacyKey = KEY_DANMAKU_FONT_SCALE,
                    defaultValue = DEFAULT_DANMAKU_FONT_SCALE
                )
            ),
            speed = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuSpeed(scope),
                legacyKey = KEY_DANMAKU_SPEED,
                defaultValue = DEFAULT_DANMAKU_SPEED
            ),
            displayArea = normalizeDanmakuDisplayArea(
                readSharedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuArea(scope),
                    legacyPortraitKey = keyDanmakuLegacyPortraitArea(),
                    legacyKey = KEY_DANMAKU_AREA,
                    defaultValue = DEFAULT_DANMAKU_AREA
                )
            ),
            fontWeight = normalizeDanmakuFontWeight(
                readScopedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuFontWeight(scope),
                    legacyKey = KEY_DANMAKU_FONT_WEIGHT,
                    defaultValue = DEFAULT_DANMAKU_FONT_WEIGHT
                )
            ),
            strokeWidth = normalizeDanmakuStrokeWidth(
                readScopedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuStrokeWidth(scope),
                    legacyKey = KEY_DANMAKU_STROKE_WIDTH,
                    defaultValue = DEFAULT_DANMAKU_STROKE_WIDTH
                )
            ),
            lineHeight = normalizeDanmakuLineHeight(
                readScopedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuLineHeight(scope),
                    legacyKey = KEY_DANMAKU_LINE_HEIGHT,
                    defaultValue = DEFAULT_DANMAKU_LINE_HEIGHT
                )
            ),
            scrollDurationSeconds = normalizeDanmakuScrollDurationSeconds(
                readScopedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuScrollDurationSeconds(scope),
                    legacyKey = KEY_DANMAKU_SCROLL_DURATION_SECONDS,
                    defaultValue = DEFAULT_DANMAKU_SCROLL_DURATION_SECONDS
                )
            ),
            staticDurationSeconds = normalizeDanmakuStaticDurationSeconds(
                readScopedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuStaticDurationSeconds(scope),
                    legacyKey = KEY_DANMAKU_STATIC_DURATION_SECONDS,
                    defaultValue = DEFAULT_DANMAKU_STATIC_DURATION_SECONDS
                )
            ),
            scrollFixedVelocity = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuScrollFixedVelocity(scope),
                legacyKey = KEY_DANMAKU_SCROLL_FIXED_VELOCITY,
                defaultValue = false
            ),
            staticDanmakuToScroll = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuStaticToScroll(scope),
                legacyKey = KEY_DANMAKU_STATIC_TO_SCROLL,
                defaultValue = false
            ),
            massiveMode = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuMassiveMode(scope),
                legacyKey = KEY_DANMAKU_MASSIVE_MODE,
                defaultValue = false
            ),
            mergeDuplicates = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuMergeDuplicates(scope),
                legacyKey = KEY_DANMAKU_MERGE_DUPLICATES,
                defaultValue = true
            ),
            duplicateMergeWindowMs = normalizeDanmakuDuplicateMergeWindowMs(
                readScopedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuDuplicateMergeWindowMs(scope),
                    legacyKey = KEY_DANMAKU_DUPLICATE_MERGE_WINDOW_MS,
                    defaultValue = DEFAULT_DANMAKU_DUPLICATE_MERGE_WINDOW_MS
                )
            ),
            duplicateMergeCountThreshold = normalizeDanmakuDuplicateMergeCountThreshold(
                readScopedDanmakuPreference(
                    preferences = preferences,
                    scopeKey = keyDanmakuDuplicateMergeCountThreshold(scope),
                    legacyKey = KEY_DANMAKU_DUPLICATE_MERGE_COUNT_THRESHOLD,
                    defaultValue = DEFAULT_DANMAKU_DUPLICATE_MERGE_COUNT_THRESHOLD
                )
            ),
            allowScroll = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuAllowScroll(scope),
                legacyKey = KEY_DANMAKU_ALLOW_SCROLL,
                defaultValue = true
            ),
            allowTop = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuAllowTop(scope),
                legacyKey = KEY_DANMAKU_ALLOW_TOP,
                defaultValue = true
            ),
            allowBottom = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuAllowBottom(scope),
                legacyKey = KEY_DANMAKU_ALLOW_BOTTOM,
                defaultValue = true
            ),
            allowColorful = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuAllowColorful(scope),
                legacyKey = KEY_DANMAKU_ALLOW_COLORFUL,
                defaultValue = true
            ),
            allowSpecial = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuAllowSpecial(scope),
                legacyKey = KEY_DANMAKU_ALLOW_SPECIAL,
                defaultValue = true
            ),
            weightFilterLevel = (preferences[KEY_DANMAKU_WEIGHT_FILTER_LEVEL] ?: 0).coerceIn(0, 10),
            hideInteractiveCommands = preferences[KEY_DANMAKU_BLOCK_ATTENTION_COMMANDS] ?: false,
            blockAttentionCommands = preferences[KEY_DANMAKU_BLOCK_ATTENTION_COMMANDS] ?: false,
            smartOcclusion = readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuSmartOcclusion(scope),
                legacyKey = KEY_DANMAKU_SMART_OCCLUSION,
                defaultValue = false
            ),
            portraitDisplayAreaMode = if (scope == DanmakuSettingsScope.PORTRAIT) {
                PortraitDanmakuDisplayAreaMode.fromValue(
                    preferences[keyDanmakuPortraitDisplayAreaMode()]
                        ?: PortraitDanmakuDisplayAreaMode.VIDEO_VIEWPORT.value
                )
            } else {
                PortraitDanmakuDisplayAreaMode.VIDEO_VIEWPORT
            },
            fullscreenPanelWidthMode = normalizeDanmakuFullscreenPanelWidthMode(
                DanmakuPanelWidthMode.fromValue(
                    preferences[KEY_DANMAKU_FULLSCREEN_PANEL_WIDTH_MODE]
                        ?: DanmakuPanelWidthMode.THIRD.value
                )
            ),
            blockRulesRaw = blockRulesRaw,
            blockRules = parseDanmakuBlockRules(blockRulesRaw)
        )
    }

    fun getDanmakuSettings(
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ): Flow<DanmakuSettings> {
        return store.snapshot("settings")
            .map { preferences -> mapDanmakuSettingsFromPreferences(preferences, scope) }
            .distinctUntilChanged()
    }

    fun getDanmakuCloudSyncEnabled(): Flow<Boolean> = store.snapshot("settings")
        .map { preferences -> preferences[KEY_DANMAKU_CLOUD_SYNC_ENABLED] ?: true }

    suspend fun setDanmakuEnabled(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuEnabled(scope)] = value
        }
    }

    suspend fun setDanmakuOpacity(
        value: Float,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuOpacity(scope)] = normalizeDanmakuOpacity(value)
        }
    }

    suspend fun setDanmakuFontScale(
        value: Float,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuFontScale(scope)] = normalizeDanmakuFontScale(value)
        }
    }

    suspend fun setDanmakuSpeed(
        value: Float,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuSpeed(scope)] = value.coerceIn(0.5f, 3.0f)
        }
    }

    suspend fun setDanmakuArea(
        value: Float,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuArea(scope)] = normalizeDanmakuDisplayArea(value)
        }
    }

    suspend fun setPortraitDanmakuDisplayAreaMode(
        value: PortraitDanmakuDisplayAreaMode
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuPortraitDisplayAreaMode()] = value.value
        }
    }

    suspend fun setDanmakuFontWeight(
        value: Int,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuFontWeight(scope)] = normalizeDanmakuFontWeight(value)
        }
    }

    suspend fun setDanmakuStrokeWidth(
        value: Float,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuStrokeWidth(scope)] = normalizeDanmakuStrokeWidth(value)
        }
    }

    suspend fun setDanmakuLineHeight(
        value: Float,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuLineHeight(scope)] = normalizeDanmakuLineHeight(value)
        }
    }

    suspend fun setDanmakuScrollDurationSeconds(
        value: Float,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuScrollDurationSeconds(scope)] =
                normalizeDanmakuScrollDurationSeconds(value)
        }
    }

    suspend fun setDanmakuStaticDurationSeconds(
        value: Float,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuStaticDurationSeconds(scope)] =
                normalizeDanmakuStaticDurationSeconds(value)
        }
    }

    suspend fun setDanmakuScrollFixedVelocity(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuScrollFixedVelocity(scope)] = value
        }
    }

    suspend fun setDanmakuStaticToScroll(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuStaticToScroll(scope)] = value
        }
    }

    suspend fun setDanmakuMassiveMode(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuMassiveMode(scope)] = value
        }
    }

    suspend fun setDanmakuAllowScroll(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuAllowScroll(scope)] = value
        }
    }

    suspend fun setDanmakuAllowTop(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuAllowTop(scope)] = value
        }
    }

    suspend fun setDanmakuAllowBottom(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuAllowBottom(scope)] = value
        }
    }

    suspend fun setDanmakuAllowColorful(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuAllowColorful(scope)] = value
        }
    }

    suspend fun setDanmakuAllowSpecial(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuAllowSpecial(scope)] = value
        }
    }

    suspend fun setDanmakuWeightFilterLevel(value: Int) {
        writeOriginal { preferences ->
            preferences[KEY_DANMAKU_WEIGHT_FILTER_LEVEL] = value.coerceIn(0, 10)
        }
    }

    suspend fun setDanmakuHideInteractiveCommands(value: Boolean) {
        writeOriginal { preferences ->
            preferences[KEY_DANMAKU_BLOCK_ATTENTION_COMMANDS] = value
        }
    }

    suspend fun setDanmakuSmartOcclusion(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuSmartOcclusion(scope)] = value
        }
    }

    suspend fun setDanmakuFullscreenPanelWidthMode(
        value: DanmakuPanelWidthMode
    ) {
        writeOriginal { preferences ->
            preferences[KEY_DANMAKU_FULLSCREEN_PANEL_WIDTH_MODE] =
                normalizeDanmakuFullscreenPanelWidthMode(value).value
        }
    }

    suspend fun setDanmakuMergeDuplicates(
        value: Boolean,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences -> 
            preferences[keyDanmakuMergeDuplicates(scope)] = value
        }
    }

    suspend fun setDanmakuDuplicateMergeWindowMs(
        value: Int,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuDuplicateMergeWindowMs(scope)] =
                normalizeDanmakuDuplicateMergeWindowMs(value)
        }
    }

    suspend fun setDanmakuDuplicateMergeCountThreshold(
        value: Int,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        writeOriginal { preferences ->
            preferences[keyDanmakuDuplicateMergeCountThreshold(scope)] =
                normalizeDanmakuDuplicateMergeCountThreshold(value)
        }
    }

    suspend fun setDanmakuCloudSyncEnabled(value: Boolean) {
        writeOriginal { preferences ->
            preferences[KEY_DANMAKU_CLOUD_SYNC_ENABLED] = value
        }
    }

    suspend fun forceDanmakuDefaults() {
        writeOriginal { preferences ->
            val currentVersion = preferences[KEY_DANMAKU_DEFAULTS_VERSION] ?: 0
            if (currentVersion < DANMAKU_DEFAULTS_VERSION) {
                preferences[KEY_DANMAKU_OPACITY] = DEFAULT_DANMAKU_OPACITY
                preferences[KEY_DANMAKU_FONT_SCALE] = DEFAULT_DANMAKU_FONT_SCALE
                preferences[KEY_DANMAKU_SPEED] = DEFAULT_DANMAKU_SPEED
                preferences[KEY_DANMAKU_AREA] = DEFAULT_DANMAKU_AREA
                preferences[KEY_DANMAKU_FONT_WEIGHT] = DEFAULT_DANMAKU_FONT_WEIGHT
                preferences[KEY_DANMAKU_STROKE_WIDTH] = DEFAULT_DANMAKU_STROKE_WIDTH
                preferences[KEY_DANMAKU_LINE_HEIGHT] = DEFAULT_DANMAKU_LINE_HEIGHT
                preferences[KEY_DANMAKU_SCROLL_DURATION_SECONDS] =
                    DEFAULT_DANMAKU_SCROLL_DURATION_SECONDS
                preferences[KEY_DANMAKU_STATIC_DURATION_SECONDS] =
                    DEFAULT_DANMAKU_STATIC_DURATION_SECONDS
                preferences[KEY_DANMAKU_SCROLL_FIXED_VELOCITY] = false
                preferences[KEY_DANMAKU_STATIC_TO_SCROLL] = false
                preferences[KEY_DANMAKU_MASSIVE_MODE] = false
                preferences[KEY_DANMAKU_DUPLICATE_MERGE_WINDOW_MS] =
                    DEFAULT_DANMAKU_DUPLICATE_MERGE_WINDOW_MS
                preferences[KEY_DANMAKU_DUPLICATE_MERGE_COUNT_THRESHOLD] =
                    DEFAULT_DANMAKU_DUPLICATE_MERGE_COUNT_THRESHOLD
                preferences[KEY_DANMAKU_ALLOW_SCROLL] = true
                preferences[KEY_DANMAKU_ALLOW_TOP] = true
                preferences[KEY_DANMAKU_ALLOW_BOTTOM] = true
                preferences[KEY_DANMAKU_ALLOW_COLORFUL] = true
                preferences[KEY_DANMAKU_ALLOW_SPECIAL] = true
                preferences[KEY_DANMAKU_SMART_OCCLUSION] = false
                preferences[KEY_DANMAKU_DEFAULTS_VERSION] = DANMAKU_DEFAULTS_VERSION
            }
        }
    }
}
