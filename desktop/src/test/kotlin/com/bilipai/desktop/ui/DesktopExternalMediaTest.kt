package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.js.BiliPaiJsMediaStream
import com.android.purebilibili.core.plugin.js.ExternalMediaLaunchRequest
import kotlin.test.*

class DesktopExternalMediaTest {
    @Test fun `launch preserves all original streams and MIME declarations while detaching credentials`() {
        val headers = mutableMapOf("Authorization" to "fixture-token", "Cookie" to "", "Referer" to "https://example.invalid/")
        val streams = mutableListOf(
            BiliPaiJsMediaStream("one", "HLS", "https://example.invalid/live", "application/x-mpegURL", headers),
            BiliPaiJsMediaStream("two", "MP4", "https://example.invalid/video.mp4", "video/mp4"))
        val original = ExternalMediaLaunchRequest("fixture-launch", "Fixture", "https://example.invalid/cover", streams, 1)
        val detached = detachDesktopExternalRequest(original)
        headers["Authorization"] = "changed"
        streams.clear()
        assertEquals(listOf("one", "two"), detached.streams.map { it.id })
        assertEquals(listOf("application/x-mpegURL", "video/mp4"), detached.streams.map { it.contentType })
        assertEquals("fixture-token", detached.streams[0].headers["Authorization"])
        assertEquals("", detached.streams[0].headers["Cookie"])
        assertEquals(1, detached.selectedStreamIndex)
        assertEquals(original.coverUrl, detached.coverUrl)
        assertFailsWith<UnsupportedOperationException> { (detached.streams[0].headers as MutableMap)["Cookie"] = "injected" }
    }

    @Test fun `native external route refuses local file and non HTTP transport before loading`() {
        for (url in listOf("file:///C:/fixture.txt", "rtsp://example.invalid/stream", "https:opaque")) {
            assertFailsWith<IllegalArgumentException> {
                detachDesktopExternalRequest(ExternalMediaLaunchRequest("fixture", "Fixture", streams = listOf(BiliPaiJsMediaStream(url = url))))
            }
        }
    }
}
