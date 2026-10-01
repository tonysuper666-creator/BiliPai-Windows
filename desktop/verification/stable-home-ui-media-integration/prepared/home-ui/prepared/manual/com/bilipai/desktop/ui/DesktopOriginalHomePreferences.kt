package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** A projection of the ONE Root global store. Original decoders and namespace/key values are
 * preserved. Root must retire the supplied window scope before freeze/restore; no account
 * preference file or Home cache is created here. */
internal class DesktopOriginalHomePreferences private constructor(
    private val store:DesktopPluginStore,
    private val scope:CoroutineScope,
    private val defaultTabletUseSidebar:Boolean,
    private val isMobileNetwork:()->Boolean,
):DesktopHomeSettingsPort {
    private val settings=store.snapshot("settings")
    private fun <T> state(read:(DesktopPreferenceSnapshot)->T)=settings.map(read)
        .distinctUntilChanged().stateIn(scope,SharingStarted.Eagerly,read(settings.value))
    override val homeSettings=state(::decodeDesktopOriginalHomeSettings)
    override val topTabs=state(::decodeDesktopOriginalHomeTopTabs)
    override val navigation=state{decodeDesktopOriginalHomeNavigation(it,defaultTabletUseSidebar)}
    override val showOnlineCount=state{it[booleanPreferencesKey("show_online_count")]?:false}
    override val homeFeedCardStyle=state{HomeFeedCardStyle.fromValue(it[favoriteIntKey("home_feed_card_style")]?:HomeFeedCardStyle.BILIPAI.value)}
    override val homeWallpaperUri=state{it[stringPreferencesKey("home_wallpaper_uri")].orEmpty()}
    override val splashWallpaperUri=state{it[stringPreferencesKey("splash_wallpaper_uri")].orEmpty()}

    /** Original synchronous mode cache namespace. The network transport flag is supplied by
     * the real current Windows network observer, not inferred from the Home route. */
    override fun isDataSaverActive():Boolean {
        val mode=store.snapshot("data_saver").value[favoriteIntKey("mode")]?:1
        return when(mode){0->false;2->true;else->isMobileNetwork()}
    }
    override suspend fun setEasterEggEnabled(enabled:Boolean)=withContext(Dispatchers.IO) {
        store.update("settings",mapOf("easter_egg_enabled" to JsonPrimitive(enabled)))
        // Preserve original second synchronous cache consumer, on the same global backing.
        store.update("easter_egg",mapOf("enabled" to JsonPrimitive(enabled)))
    }
    override suspend fun setTabletUseSidebar(enabled:Boolean)=write("tablet_use_sidebar",JsonPrimitive(enabled))
    override suspend fun setGridColumnCountCompact(columns:Int)=write("grid_column_count_compact",JsonPrimitive(columns))
    override suspend fun setGridColumnCount(columns:Int)=write("grid_column_count",JsonPrimitive(columns))
    private suspend fun write(key:String,value:JsonElement)=withContext(Dispatchers.IO){store.update("settings",mapOf(key to value))}

    companion object {
        /** NavigationSettingsStore.ensureListenVideoBottomTabMigration uses one atomic
         * read-dependent admission on the same backing, before ready StateFlow construction. */
        suspend fun create(store:DesktopPluginStore,scope:CoroutineScope,defaultTabletUseSidebar:Boolean,
            isMobileNetwork:()->Boolean):DesktopOriginalHomePreferences=withContext(Dispatchers.IO) {
            for(name in listOf("settings","data_saver","easter_egg"))store.requireObjectNamespace(name)
            store.updateFromSnapshot("settings") { values ->
                val order=(values[stringPreferencesKey("bottom_bar_order")]?:"HOME,DYNAMIC,HISTORY,LISTEN_VIDEO,PROFILE")
                    .split(',').filter(String::isNotBlank)
                val visible=(values[stringPreferencesKey("bottom_bar_visible_tabs")]?:"HOME,DYNAMIC,HISTORY,LISTEN_VIDEO,PROFILE")
                    .split(',').filter(String::isNotBlank).toSet()
                val migration=resolveListenVideoBottomTabMigration(order,visible,
                    values[booleanPreferencesKey("listen_video_bottom_tab_migration_complete")]?:false)
                buildMap {
                    if(migration.order!=order)put("bottom_bar_order",JsonPrimitive(migration.order.joinToString(",")))
                    if(migration.visible!=visible)put("bottom_bar_visible_tabs",JsonPrimitive(migration.visible.joinToString(",")))
                    if(migration.markComplete)put("listen_video_bottom_tab_migration_complete",JsonPrimitive(true))
                }
            }
            DesktopOriginalHomePreferences(store,scope,defaultTabletUseSidebar,isMobileNetwork)
        }
    }
}

internal class DesktopOriginalHomeGlobalNamespace(store:DesktopPluginStore,name:String):DesktopHomeGlobalNamespace {
    init {store.requireObjectNamespace(name)}
    private val values=store.snapshot(name)
    override fun getBoolean(key:String,default:Boolean)=values.value[booleanPreferencesKey(key)]?:default
}
