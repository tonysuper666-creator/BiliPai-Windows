package com.android.purebilibili.feature.settings

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable

import com.android.purebilibili.core.ui.AppSemanticIconFamily
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*

private enum class SettingsNavigationIconRole {
    HOME,
    DYNAMIC,
    STORY,
    HISTORY,
    LISTEN_VIDEO,
    PROFILE,
    FAVORITE,
    LIVE,
    WATCH_LATER,
    SETTINGS,
    PLUGINS,
    FOLLOW,
    POPULAR,
    ANIME,
    GAME,
    PARTITION,
    KNOWLEDGE,
    TECH,
    SUBSCRIPTIONS,
}

internal fun resolveSettingsNavigationPreviewMaterialSymbolResource(
    tabId: String,
    selected: Boolean = false,
): String = when (tabId.trim().uppercase()) {
    "HOME", "RECOMMEND" -> if (selected) "ms_home_fill_24" else "ms_home_24"
    "DYNAMIC" -> if (selected) "ms_notifications_fill_24" else "ms_notifications_none_24"
    "STORY" -> if (selected) "ms_play_circle_fill_24" else "ms_play_circle_outline_24"
    "HISTORY" -> if (selected) "ms_history_fill_24" else "ms_history_24"
    "LISTEN_VIDEO" -> if (selected) "ms_library_music_fill_24" else "ms_library_music_24"
    "PROFILE", "FOLLOW" -> if (selected) "ms_person_fill_24" else "ms_person_24"
    "FAVORITE" -> if (selected) "ms_collections_bookmark_fill_24" else "ms_collections_bookmark_24"
    "LIVE" -> if (selected) "ms_live_tv_fill_24" else "ms_live_tv_24"
    "WATCHLATER", "WATCH_LATER" -> if (selected) "ms_watch_later_fill_24" else "ms_watch_later_24"
    "SETTINGS" -> if (selected) "ms_settings_fill_24" else "ms_settings_24"
    "PLUGINS" -> if (selected) "ms_extension_fill_24" else "ms_extension_24"
    "POPULAR" -> if (selected) "ms_trending_up_fill_24" else "ms_trending_up_24"
    "ANIME" -> if (selected) "ms_collections_bookmark_fill_24" else "ms_collections_bookmark_24"
    "GAME" -> if (selected) "ms_sports_esports_fill_24" else "ms_sports_esports_24"
    "PARTITION" -> if (selected) "ms_grid_view_fill_24" else "ms_grid_view_24"
    "KNOWLEDGE" -> if (selected) "ms_lightbulb_fill_24" else "ms_lightbulb_24"
    "TECH" -> if (selected) "ms_smart_toy_fill_24" else "ms_smart_toy_24"
    "SUBSCRIPTIONS" -> "ms_rss_feed_24"
    else -> if (selected) "ms_home_fill_24" else "ms_home_24"
}

/** Uses the active native icon family so the navigation preview matches the runtime chrome. */
@Composable
internal fun resolveSettingsNavigationPreviewIcon(
    tabId: String,
    iconFamily: AppSemanticIconFamily,
    selected: Boolean = false,
): ImageVector {
    val role = when (tabId.trim().uppercase()) {
        "HOME", "RECOMMEND" -> SettingsNavigationIconRole.HOME
        "DYNAMIC" -> SettingsNavigationIconRole.DYNAMIC
        "STORY" -> SettingsNavigationIconRole.STORY
        "HISTORY" -> SettingsNavigationIconRole.HISTORY
        "LISTEN_VIDEO" -> SettingsNavigationIconRole.LISTEN_VIDEO
        "PROFILE" -> SettingsNavigationIconRole.PROFILE
        "FAVORITE" -> SettingsNavigationIconRole.FAVORITE
        "LIVE" -> SettingsNavigationIconRole.LIVE
        "WATCHLATER", "WATCH_LATER" -> SettingsNavigationIconRole.WATCH_LATER
        "SETTINGS" -> SettingsNavigationIconRole.SETTINGS
        "PLUGINS" -> SettingsNavigationIconRole.PLUGINS
        "FOLLOW" -> SettingsNavigationIconRole.FOLLOW
        "POPULAR" -> SettingsNavigationIconRole.POPULAR
        "ANIME" -> SettingsNavigationIconRole.ANIME
        "GAME" -> SettingsNavigationIconRole.GAME
        "PARTITION" -> SettingsNavigationIconRole.PARTITION
        "KNOWLEDGE" -> SettingsNavigationIconRole.KNOWLEDGE
        "TECH" -> SettingsNavigationIconRole.TECH
        "SUBSCRIPTIONS" -> SettingsNavigationIconRole.SUBSCRIPTIONS
        else -> SettingsNavigationIconRole.HOME
    }

    return when (iconFamily) {
        AppSemanticIconFamily.MIUIX -> when (role) {
            SettingsNavigationIconRole.HOME -> if (selected) MiuixIcons.Medium.Home else MiuixIcons.Home
            SettingsNavigationIconRole.DYNAMIC -> if (selected) MiuixIcons.Medium.Messages else MiuixIcons.Messages
            SettingsNavigationIconRole.STORY -> if (selected) MiuixIcons.Medium.Recording else MiuixIcons.Recording
            SettingsNavigationIconRole.HISTORY -> if (selected) MiuixIcons.Medium.Recent else MiuixIcons.Recent
            SettingsNavigationIconRole.LISTEN_VIDEO -> if (selected) MiuixIcons.Medium.Music else MiuixIcons.Music
            SettingsNavigationIconRole.PROFILE -> MiuixIcons.ContactsCircle
            SettingsNavigationIconRole.FAVORITE -> if (selected) rememberDesktopNavigationMaterialSymbol("ms_collections_bookmark_fill_24") else MiuixIcons.Favorites
            SettingsNavigationIconRole.LIVE -> if (selected) rememberDesktopNavigationMaterialSymbol("ms_live_tv_24") else rememberDesktopNavigationMaterialSymbol("ms_live_tv_24")
            SettingsNavigationIconRole.WATCH_LATER -> if (selected) rememberDesktopNavigationMaterialSymbol("ms_watch_later_24") else rememberDesktopNavigationMaterialSymbol("ms_watch_later_24")
            SettingsNavigationIconRole.SETTINGS -> if (selected) MiuixIcons.Medium.Settings else MiuixIcons.Settings
            SettingsNavigationIconRole.PLUGINS -> if (selected) rememberDesktopNavigationMaterialSymbol("ms_extension_fill_24") else MiuixIcons.Folder
            SettingsNavigationIconRole.FOLLOW -> MiuixIcons.Contacts
            SettingsNavigationIconRole.POPULAR -> MiuixIcons.TopDownloads
            SettingsNavigationIconRole.ANIME -> if (selected) rememberDesktopNavigationMaterialSymbol("ms_collections_bookmark_fill_24") else MiuixIcons.Favorites
            SettingsNavigationIconRole.GAME -> MiuixIcons.Store
            SettingsNavigationIconRole.PARTITION -> if (selected) MiuixIcons.Medium.GridView else MiuixIcons.GridView
            SettingsNavigationIconRole.KNOWLEDGE -> MiuixIcons.Notes
            SettingsNavigationIconRole.TECH -> rememberDesktopNavigationMaterialSymbol("ms_smart_toy_24")
            SettingsNavigationIconRole.SUBSCRIPTIONS -> rememberDesktopNavigationMaterialSymbol("ms_rss_feed_24")
        }
        AppSemanticIconFamily.MATERIAL -> rememberDesktopNavigationMaterialSymbol(
            resolveSettingsNavigationPreviewMaterialSymbolResource(tabId, selected)
        )
    }
}
