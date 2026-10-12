package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.Dash
import com.android.purebilibili.data.model.response.DashAudio
import com.android.purebilibili.data.model.response.DashVideo
import com.android.purebilibili.data.model.response.Dolby
import com.android.purebilibili.data.model.response.Durl
import com.android.purebilibili.data.model.response.Flac
import com.android.purebilibili.data.model.response.PlayUrlData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Exercises the real cache and the production original expiry policy with fixed clocks. */
class DesktopPlaybackCacheExpiryTest {
    private val storedAt = 1_000_000L
    private val key = DesktopPlaybackCache.Key(7, "BVcache", 31, 80, "avc1",
        authorizationRevision = 9)
    private enum class ReadPath { EXACT, FOR_VIDEO }
    private data class UrlCase(val label: String, val data: PlayUrlData)

    private fun url(resource: String, deadlineSeconds: Long): String =
        "https://media.invalid/$resource?token=fixture&deadline=$deadlineSeconds"

    private fun read(cache: DesktopPlaybackCache, requested: DesktopPlaybackCache.Key,
        path: ReadPath, now: Long): DesktopPlaybackCache.Entry? = when (path) {
        ReadPath.EXACT -> cache.get(requested, now)
        ReadPath.FOR_VIDEO -> cache.getForVideo(requested.accountEpoch,
            requested.authorizationRevision, requested.bvid, requested.cid, now)
    }

    private fun assertExpiryForBothReadPaths(case: UrlCase, expectedExpiry: Long) {
        for (path in ReadPath.entries) {
            val cache = DesktopPlaybackCache()
            cache.put(key, case.data, 80, storedAt)
            val context = "${case.label} / $path"
            val before = assertNotNull(read(cache, key, path, expectedExpiry - 1), context)
            assertSame(case.data, before.data, context)
            assertEquals(80, before.selectionQuality, context)
            assertEquals(storedAt, before.storedAt, context)
            assertEquals(expectedExpiry, before.expiresAt, context)
            assertNull(read(cache, key, path, expectedExpiry), context)
        }
    }

    @Test fun everyDashTrackAndBackupEnforcesItsDeadlineInBothReadPaths(): Unit {
        val early = url("early", 1150)
        val late = url("late", 1500)
        val video = DashVideo(id = 80, baseUrl = late)
        val audio = DashAudio(id = 30280, baseUrl = late)
        val base = Dash(video = listOf(video), audio = listOf(audio),
            dolby = Dolby(audio = listOf(audio)), flac = Flac(audio = audio))
        val cases = listOf(
            UrlCase("video primary", PlayUrlData(dash = base.copy(
                video = listOf(video.copy(baseUrl = early))))),
            UrlCase("video backup", PlayUrlData(dash = base.copy(
                video = listOf(video.copy(backupUrl = listOf(late, early)))))),
            UrlCase("audio primary", PlayUrlData(dash = base.copy(
                audio = listOf(audio.copy(baseUrl = early))))),
            UrlCase("audio backup", PlayUrlData(dash = base.copy(
                audio = listOf(audio.copy(backupUrl = listOf(late, early)))))),
            UrlCase("Dolby primary", PlayUrlData(dash = base.copy(
                dolby = Dolby(audio = listOf(audio.copy(baseUrl = early)))))),
            UrlCase("Dolby backup", PlayUrlData(dash = base.copy(
                dolby = Dolby(audio = listOf(audio.copy(backupUrl = listOf(late, early))))))),
            UrlCase("FLAC primary", PlayUrlData(dash = base.copy(
                flac = Flac(audio = audio.copy(baseUrl = early))))),
            UrlCase("FLAC backup", PlayUrlData(dash = base.copy(
                flac = Flac(audio = audio.copy(backupUrl = listOf(late, early)))))),
        )
        for (case in cases) assertExpiryForBothReadPaths(case, 1_120_000L)
    }

    @Test fun everyProgressiveSegmentAndBackupEnforcesItsDeadlineInBothReadPaths(): Unit {
        val early = url("early", 1150)
        val late = url("late", 1500)
        val first = Durl(order = 1, url = late)
        val cases = listOf(
            UrlCase("second segment primary", PlayUrlData(durl = listOf(
                first, Durl(order = 2, url = early)))),
            UrlCase("second segment backup", PlayUrlData(durl = listOf(
                first, Durl(order = 2, url = "", backupUrl = listOf(late, early))))),
        )
        for (case in cases) assertExpiryForBothReadPaths(case, 1_120_000L)
    }

    @Test fun earliestDeadlineAcrossTracksWinsAndLaterDeadlineCannotExtendTenMinutes(): Unit {
        val mixed = PlayUrlData(
            dash = Dash(video = listOf(DashVideo(baseUrl = url("video", 1300),
                backupUrl = listOf(url("video-backup", 1190)))),
                audio = listOf(DashAudio(baseUrl = url("audio", 1200))),
                dolby = Dolby(audio = listOf(DashAudio(baseUrl = url("dolby", 1350)))),
                flac = Flac(audio = DashAudio(baseUrl = url("flac", 1300)))),
            durl = listOf(Durl(url = url("progressive", 1150))),
        )
        assertExpiryForBothReadPaths(UrlCase("mixed tracks earliest", mixed), 1_120_000L)
        val later = PlayUrlData(dash = Dash(
            video = listOf(DashVideo(baseUrl = url("video", 2500))),
            audio = listOf(DashAudio(baseUrl = url("audio", 2600)))))
        assertExpiryForBothReadPaths(UrlCase("later than maximum age", later), 1_600_000L)
    }

    @Test fun MissingOrInvalidDeadlinesKeepMaximumAgeAndClockReversalDiscardsBothReadPaths(): Unit {
        val urls = listOf(
            "https://media.invalid/plain",
            "https://media.invalid/bad?deadline=not-a-number",
            "https://media.invalid/zero?deadline=0",
            "https://media.invalid/overflow?deadline=9223372036854775807",
            "https://media.invalid/%",
        )
        for (mediaUrl in urls) {
            val data = PlayUrlData(durl = listOf(Durl(url = mediaUrl)))
            assertExpiryForBothReadPaths(UrlCase(mediaUrl, data), 1_600_000L)
            for (path in ReadPath.entries) {
                val cache = DesktopPlaybackCache()
                cache.put(key, data, 80, storedAt)
                assertNull(read(cache, key, path, storedAt - 1), "$mediaUrl / $path")
                assertNull(read(cache, key, path, storedAt), "clock reversal must remove entry")
            }
        }
    }

    @Test fun expiredIncomingDataRemovesItsExistingKeyWithoutDeletingAnotherCid(): Unit {
        val old = PlayUrlData(durl = listOf(Durl(url = "https://media.invalid/old")))
        val other = PlayUrlData(durl = listOf(Durl(url = "https://media.invalid/other")))
        val otherKey = key.copy(cid = 32)
        for (deadline in listOf(1030L, 1000L)) {
            val cache = DesktopPlaybackCache()
            cache.put(key, old, 80, storedAt)
            cache.put(otherKey, other, 80, storedAt)
            val expired = PlayUrlData(durl = listOf(Durl(url = url("expired", deadline))))
            cache.put(key, expired, 80, storedAt)
            assertNull(cache.get(key, storedAt), "deadline=$deadline")
            assertNull(cache.getForVideo(key.accountEpoch, key.authorizationRevision,
                key.bvid, key.cid, storedAt), "deadline=$deadline")
            assertSame(other, assertNotNull(cache.get(otherKey, storedAt)).data)
            assertSame(other, assertNotNull(cache.getForVideo(otherKey.accountEpoch,
                otherKey.authorizationRevision, otherKey.bvid, otherKey.cid, storedAt)).data)
        }
    }

    @Test fun newestForVideoDropsExpiredVariantAndReturnsOlderValidQuality(): Unit {
        val cache = DesktopPlaybackCache()
        val older = PlayUrlData(quality = 64, durl = listOf(Durl(url = "https://media.invalid/older")))
        val newer = PlayUrlData(quality = 80, durl = listOf(Durl(url = url("newer", 1150))))
        val oldKey = key.copy(quality = 64)
        cache.put(oldKey, older, 64, storedAt)
        cache.put(key, newer, 80, storedAt + 1)
        assertSame(newer, assertNotNull(cache.getForVideo(key.accountEpoch,
            key.authorizationRevision, key.bvid, key.cid, 1_119_999L)).data)
        val remaining = assertNotNull(cache.getForVideo(key.accountEpoch,
            key.authorizationRevision, key.bvid, key.cid, 1_120_000L))
        assertSame(older, remaining.data)
        assertEquals(64, remaining.selectionQuality)
        assertNull(cache.get(key, 1_120_000L))
        assertSame(older, assertNotNull(cache.get(oldKey, 1_120_000L)).data)
    }

    @Test fun epochAuthorizationRevisionCidAndExactSelectionStayPartitioned(): Unit {
        val cache = DesktopPlaybackCache()
        val first = PlayUrlData(durl = listOf(Durl(url = url("first", 1500))))
        val second = PlayUrlData(durl = listOf(Durl(url = url("second", 1500))))
        cache.put(key, first, 80, storedAt)
        cache.put(key.copy(cid = 32), second, 80, storedAt)
        assertSame(first, assertNotNull(cache.get(key, storedAt + 1)).data)
        assertSame(second, assertNotNull(cache.get(key.copy(cid = 32), storedAt + 1)).data)
        for (mismatch in listOf(key.copy(accountEpoch = 8), key.copy(authorizationRevision = 10),
                key.copy(cid = 33), key.copy(quality = 64), key.copy(codec = "hev1"))) {
            assertNull(cache.get(mismatch, storedAt + 1))
        }
        assertNull(cache.getForVideo(8, 9, key.bvid, key.cid, storedAt + 1))
        assertNull(cache.getForVideo(7, 10, key.bvid, key.cid, storedAt + 1))
        assertNull(cache.getForVideo(7, 9, key.bvid, 33, storedAt + 1))
        val revised = key.copy(authorizationRevision = 10)
        cache.put(revised, second, 80, storedAt + 1)
        assertNull(cache.get(key, storedAt + 2))
        assertNull(cache.get(key.copy(cid = 32), storedAt + 2))
        assertSame(second, assertNotNull(cache.get(revised, storedAt + 2)).data)
        val switched = revised.copy(accountEpoch = 8)
        cache.put(switched, first, 80, storedAt + 2)
        assertNull(cache.get(revised, storedAt + 3))
        assertNull(cache.getForVideo(7, 10, key.bvid, key.cid, storedAt + 3))
        assertSame(first, assertNotNull(cache.getForVideo(8, 10, key.bvid,
            key.cid, storedAt + 3)).data)
    }
}
