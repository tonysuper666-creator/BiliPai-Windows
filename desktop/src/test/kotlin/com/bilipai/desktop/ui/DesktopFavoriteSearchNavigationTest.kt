package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.FavoriteSearchScope
import com.android.purebilibili.navigation3.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Executes the production original keys, route parser and state-key policy. */
class DesktopFavoriteSearchNavigationTest {
    @Test fun subscribedSearchPreservesReservedQueryAndEveryScopeAcrossRouteRestoration() {
        for (scope in FavoriteSearchScope.entries) {
            val key = BiliPaiNavKey.FavoriteSearch("教程 + 合集 / 100% & subscribed=false ? #", scope, true)
            assertEquals(key, legacyRouteToBiliPaiNavKey(key.toLegacyRoute()))
        }
    }

    @Test fun oldSavedTypedSearchDefaultsToOrdinaryFavorites() {
        val restored = Json.decodeFromString<BiliPaiNavKey.FavoriteSearch>("""{"query":"旧搜索"}""")
        assertEquals("旧搜索", restored.query)
        assertFalse(restored.subscribed)
        assertEquals(FavoriteSearchScope.CURRENT_FOLDER, restored.scope)
        assertEquals(restored, Json.decodeFromString<BiliPaiNavKey.FavoriteSearch>(Json.encodeToString(restored)))
    }

    @Test fun legacyRouteWithoutFlagRemainsOrdinaryAndKeepsExplicitScope() {
        val restored = assertIs<BiliPaiNavKey.FavoriteSearch>(legacyRouteToBiliPaiNavKey(
            "favorite_search?query=old&scope=ALL_VIDEO_FOLDERS"))
        assertEquals("old", restored.query)
        assertEquals(FavoriteSearchScope.ALL_VIDEO_FOLDERS, restored.scope)
        assertFalse(restored.subscribed)
    }

    @Test fun unrelatedQueryTextCannotPromoteARegularSearchToSubscribed() {
        val key = BiliPaiNavKey.FavoriteSearch("x&subscribed=true#subscribed=true")
        assertEquals(key, legacyRouteToBiliPaiNavKey(key.toLegacyRoute()))
        for (flag in listOf("", "false", "1", "yes", "garbage")) {
            assertFalse(assertIs<BiliPaiNavKey.FavoriteSearch>(legacyRouteToBiliPaiNavKey(
                "favorite_search?query=x&subscribed=$flag")).subscribed)
        }
    }

    @Test fun subscribedAndOrdinarySearchKeepDifferentSaveableStateOwners() {
        val ordinary = BiliPaiNavKey.FavoriteSearch("same")
        val subscribed = ordinary.copy(subscribed = true)
        assertNotEquals(ordinary, subscribed)
        assertNotEquals(resolveNavigation3SaveableStateKey(ordinary), resolveNavigation3SaveableStateKey(subscribed))
        assertEquals(resolveNavigation3SaveableStateKey(subscribed),
            resolveNavigation3SaveableStateKey(legacyRouteToBiliPaiNavKey(subscribed.toLegacyRoute())))
    }

    @Test fun typedSerializationKeepsSubscribedSearchWithoutChangingSiblingListRoutes() {
        val key = BiliPaiNavKey.FavoriteSearch("收藏 + 合集", subscribed = true)
        assertEquals(key, Json.decodeFromString<BiliPaiNavKey.FavoriteSearch>(Json.encodeToString(key)))
        for (sibling in listOf(BiliPaiNavKey.Favorite, BiliPaiNavKey.FavoriteSubscribed,
            BiliPaiNavKey.HistorySearch("历史"), BiliPaiNavKey.WatchLaterSearch("稍后"))) {
            assertEquals(sibling, legacyRouteToBiliPaiNavKey(sibling.toLegacyRoute()))
        }
    }
}
