package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaybackSegmentsTest {
    @Test fun `progressive EDL preserves URL bytes including separators and Unicode`() {
        val first = "https://example.test/中文.mp4?x=1,2;!next=unsafe"
        val second = "https://example.test/segment.mp4?token=abc%20def"
        val source = PlaybackSource(first, progressiveSegments = listOf(PlaybackSegment(first, 2.5), PlaybackSegment(second, 3.75)))
        assertEquals("edl://%${first.toByteArray(Charsets.UTF_8).size}%$first,0,2.5;%${second.toByteArray(Charsets.UTF_8).size}%$second,0,3.75", source.nativeLoadUrl)
    }

    @Test fun `one ordinary source remains unchanged and durationless segments use native probing`() {
        assertEquals("https://example.test/video.mp4", PlaybackSource("https://example.test/video.mp4").nativeLoadUrl)
        assertEquals("edl://%20%file:///C:/first.mp4;%21%file:///C:/second.mp4", PlaybackSource("file:///C:/first.mp4",
            progressiveSegments = listOf(PlaybackSegment("file:///C:/first.mp4"), PlaybackSegment("file:///C:/second.mp4"))).nativeLoadUrl)
    }

    @Test fun `progressive inputs refuse mixed audio sources invalid durations and arbitrary native protocols`() {
        assertFailsWith<IllegalArgumentException> { PlaybackSegment("edl://!new_stream;bad") }
        assertFailsWith<IllegalArgumentException> { PlaybackSegment("https://example.test/a.mp4\n!new_stream") }
        assertFailsWith<IllegalArgumentException> { PlaybackSegment("https://example.test/a.mp4", Double.NaN) }
        assertFailsWith<IllegalArgumentException> { PlaybackSegment("https://example.test/a.mp4", 0.0) }
        assertFailsWith<IllegalArgumentException> { PlaybackSource("https://example.test/a.mp4", "https://example.test/audio.m4a",
            progressiveSegments = listOf(PlaybackSegment("https://example.test/a.mp4", 3.0))) }
    }

    @Test fun `Java single slash file URIs normalize before counting EDL bytes and Windows paths remain literal`() {
        val fileUri = "file:/C:/My%20Video/中文.avi"
        val normalized = "file:///C:/My%20Video/中文.avi"
        val nativePath = "C:\\My Video\\中文.avi"
        assertEquals(normalized, PlaybackSegment(fileUri).nativeUrl)
        assertEquals(nativePath, PlaybackSegment(nativePath).nativeUrl)
        val source = PlaybackSource(fileUri, progressiveSegments = listOf(PlaybackSegment(fileUri, 3.0), PlaybackSegment(nativePath, 4.0)))
        assertEquals("edl://%${normalized.toByteArray(Charsets.UTF_8).size}%$normalized,0,3.0;%${nativePath.toByteArray(Charsets.UTF_8).size}%$nativePath,0,4.0", source.nativeLoadUrl)
    }
}
