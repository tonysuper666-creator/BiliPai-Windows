package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopDynamicCardSettings
import com.android.purebilibili.core.store.DesktopDynamicSettings
import com.android.purebilibili.core.ui.components.AppNativeTabRow
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.components.AppSwitchPreference
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.settings.DesktopDynamicTimelineSettingsFields
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.DYNAMIC_DETAIL_IMAGE_LAYOUT_KEY
import com.bilipai.desktop.ui.DYNAMIC_IMAGE_PREVIEW_TEXT_VISIBLE_KEY
import com.bilipai.desktop.ui.DYNAMIC_PAGE_LAYOUT_DIRECTION_KEY
import com.bilipai.desktop.ui.DYNAMIC_TOP_ACTIONS_COLLAPSED_KEY
import com.bilipai.desktop.ui.DYNAMIC_TOP_BAR_COLLAPSE_ON_SCROLL_KEY
import com.bilipai.desktop.ui.DesktopDynamicPageLayoutDirection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Same Root settings namespace/backing; the original keys/defaults remain in generated source. */
internal fun dynamicIntPreferencesKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->
    (value as? JsonPrimitive)?.intOrNull}
internal class DesktopDynamicPreferenceEditor {
    val values=linkedMapOf<String,JsonElement>()
    operator fun <T> set(key:DesktopPreferenceKey<T>,value:T) {
        values[key.name]=when(value){is Boolean->JsonPrimitive(value);is Int->JsonPrimitive(value)
            is String->JsonPrimitive(value);else->error("Unsupported original dynamic preference value")}
    }
}
internal class DesktopDynamicSettingsDataStore(private val context:DesktopPluginContext) {
    val data:StateFlow<DesktopPreferenceSnapshot> get()=context.store.snapshot("settings")
    suspend fun edit(block:(DesktopDynamicPreferenceEditor)->Unit)=withContext(Dispatchers.IO) {
        val editor=DesktopDynamicPreferenceEditor().apply(block)
        context.store.requireObjectNamespace("settings")
        context.store.update("settings",editor.values)
    }
}
internal val DesktopPluginContext.dynamicSettingsDataStore get()=DesktopDynamicSettingsDataStore(this)

/**
 * Global dynamic-page chrome. Original key strings live in the settings namespace.
 * Image preview and detail layout go through the generated card settings so the
 * card, preview dialog, and detail screen read the same values.
 */
internal class DesktopDynamicPageChromePreferences(val context: DesktopPluginContext) {
    init { context.store.requireObjectNamespace("settings") }

    val imagePreviewTextVisible: Flow<Boolean> =
        DesktopDynamicCardSettings.getDynamicImagePreviewTextVisible(context)
    val detailImageLayout: Flow<DesktopDynamicCardSettings.DynamicDetailImageLayout> =
        DesktopDynamicCardSettings.getDynamicDetailImageLayout(context)
    val topBarCollapseOnScroll: Flow<Boolean> = context.dynamicSettingsDataStore.data.map { snapshot ->
        snapshot[booleanPreferencesKey(DYNAMIC_TOP_BAR_COLLAPSE_ON_SCROLL_KEY)] ?: false
    }
    val topActionsCollapsed: Flow<Boolean> = context.dynamicSettingsDataStore.data.map { snapshot ->
        snapshot[booleanPreferencesKey(DYNAMIC_TOP_ACTIONS_COLLAPSED_KEY)] ?: false
    }
    val pageLayoutDirection: Flow<DesktopDynamicPageLayoutDirection> =
        context.dynamicSettingsDataStore.data.map { snapshot ->
            DesktopDynamicPageLayoutDirection.fromValue(
                snapshot[dynamicIntPreferencesKey(DYNAMIC_PAGE_LAYOUT_DIRECTION_KEY)] ?: 0,
            )
        }

    fun peekImagePreviewTextVisible(): Boolean =
        settingsBoolean(DYNAMIC_IMAGE_PREVIEW_TEXT_VISIBLE_KEY, true)

    fun peekDetailImageLayout(): DesktopDynamicCardSettings.DynamicDetailImageLayout {
        val stored = settingsInt(DYNAMIC_DETAIL_IMAGE_LAYOUT_KEY)
        return if (stored != null) DesktopDynamicCardSettings.DynamicDetailImageLayout.fromValue(stored)
        else DesktopDynamicCardSettings.peekDynamicDetailImageLayout(context)
    }

    fun peekTopBarCollapseOnScroll(): Boolean =
        settingsBoolean(DYNAMIC_TOP_BAR_COLLAPSE_ON_SCROLL_KEY, false)

    fun peekTopActionsCollapsed(): Boolean =
        settingsBoolean(DYNAMIC_TOP_ACTIONS_COLLAPSED_KEY, false)

    fun peekPageLayoutDirection(): DesktopDynamicPageLayoutDirection =
        DesktopDynamicPageLayoutDirection.fromValue(settingsInt(DYNAMIC_PAGE_LAYOUT_DIRECTION_KEY) ?: 0)

    suspend fun setImagePreviewTextVisible(value: Boolean) =
        DesktopDynamicCardSettings.setDynamicImagePreviewTextVisible(context, value)

    suspend fun setDetailImageLayout(layout: DesktopDynamicCardSettings.DynamicDetailImageLayout) =
        DesktopDynamicCardSettings.setDynamicDetailImageLayout(context, layout)

    suspend fun setTopBarCollapseOnScroll(value: Boolean) =
        write(DYNAMIC_TOP_BAR_COLLAPSE_ON_SCROLL_KEY, JsonPrimitive(value))

    suspend fun setTopActionsCollapsed(value: Boolean) =
        write(DYNAMIC_TOP_ACTIONS_COLLAPSED_KEY, JsonPrimitive(value))

    suspend fun setPageLayoutDirection(direction: DesktopDynamicPageLayoutDirection) =
        write(DYNAMIC_PAGE_LAYOUT_DIRECTION_KEY, JsonPrimitive(direction.value))

    private fun settingsSnapshot() = DesktopPreferenceSnapshot(context.store.preferences("settings"))

    private fun settingsBoolean(name: String, fallback: Boolean): Boolean =
        settingsSnapshot()[booleanPreferencesKey(name)] ?: fallback

    private fun settingsInt(name: String): Int? =
        settingsSnapshot()[dynamicIntPreferencesKey(name)]

    private suspend fun write(name: String, value: JsonElement) {
        context.dynamicSettingsDataStore.edit { editor ->
            editor.values[name] = value
        }
    }
}

class DesktopDynamicTimelinePreferences(val context:DesktopPluginContext) {
    init {context.store.requireObjectNamespace("settings")}
    val incrementalRefresh:Flow<Boolean> = DesktopDynamicSettings.getIncrementalTimelineRefresh(context)
    val layoutMode:Flow<DesktopDynamicSettings.DynamicFeedLayoutMode> = DesktopDynamicSettings.getDynamicFeedLayoutMode(context)
    suspend fun setIncrementalRefresh(value:Boolean)=DesktopDynamicSettings.setIncrementalTimelineRefresh(context,value)
    suspend fun setLayoutMode(value:DesktopDynamicSettings.DynamicFeedLayoutMode)=DesktopDynamicSettings.setDynamicFeedLayoutMode(context,value)
}

/** Root must provide its existing global store instance. No guest/account fallback store is created. */
val LocalDesktopDynamicTimelinePreferences=staticCompositionLocalOf<DesktopDynamicTimelinePreferences?>{null}

@Composable
internal fun DesktopDynamicTimelineSettings(preferences:DesktopDynamicTimelinePreferences,onFailure:(Throwable)->Unit,modifier:Modifier=Modifier) {
    val incremental by preferences.incrementalRefresh.collectAsState(false)
    val layout by preferences.layoutMode.collectAsState(DesktopDynamicSettings.DynamicFeedLayoutMode.WATERFALL)
    val scope=rememberCoroutineScope();val writer=remember(preferences){Mutex()};val currentFailure by rememberUpdatedState(onFailure)
    fun write(operation:suspend ()->Unit) {scope.launch {
        try {writer.withLock{operation()}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){currentFailure(failure)}
    }}
    Column(modifier) {
        DesktopDynamicTimelineSettingsFields(incremental,{value->write{preferences.setIncrementalRefresh(value)}},
            layout,{value->write{preferences.setLayoutMode(value)}})
        val tabs=remember(preferences){DesktopDynamicTabsPreferences(preferences.context)}
        DesktopDynamicTabsSettings(tabs,onFailure)
        val chrome=remember(preferences){DesktopDynamicPageChromePreferences(preferences.context)}
        DesktopDynamicPageChromeSettings(chrome,onFailure)
    }
}

@Composable
internal fun DesktopDynamicPageChromeSettings(
    preferences: DesktopDynamicPageChromePreferences,
    onFailure: (Throwable) -> Unit,
) {
    val previewVisible by preferences.imagePreviewTextVisible.collectAsState(preferences.peekImagePreviewTextVisible())
    val detailLayout by preferences.detailImageLayout.collectAsState(preferences.peekDetailImageLayout())
    val collapseOnScroll by preferences.topBarCollapseOnScroll.collectAsState(preferences.peekTopBarCollapseOnScroll())
    val actionsCollapsed by preferences.topActionsCollapsed.collectAsState(preferences.peekTopActionsCollapsed())
    val direction by preferences.pageLayoutDirection.collectAsState(preferences.peekPageLayoutDirection())
    val scope = rememberCoroutineScope()
    val writer = remember(preferences) { Mutex() }
    val currentFailure by rememberUpdatedState(onFailure)
    fun write(operation: suspend () -> Unit) {
        scope.launch {
            try { writer.withLock { operation() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { currentFailure(failure) }
        }
    }
    AppSwitchPreference(
        title = "动态图片默认显示文字",
        subtitle = "打开图文动态图片时默认显示下方文字，可用右上角眼睛临时切换",
        checked = previewVisible,
        onCheckedChange = { value -> write { preferences.setImagePreviewTextVisible(value) } },
    )
    AppText("动态详情图片展示", Modifier.padding(top = 12.dp))
    AppText("缩略图可更快看到评论；详情页右上角可临时切换", Modifier.padding(bottom = 4.dp))
    AppNativeTabRow(
        options = DesktopDynamicCardSettings.DynamicDetailImageLayout.entries.map { layout ->
            AppSegmentOption(layout, layout.label)
        },
        selectedValue = detailLayout,
        onSelectionChange = { layout -> write { preferences.setDetailImageLayout(layout) } },
    )
    AppSwitchPreference(
        title = "浏览动态时收起顶部栏",
        subtitle = if (collapseOnScroll) {
            "列表下滑时折叠 Tab 顶栏；横向 UP 主栏仍会单独收起"
        } else {
            "Tab 顶栏固定在顶部；横向 UP 主栏仍会单独收起"
        },
        checked = collapseOnScroll,
        onCheckedChange = { value -> write { preferences.setTopBarCollapseOnScroll(value) } },
    )
    AppSwitchPreference(
        title = "折叠顶部操作",
        subtitle = "收起布局菜单和发布按钮，冷启动后保持",
        checked = actionsCollapsed,
        onCheckedChange = { value -> write { preferences.setTopActionsCollapsed(value) } },
    )
    AppText("动态页布局方向", Modifier.padding(top = 12.dp))
    AppText(
        "左侧保持现有侧栏、顶部 UP 顺序和详情分栏。右侧把侧栏和抽屉换边；在顶部横条或大屏分栏下镜像 UP 顺序、把顶栏操作放到起始侧，并把详情卡片放到右侧。",
        Modifier.padding(bottom = 4.dp),
    )
    AppNativeTabRow(
        options = DesktopDynamicPageLayoutDirection.entries.map { item -> AppSegmentOption(item, item.label) },
        selectedValue = direction,
        onSelectionChange = { value -> write { preferences.setPageLayoutDirection(value) } },
    )
}
