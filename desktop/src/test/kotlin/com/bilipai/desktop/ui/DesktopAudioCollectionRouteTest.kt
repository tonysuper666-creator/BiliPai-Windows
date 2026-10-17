package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.*
import kotlin.test.*

/** Real typed keys and original route codec; no substitute navigation/store. */
class DesktopAudioCollectionRouteTest {
    @Test fun favoriteAudioCollectionRestoresItsMediaIdAndReservedTitle() {
        val key = BiliPaiNavKey.AudioMode(collectionMediaId = 42L,
            collectionTitle = "默认收藏夹 + 100% & 音乐 # / ? mediaId=999")
        val restored = assertIs<BiliPaiNavKey.AudioMode>(legacyRouteToBiliPaiNavKey(key.toLegacyRoute()))
        assertEquals(key, restored)
        assertEquals(42L, restored.collectionMediaId)
        assertEquals(0L, restored.collectionSeasonId)
    }

    @Test fun seasonAudioCollectionRestoresItsSeasonAndUnicodeTitle() {
        val key = BiliPaiNavKey.AudioMode(collectionSeasonId = 81L, collectionTitle = "专辑 🎵 + 合集")
        assertEquals(key, legacyRouteToBiliPaiNavKey(key.toLegacyRoute()))
        assertEquals(BiliPaiNavEntryContentRole.AUDIO_MODE, resolveBiliPaiNavEntryContentRole(key))
    }

    @Test fun regularVideoAudioRouteStillKeepsTheOriginalVideoAndPart() {
        val key = BiliPaiNavKey.AudioMode(sourceBvid = "BV1GJ411x7h7", sourceCid = 104L)
        assertEquals(key, legacyRouteToBiliPaiNavKey(key.toLegacyRoute()))
        assertEquals(BiliPaiNavKey.AudioMode(), legacyRouteToBiliPaiNavKey("audio_mode"))
    }

    @Test fun restoringACollectionDoesNotChangeItsRealSaveableStateOwner() {
        val favorite = BiliPaiNavKey.AudioMode(collectionMediaId = 42L, collectionTitle = "收藏")
        val season = BiliPaiNavKey.AudioMode(collectionSeasonId = 81L, collectionTitle = "合集")
        assertNotEquals(resolveNavigation3SaveableStateKey(favorite), resolveNavigation3SaveableStateKey(season))
        val restored = legacyRouteToBiliPaiNavKey(favorite.toLegacyRoute())
        assertEquals(resolveNavigation3SaveableStateKey(favorite), resolveNavigation3SaveableStateKey(restored))
        assertTrue(resolveRemovedNavigation3SaveableStateKeys(listOf(favorite), listOf(restored)).isEmpty())
    }
}
