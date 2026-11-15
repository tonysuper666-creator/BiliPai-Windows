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

    @Test fun srtStripsBackslashControlTagsWithoutRemovingLiteralBracesOrVttText() {
        val text = """{\an8}<b>Hello</b>{\pos(20,30)} {literal}
            |{\an1}next line{\i0}""".trimMargin()
        val timing = "00:00:01,000 --> 00:00:02,000\n"
        assertEquals(listOf(SubtitleCue(1000, 2000, "Hello {literal}\nnext line")),
            parseSubtitleDocument("controls.srt", "1\n" + timing + text))
        assertEquals(listOf(SubtitleCue(1000, 2000, """{\an8}Hello{\pos(20,30)} {literal}
            |{\an1}next line{\i0}""".trimMargin())),
            parseSubtitleDocument("literal.vtt", "WEBVTT\n\n" + timing + text))
        assertFailsWith<IllegalArgumentException> {
            parseSubtitleDocument("empty.srt", "1\n" + timing + """{\an8}{\i0}""")
        }
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
        val vtt = "WEBVTT\n\n00:00.000 --> 00:02.000\nfirst\n\n00:01.000 --> 00:03.000\nsecond\n"
        val ass = "[Events]\nFormat: Start, End, Text\n" +
            "Dialogue: 0:00:00.00,0:00:02.00,first\nDialogue: 0:00:01.00,0:00:03.00,second\n"
        val expected = listOf(SubtitleCue(0, 1000, "first"),
            SubtitleCue(1000, 2000, "first\nsecond"), SubtitleCue(2000, 3000, "second"))
        for ((name, source) in listOf("overlap.vtt" to vtt, "overlap.ass" to ass, "overlap.ssa" to ass)) {
            val cues = parseSubtitleDocument(name, source)
            assertEquals(expected, cues, name)
            assertEquals("first", resolveSubtitleTextAt(cues, 500), name)
            assertEquals("first\nsecond", resolveSubtitleTextAt(cues, 1500), name)
            assertEquals("second", resolveSubtitleTextAt(cues, 2500), name)
            assertNull(resolveSubtitleTextAt(cues, 3500), name)
        }
        // SRT follows SubripParser's original-interval output, unlike WebVTT and SSA.
        assertEquals(listOf(SubtitleCue(0, 2000, "first"), SubtitleCue(1000, 3000, "second")),
            parseSubtitleDocument("overlap.srt", vtt.removePrefix("WEBVTT\n\n")))

        // Out-of-order input, equal starts, an ending/starting boundary, a gap and nested cues.
        val events = listOf(SubtitleCue(1000, 3000, "second"), SubtitleCue(0, 2000, "first"),
            SubtitleCue(1000, 2000, "third"), SubtitleCue(2000, 3000, "fourth"),
            SubtitleCue(4000, 5000, "last"))
        val ordered = listOf(SubtitleCue(0, 1000, "first"),
            SubtitleCue(1000, 2000, "first\nsecond\nthird"),
            SubtitleCue(2000, 3000, "second\nfourth"), SubtitleCue(4000, 5000, "last"))
        assertEquals(ordered, parseSubtitleDocument("ordered.vtt",
            encodeSubtitleDocument(events, SubtitleDocumentFormat.WEBVTT)))
        val dialogueOrder = ordered.toMutableList().apply {
            this[1] = SubtitleCue(1000, 2000, "second\nfirst\nthird")
        }
        assertEquals(dialogueOrder, parseSubtitleDocument("ordered.ass",
            "[Events]\nFormat: Start, End, Text\n" + events.joinToString("\n") {
                "Dialogue: 0:00:0${it.startMs / 1000}.00,0:00:0${it.endMs / 1000}.00,${it.content}"
            }))
    }

    @Test fun assAndSsaOverlapKeepsDialogueFileOrderEvenWhenStartsAndLayersDiffer() {
        val source = """
            [Events]
            Format: Layer, Start, End, Text
            Dialogue: 9,0:00:01.00,0:00:03.00,second
            Dialogue: 0,0:00:00.00,0:00:02.00,first
            Dialogue: 1,0:00:01.00,0:00:02.00,third
        """.trimIndent()
        for (extension in listOf("ass", "ssa")) {
            val cues = parseSubtitleDocument("unordered.$extension", source)
            assertEquals(listOf(SubtitleCue(0, 1000, "first"),
                SubtitleCue(1000, 2000, "second\nfirst\nthird"),
                SubtitleCue(2000, 3000, "second")), cues, extension)
            assertEquals("first", resolveSubtitleTextAt(cues, 500), extension)
            assertEquals("second\nfirst\nthird", resolveSubtitleTextAt(cues, 1500), extension)
            assertEquals("second", resolveSubtitleTextAt(cues, 2500), extension)
            assertNull(resolveSubtitleTextAt(cues, 3500), extension)
        }
    }

    @Test fun webVttExplicitLinesPrecedeStableStartOrderedUnsetLines() {
        // Media3 accepts integer/float-percentage lines, even with a bad anchor or range.
        // A malformed setting leaves the previous valid line intact; "auto" is not numeric.
        val explicit = listOf("line:0", "line:-1", "line:+2", "line:25", "line:25%,start", "line:2,end",
            "line:50%,middle", "line:50%,center", "line:2,bad-anchor", "line:150%", "line:-10%",
            "line:NaN%", "line:Infinity%", "line:1e2%", "line:0x1.0p1%",
            "line:2147483647", "line:-2147483648", "line:2 line:auto", "line:auto line:2")
        val unset = listOf("", "line:auto", "line:bad", "line:1.5", "line:%",
            "line:bad%", "line:2147483648", "line:-2147483649", "LINE:2", "line:,start")
        for ((setting, hasLine) in explicit.map { it to true } + unset.map { it to false }) {
            val source = """
                WEBVTT

                00:01.000 --> 00:04.000
                unset-later

                00:02.000 --> 00:04.000 $setting
                candidate

                00:00.000 --> 00:04.000
                unset-early

                00:00.000 --> 00:03.000 line:-1
                explicit-early

                00:01.000 --> 00:04.000
                unset-equal
            """.trimIndent()
            val cues = parseSubtitleDocument("mixed.vtt", source)
            val middle = if (hasLine) "candidate\nexplicit-early\nunset-early\nunset-later\nunset-equal"
                else "explicit-early\nunset-early\nunset-later\nunset-equal\ncandidate"
            val last = if (hasLine) "candidate\nunset-early\nunset-later\nunset-equal"
                else "unset-early\nunset-later\nunset-equal\ncandidate"
            assertEquals(listOf(SubtitleCue(0, 1000, "explicit-early\nunset-early"),
                SubtitleCue(1000, 2000, "explicit-early\nunset-early\nunset-later\nunset-equal"),
                SubtitleCue(2000, 3000, middle), SubtitleCue(3000, 4000, last)), cues, setting)
            assertEquals(middle, resolveSubtitleTextAt(cues, 2500), setting)
            assertEquals(last, resolveSubtitleTextAt(cues, 3500), setting)
            assertNull(resolveSubtitleTextAt(cues, 4500), setting)
        }
    }
}
