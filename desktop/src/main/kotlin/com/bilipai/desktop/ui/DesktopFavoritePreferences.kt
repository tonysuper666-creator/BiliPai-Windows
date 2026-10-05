package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicReference

internal fun favoriteBooleanKey(name:String)=DesktopPreferenceKey<Boolean>(name){(it as? JsonPrimitive)?.booleanOrNull}
internal fun favoriteIntKey(name:String)=DesktopPreferenceKey<Int>(name){(it as? JsonPrimitive)?.intOrNull}
internal fun favoriteFloatKey(name:String)=DesktopPreferenceKey<Float>(name){(it as? JsonPrimitive)?.floatOrNull}

/** One read projection on Root's actual global store, using original keys/defaults/decoder.
 * No account-scoped preference or fallback store is opened. Cached drag offset is the
 * original BackToTopSettingsStore UI cache, never a second persistent authority. */
class DesktopFavoritePreferences(val store:DesktopPluginStore) {
    init {store.requireObjectNamespace("settings")}
    private val values=store.snapshot("settings")
    val homeSettings=values.map(::decodeDesktopFavoriteHomeAppearance).distinctUntilChanged()
    fun initialHomeSettings()=decodeDesktopFavoriteHomeAppearance(values.value)
    val personalRecapEnabled=DesktopPersonalRecapSettings.getSubscriptionRecapEnabled(DesktopPluginContext(store))
    val showOnlineCount=values.map{it[favoriteBooleanKey("show_online_count")]?:false}.distinctUntilChanged()
    private fun navigation(snapshot:DesktopPreferenceSnapshot)=DesktopFavoriteNavigationAppearance(
        DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.fromValue(snapshot[favoriteIntKey("bottom_bar_visibility_mode")]
            ?:DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.ALWAYS_VISIBLE.value))
    val navigationSettings=values.map(::navigation).distinctUntilChanged()
    fun initialNavigationSettings()=navigation(values.value)
    val homeFeedCardStyle=values.map{HomeFeedCardStyle.fromValue(it[favoriteIntKey("home_feed_card_style")]?:HomeFeedCardStyle.BILIPAI.value)}.distinctUntilChanged()
    val backToTopEnabled=values.map{it[favoriteBooleanKey("back_to_top_button_enabled")]?:true}.distinctUntilChanged()
    fun initialBackToTopEnabled()=values.value[favoriteBooleanKey("back_to_top_button_enabled")]?:true
    private fun offset(snapshot:DesktopPreferenceSnapshot)=Pair(snapshot[favoriteFloatKey("back_to_top_button_offset_x_dp")]?:0f,
        snapshot[favoriteFloatKey("back_to_top_button_offset_y_dp")]?:0f)
    private val cachedOffset=AtomicReference(offset(values.value))
    val backToTopOffset=values.map{offset(it).also(cachedOffset::set)}.distinctUntilChanged()
    fun initialBackToTopOffset()=cachedOffset.get()
    fun updateBackToTopOffset(x:Float,y:Float) {cachedOffset.set(x to y)}
    suspend fun setBackToTopOffset(x:Float,y:Float)=withContext(Dispatchers.IO) {
        cachedOffset.set(x to y)
        store.update("settings",mapOf("back_to_top_button_offset_x_dp" to JsonPrimitive(x),"back_to_top_button_offset_y_dp" to JsonPrimitive(y)))
    }
}
