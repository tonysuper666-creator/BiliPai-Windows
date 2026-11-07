package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.playerBooleanPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Projection of the original global key, shared by HOME_FEED and the retained partition page. */
class DesktopPartitionPreferences(private val context: DesktopPluginContext) {
    init { context.store.requireObjectNamespace("settings") }

    private val snapshot = context.store.snapshot("settings")
    private val key = playerBooleanPreferencesKey("partition_side_rail_on_right")
    val onRight = snapshot.map { it[key] ?: false }.distinctUntilChanged()

    fun initialSideRailOnRight(): Boolean = snapshot.value[key] ?: false

    /** Use the mounted page's existing journal/admission, including cancellation and atomic commit. */
    internal suspend fun setSideRailOnRight(value: Boolean, owner: DesktopOriginalPlayerSettingsContext) {
        require(owner.pluginContext.store === context.store)
        owner.settingsDataStore.edit { it[key] = value }
    }
}
