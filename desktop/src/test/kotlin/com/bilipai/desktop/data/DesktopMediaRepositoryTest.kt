package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DesktopMediaRepositoryTest {
    private val room = LiveRoomDetails(123, "直播标题", "", "主播", "", 10, "生活", 1)
    private val episode = BangumiEpisode(42, 8, "BV1xx411c7mD", 99, "1", "第一集", "", 60)
    private val season = BangumiSeason(7, "番剧", "", "", listOf(episode))

    @Test fun `live selects AVC HLS and preserves quality and CDN backup`() {
        val data = LivePlayUrlData(playurl_info = PlayurlInfo(Playurl(stream = listOf(
            StreamInfo("http_stream", listOf(FormatInfo("flv", listOf(CodecInfo("hevc", 150, baseUrl = "/live.flv", url_info = listOf(UrlInfo("https://hevc.example"))))))),
            StreamInfo("http_hls", listOf(FormatInfo("fmp4", listOf(CodecInfo("avc", 150, baseUrl = "/index.m3u8",
                url_info = listOf(UrlInfo("https://first.example", "?token=one"), UrlInfo("https://backup.example", "?token=two"))))))),
        ), gQnDesc = listOf(LiveQuality(150, "高清")))))
        val selected = requireNotNull(DesktopMediaRepository.selectLive(data, room, 150))
        assertEquals("https://first.example/index.m3u8?token=one", selected.source.videoUrl)
        assertEquals(listOf("https://backup.example/index.m3u8?token=two"), selected.backupUrls)
        assertEquals("https://live.bilibili.com/123", selected.source.referer)
        assertEquals(MediaQuality(150, "高清"), selected.qualities.single())
    }

    @Test fun `original quality request keeps server downgraded quality as actual`() {
        val data = LivePlayUrlData(playurl_info = PlayurlInfo(Playurl(stream = listOf(
            StreamInfo("http_hls", listOf(FormatInfo("fmp4", listOf(CodecInfo("avc", 150,
                baseUrl = "/index.m3u8", url_info = listOf(UrlInfo("https://first.example"))))))),
        ), gQnDesc = listOf(LiveQuality(10000, "原画"), LiveQuality(150, "高清")))))
        val selected = requireNotNull(DesktopMediaRepository.selectLive(data, room, 10000))
        assertEquals(150, selected.source.quality)
        assertEquals(listOf(10000, 150), selected.qualities.map { it.id })
        assertEquals("https://first.example/index.m3u8", selected.source.videoUrl)
    }

    @Test fun `legacy live supports muxed URL and rejects file protocols`() {
        val data = LivePlayUrlData(durl = listOf(LiveDurl("file:///etc/passwd"), LiveDurl("//cdn.example/live.flv")), current_quality = 80)
        assertEquals("https://cdn.example/live.flv", DesktopMediaRepository.selectLive(data, room, 150)?.source?.videoUrl)
        assertNull(DesktopMediaRepository.selectLive(LivePlayUrlData(durl = listOf(LiveDurl("javascript:alert(1)"))), room, 150))
    }

    @Test fun `PGC DASH keeps audio alongside matching video quality`() {
        val info = BangumiVideoInfo(quality = 80, dash = Dash(video = listOf(DashVideo(80, "https://cdn.example/video.m4s", codecs = "avc1")),
            audio = listOf(DashAudio(30280, "https://cdn.example/audio.m4s", bandwidth = 128000))))
        val source = DesktopMediaRepository.selectBangumi(info, season, episode, 80)
        assertEquals("https://cdn.example/video.m4s", source.videoUrl)
        assertEquals("https://cdn.example/audio.m4s", source.audioUrl)
        assertEquals("https://www.bilibili.com/bangumi/play/ep42", source.referer)
    }

    @Test fun `DRM without regular web streams is rejected without inventing a URL`() {
        val error = assertFailsWith<BiliApiException> {
            DesktopMediaRepository.selectBangumi(BangumiVideoInfo(isDrm = true), season, episode, 80)
        }
        assertEquals(-403, error.apiCode)
        assertTrue(error.message.orEmpty().contains("DRM"))
    }

    @Test fun `plain stream is playable when upstream also includes DRM marker`() {
        val source = DesktopMediaRepository.selectBangumi(BangumiVideoInfo(isDrm = true,
            durl = listOf(Durl(url = "https://cdn.example/normal.mp4"))), season, episode, 80)
        assertEquals("https://cdn.example/normal.mp4", source.videoUrl)
    }

    @Test fun `PGC request carries episode season cid and permitted web playback params`() {
        val params = DesktopMediaRepository.buildBangumiParams(season.seasonId, episode, 80)
        assertEquals("42", params["ep_id"])
        assertEquals("7", params["season_id"])
        assertEquals("99", params["cid"])
        assertEquals("12240", params["fnval"])
        assertEquals("8", params["avid"])
    }

    @Test fun `PUGV request uses original course format and every durl segment is preserved`() {
        val detail = Json { ignoreUnknownKeys = true }.decodeFromString(BangumiDetail.serializer(),
            """{"season_id":7,"season_type":10,"title":"课程","rights":{"allow_download":1}}""")
        val course = season.copy(upstreamDetail = detail)
        assertTrue(course.isCourse)
        assertEquals("4048", DesktopMediaRepository.buildBangumiParams(7, episode, 80, true)["fnval"])
        val source = DesktopMediaRepository.selectBangumi(BangumiVideoInfo(quality = 80, durl = listOf(
            Durl(url = "https://cdn.example/one.flv", length = 1000),
            Durl(url = "", backupUrl = listOf("https://cdn.example/two.flv"), length = 2500),
        )), course, episode, 80)
        assertEquals("https://www.bilibili.com/cheese/play/ep42", source.referer)
        assertEquals(listOf("https://cdn.example/one.flv", "https://cdn.example/two.flv"), source.progressiveSegments.map { it.url })
        assertEquals(listOf(1.0, 2.5), source.progressiveSegments.map { it.durationSeconds })
        assertNull(source.audioUrl)
    }

    @Test fun `missing or unsafe progressive segment cannot silently truncate an episode`() {
        for (url in listOf("", "file:///C:/secret")) assertFailsWith<BiliApiException> {
            DesktopMediaRepository.selectBangumi(BangumiVideoInfo(durl = listOf(
                Durl(url = "https://cdn.example/one.flv", length = 1000), Durl(url = url, length = 1000),
            )), season, episode, 80)
        }
    }
}
