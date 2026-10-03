package com.bilipai.desktop.player

import com.bilipai.desktop.danmaku.DanmakuSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerPreferencesTest {
    @Test fun `playback modes honor natural completion and queue bounds`() {
        assertNull(resolveNextPlaybackIndex(PlaybackMode.STOP_AFTER_CURRENT, 0, 3))
        assertEquals(1, resolveNextPlaybackIndex(PlaybackMode.SEQUENTIAL, 0, 3))
        assertNull(resolveNextPlaybackIndex(PlaybackMode.SEQUENTIAL, 2, 3))
        assertEquals(2, resolveNextPlaybackIndex(PlaybackMode.REPEAT_ONE, 2, 3))
        assertEquals(0, resolveNextPlaybackIndex(PlaybackMode.REPEAT_ALL, 2, 3))
        assertNull(resolveNextPlaybackIndex(PlaybackMode.REPEAT_ALL, -1, 3))
        assertNull(resolveNextPlaybackIndex(PlaybackMode.REPEAT_ONE, 0, 0))
        val random = kotlin.random.Random(234)
        val shuffled = List(100) { resolveNextPlaybackIndex(PlaybackMode.SHUFFLE, 1, 3, random) }
        assertEquals(setOf(0, 2), shuffled.toSet())
        assertEquals(0, resolveNextPlaybackIndex(PlaybackMode.SHUFFLE, 0, 1, random))
    }

    @Test fun `player and danmaku preferences survive restart in isolated data`() {
        val directory = Files.createTempDirectory("bilipai-preferences-test-")
        val file = directory.resolve("player-settings.json")
        try {
            val settings = PlayerPreferences(volume = 34.0, speed = 1.75, muted = true, audioOnly = true,
                playbackMode = PlaybackMode.REPEAT_ALL, danmaku = DanmakuSettings(opacity = 0.4f,
                    displayAreaRatio = 0.75f, allowColorful = false, blockedKeywords = listOf("剧透")))
            PlayerPreferencesStore(file).save(settings)
            assertEquals(settings, PlayerPreferencesStore(file).read())
            assertEquals(listOf("player-settings.json"), Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList() })
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `invalid stored values normalize and damaged documents recover defaults`() {
        val directory = Files.createTempDirectory("bilipai-preferences-test-")
        val file = directory.resolve("player-settings.json")
        try {
            Files.writeString(file, """{"volume":-5,"speed":20,"danmaku":{"opacity":3,"fontScale":-2,"blockedKeywords":[" ","abc","abc"]},"futurePreference":true}""")
            val loaded = PlayerPreferencesStore(file).read()
            assertEquals(0.0, loaded.volume)
            assertEquals(8.0, loaded.speed)
            assertEquals(1f, loaded.danmaku.opacity)
            assertEquals(0.3f, loaded.danmaku.fontScale)
            assertEquals(listOf("abc"), loaded.danmaku.blockedKeywords)
            Files.writeString(file, "not a valid document")
            assertEquals(PlayerPreferences(), PlayerPreferencesStore(file).read())
            assertFalse(loaded.muted)
            assertTrue(loaded.danmaku.enabled)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `non-finite in-memory settings become usable defaults before serialization`() {
        val preferences = PlayerPreferences(volume = Double.NaN, speed = Double.POSITIVE_INFINITY,
            danmaku = DanmakuSettings(opacity = Float.NaN, speedFactor = Float.NEGATIVE_INFINITY)).normalized()
        assertEquals(75.0, preferences.volume)
        assertEquals(1.0, preferences.speed)
        assertEquals(0.85f, preferences.danmaku.opacity)
        assertEquals(1f, preferences.danmaku.speedFactor)
    }

    @Test fun `Bilibili JSON subtitle conversion preserves ordered Unicode cues and times`() {
        val srt = BiliSubtitleDocument.toSrt("""{"body":[
            {"from":2.001,"to":3.5,"content":"第二行","location":2},
            {"from":0.25,"to":1.75,"content":"第一行\n English"},
            {"from":-1,"to":2,"content":"invalid"},
            {"from":4,"to":4,"content":"zero length"},
            {"from":5,"to":6,"content":" "}
        ],"font_color":"#FFFFFF"}""")
        // The upstream parser clamps negative starts to zero; the Windows adapter preserves that policy.
        assertEquals("1\n00:00:00,000 --> 00:00:02,000\ninvalid\n\n2\n00:00:00,250 --> 00:00:01,750\n第一行\nEnglish\n\n3\n00:00:02,001 --> 00:00:03,500\n第二行\n", srt)
    }
}
