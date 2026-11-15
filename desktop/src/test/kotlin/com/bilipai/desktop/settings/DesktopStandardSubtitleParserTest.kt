package com.bilipai.desktop.settings

import com.android.purebilibili.feature.video.subtitle.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Exercise the original codec entry point, including the patch and unchanged JSON path. */
class DesktopStandardSubtitleParserTest {
    @Test fun srtKeepsRealTimesAndMultilineTextAndSortsCues() {
        val source = """
            2
            01:02:03,456 --> 01:02:05,006
            第二行

            1
            00:00:01,250 --> 00:00:02,750
            Hello <b>world</b>
            下一行
        """.trimIndent()
        assertEquals(listOf(
            SubtitleCue(1250, 2750, "Hello world\n下一行"),
            SubtitleCue(3_723_456, 3_725_006, "第二行"),
        ), parseSubtitleDocument("captions.SRT", source))
    }

    @Test fun webVttHonorsIdentifiersSettingsAndMetadataWithoutPublishingTheirText() {
        val source = """
            WEBVTT description

            NOTE ignore this block
            00:00.000 --> 00:01.000
            not a cue

            STYLE
            ::cue { color: red }

            REGION
            id:example

            intro
            00:01.125 --> 00:03.500 align:start position:10%
            <v Speaker><ruby>漢<rt>かん</rt></ruby> &amp; &#x1F600;<br>line &lt;2&gt;</v>

            00:04.000 --> 00:05.000
            &quot;quoted&quot;&nbsp;&#39;next&apos;
        """.trimIndent()
        assertEquals(listOf(
            SubtitleCue(1125, 3500, "漢 & 😀\nline <2>"),
            SubtitleCue(4000, 5000, "\"quoted\" 'next'"),
        ), parseSubtitleDocument("captions.vtt", source))
    }

    @Test fun assAndSsaReadDialogueColumnsStripStyleAndKeepCommasAndLineBreaks() {
        for (extension in listOf("ass", "ssa")) {
            val source = """
                [Script Info]
                Title: text outside events is not a cue
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,not a cue
                Dialogue: 0,0:00:01.23,0:00:03.45,Default,,0,0,0,,{\i1}Hello, world{\i0}\N第二行\htext
                Dialogue: 0,0:00:04.00,0:00:04.00,Default,,0,0,0,,zero duration
                [Fonts]
                Dialogue: 0,0:00:06.00,0:00:07.00,Default,,0,0,0,,not an event
            """.trimIndent()
            assertEquals(listOf(SubtitleCue(1230, 3450, "Hello, world\n第二行 text")),
                parseSubtitleDocument("captions." + extension, source))
        }
        assertEquals(listOf(SubtitleCue(2000, 3000, "legacy")), parseSubtitleDocument("legacy.ssa", """
            [Events]
            Format: Marked, End, Start, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:03.00,0:00:02.00,Default,,0,0,0,,legacy
        """.trimIndent()))
    }

    @Test fun bomUtf8Utf16AndCrLfPassThroughTheOriginalDecoder() {
        val source = "1\r\n00:00:01,001 --> 00:00:02,002\r\n真实字幕\r\n\r\n"
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val bytes = ("\uFEFF" + source).toByteArray(charset)
            assertEquals(listOf(SubtitleCue(1001, 2002, "真实字幕")),
                parseSubtitleDocument("captions.srt", decodeSubtitleDocument(bytes)))
        }
    }

    @Test fun emptyInvalidAndOverflowingCuesAreFilteredWithoutInventingTimesOrText() {
        val source = """
            1
            00:00:01,000 --> 00:00:02,000
            <i> </i>

            2
            00:00:04,000 --> 00:00:03,000
            reversed

            3
            00:70:00,000 --> 00:71:00,000
            invalid minute

            4
            999999999999999:00:00,000 --> 999999999999999:00:01,000
            overflow

            5
            00:00:05,000 --> 00:00:05,000
            zero

            6
            00:00:06,000 --> 00:00:07,000
            valid
        """.trimIndent()
        assertEquals(listOf(SubtitleCue(6000, 7000, "valid")), parseSubtitleDocument("captions.srt", source))
        for (empty in listOf("", "not a subtitle", "WEBVTT\n\n", "[Events]\n",
            "1\n00:00:01,000 --> 00:00:02,000\n<i> </i>")) {
            assertFailsWith<IllegalArgumentException>(empty) { parseSubtitleDocument("empty.srt", empty) }
        }
    }

    @Test fun bccJsonAndExportsKeepTheOriginalCodecBehavior() {
        val bcc = """{"body":[{"from":1.25,"to":2.75,"content":"原始 JSON"}]}"""
        val cues = listOf(SubtitleCue(1250, 2750, "原始 JSON"))
        assertEquals(cues, parseSubtitleDocument("sub.bcc", bcc))
        assertEquals(cues, parseSubtitleDocument("sub.json", bcc))
        val exported = cues + SubtitleCue(4000, 5000, "A & B\n下一行")
        for (format in SubtitleDocumentFormat.entries) {
            assertEquals(exported, parseSubtitleDocument("export." + format.extension,
                encodeSubtitleDocument(exported, format)), format.name)
        }
    }

    @Test fun overlappingRealCuesArePreservedForTheTimelineConsumer() {
        assertEquals(listOf(SubtitleCue(0, 2000, "first"), SubtitleCue(1000, 3000, "second")),
            parseSubtitleDocument("overlap.vtt",
                "WEBVTT\n\n00:00.000 --> 00:02.000\nfirst\n\n00:01.000 --> 00:03.000\nsecond\n"))
    }
}
