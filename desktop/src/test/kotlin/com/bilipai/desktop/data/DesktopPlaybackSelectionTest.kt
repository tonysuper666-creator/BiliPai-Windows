package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import kotlin.test.*

class DesktopPlaybackSelectionTest {
    private val repository = DesktopRepository(DesktopSessionStore.temporary())
    private val details = VideoDetails("BV17x411w7KC", 170001, "video", "", "", "up", 0, 0, listOf(VideoPart(9, "P1", 60)))
    private val dash = Dash(video = listOf(
        DashVideo(id = 80, baseUrl = "https://media.invalid/avc", codecs = "avc1.640028", bandwidth = 1_000),
        DashVideo(id = 80, baseUrl = "https://media.invalid/hevc", codecs = "hev1.1.6.L120", bandwidth = 800),
        DashVideo(id = 80, baseUrl = "https://media.invalid/av1", codecs = "av01.0.08M.08", bandwidth = 600)),
        audio = listOf(DashAudio(baseUrl = "https://media.invalid/audio", bandwidth = 100)),
        flac = Flac(audio = DashAudio(id = 30251, baseUrl = "https://media.invalid/flac")))
    private val data = PlayUrlData(quality = 80, dash = dash, acceptQuality = listOf(80, 64), acceptDescription = listOf("1080P", "720P"))

    private fun selected(data: PlayUrlData = this.data, codec: String? = null, quality: Int = 80): PlaybackSource? =
        with(repository) { data.toPlaybackSource(details, quality, codec) }

    @Test fun defaultHevcSelectionRetainsActualServerCatalogAndQualityLabels() {
        val source = assertNotNull(selected())
        assertEquals("https://media.invalid/hevc", source.videoUrl)
        assertEquals("hev1", source.videoCodecFamily)
        assertSame(dash, source.cachedDashData)
        assertEquals(listOf(PlaybackQuality(80, "1080P"), PlaybackQuality(64, "720P")), source.availableQualities)
        assertEquals(30251, source.cachedDashData?.flac?.audio?.id)
    }

    @Test fun codecOverridesUseOriginalTrackSelectionAndExposeActualFamily() {
        assertEquals("https://media.invalid/avc", selected(codec = "avc1")?.videoUrl)
        assertEquals("hev1", selected(codec = "HEV1.1.6.L120")?.videoCodecFamily)
        assertEquals("https://media.invalid/hevc", selected(codec = "hev1")?.videoUrl)
        assertEquals("av01", selected(codec = "av01")?.videoCodecFamily)
        val missingAv1 = data.copy(dash = dash.copy(video = dash.video.filterNot { it.codecs.startsWith("av01") }))
        assertEquals("avc1", selected(missingAv1, "av01")?.videoCodecFamily)
    }

    @Test fun backupOnlyDashTrackRetainsFamilyAndDoesNotInferItFromUrl() {
        val track = DashVideo(id = 64, codecs = "hev1.1.6.L93", backupUrl = listOf("https://cdn.invalid/opaque-resource"))
        val source = assertNotNull(selected(data.copy(dash = Dash(video = listOf(track))), "hev1", 64))
        assertEquals("hev1", source.videoCodecFamily)
        assertEquals(64, source.quality)
        assertEquals(track.backupUrl, source.videoAlternatives)
    }

    @Test fun progressiveSourceHasNoFabricatedDashFamily() {
        val source = assertNotNull(selected(PlayUrlData(quality = 64, durl = listOf(Durl(url = "https://media.invalid/muxed")))))
        assertNull(source.videoCodecFamily); assertNull(source.cachedDashData)
    }

    @Test fun everyProgressiveSegmentKeepsOriginalSequenceDurationAndBackupSelection() {
        val source = assertNotNull(selected(PlayUrlData(quality = 64, durl = listOf(
            Durl(order = 1, url = "https://media.invalid/first.flv", length = 1200),
            Durl(order = 2, backupUrl = listOf("", "//media.invalid/second.flv"), length = 2300)))))
        assertEquals("https://media.invalid/first.flv", source.videoUrl)
        assertEquals(listOf("https://media.invalid/first.flv", "https://media.invalid/second.flv"), source.progressiveSegments.map { it.url })
        assertEquals(listOf(1.2, 2.3), source.progressiveSegments.map { it.durationSeconds })
        assertNull(source.audioUrl); assertTrue(source.videoAlternatives.isEmpty())
    }

    @Test fun missingOrUnsafeProgressiveSegmentRejectsTheCompleteSourceInsteadOfTruncating() {
        for (invalid in listOf("", "file:///C:/secret", "javascript:alert(1)")) {
            assertNull(selected(PlayUrlData(durl = listOf(
                Durl(order = 1, url = "https://media.invalid/first.flv", length = 1000),
                Durl(order = 2, url = invalid, length = 1000)))))
        }
    }

    @Test fun singleBackupOnlyProgressiveSourceUsesActualBackupWithoutUnneededEdl() {
        val source = assertNotNull(selected(PlayUrlData(quality = 64,
            durl = listOf(Durl(backupUrl = listOf("https://media.invalid/only.mp4"))))))
        assertEquals("https://media.invalid/only.mp4", source.videoUrl)
        assertTrue(source.progressiveSegments.isEmpty())
    }

    @Test fun cacheDoesNotCrossAccountsCidsQualitiesOrCodecOverrides() {
        val cache = DesktopPlaybackCache()
        val key = DesktopPlaybackCache.Key(1, details.bvid, 9, 80, "avc1")
        cache.put(key, data, 80, 100)
        assertSame(data, assertNotNull(cache.get(key, 101)).data)
        assertNull(cache.get(key.copy(accountEpoch = 2), 101))
        assertNull(cache.get(key.copy(cid = 10), 101))
        assertNull(cache.get(key.copy(quality = 64), 101))
        assertNull(cache.get(key.copy(codec = "hev1"), 101))
        cache.put(key.copy(accountEpoch = 2), data, 80, 101)
        assertNull(cache.get(key, 102))
    }

    @Test fun forceRefreshInvalidatesAllCodecCandidatesOnlyForThisVideo() {
        val cache = DesktopPlaybackCache()
        val key = DesktopPlaybackCache.Key(1, details.bvid, 9, 80, "avc1")
        cache.put(key, data, 80, 100); cache.put(key.copy(codec = "hev1"), data, 80, 100)
        cache.put(key.copy(cid = 10), data, 80, 100)
        cache.invalidateVideo(1, key.bvid, key.cid)
        assertNull(cache.get(key, 101)); assertNull(cache.get(key.copy(codec = "hev1"), 101))
        assertNotNull(cache.get(key.copy(cid = 10), 101))
    }

    @Test fun expiredOrClockReversedMediaUrlsAreNeverReused() {
        val cache = DesktopPlaybackCache(); val key = DesktopPlaybackCache.Key(1, details.bvid, 9, 80, null)
        cache.put(key, data, 80, 100)
        assertSame(data, assertNotNull(cache.get(key, 100 + 300_000)).data)
        assertSame(data, assertNotNull(cache.get(key, 100 + 600_000)).data)
        assertNull(cache.get(key, 100 + 600_001))
        cache.put(key, data, 80, 100)
        assertNull(cache.get(key, 99))
    }
}
