package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DynamicDetailResponse
import com.android.purebilibili.data.model.response.OpusContentBlock
import com.android.purebilibili.data.model.response.OpusMajor
import com.android.purebilibili.feature.audio.lyrics.parseSplLyrics
import com.android.purebilibili.feature.audio.lyrics.resolveDisplaySecondaryRows
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopOriginalTextLyricsRegressionTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Decode the actual response/modules property; its original serializer derives the opus.
    private fun decodeOpus(nodes: String): OpusMajor {
        val response = json.decodeFromString<DynamicDetailResponse>(
            """{
                "data": {"item": {"modules": [{
                    "module_type": "MODULE_TYPE_CONTENT",
                    "module_content": {"paragraphs": [{
                        "para_type": 1,
                        "text": {"nodes": [$nodes]}
                    }]}
                }]}}
            }""".trimIndent()
        )
        return assertNotNull(response.data?.item?.modules?.module_dynamic?.major?.opus)
    }

    private fun singleText(opus: OpusMajor): OpusContentBlock.Text =
        assertIs<OpusContentBlock.Text>(opus.contentBlocks.single())

    @Test
    fun blankRichTextAndOriginalKeepEmojiOnlyParagraphAndMetadata(): Unit {
        val opus = decodeOpus(
            """{"type":"TEXT_NODE_TYPE_RICH","rich":{
                "type":"RICH_TEXT_NODE_TYPE_EMOJI","text":"","orig_text":"",
                "emoji":{"text":"[emoji]","icon_url":"https://example.invalid/emote.png"}
            }}"""
        )
        val block = singleText(opus)
        assertEquals("[emoji]", block.text)
        assertEquals("[emoji]", opus.summary?.text)
        assertEquals("[emoji]", block.richTextNodes.single().emoji?.text)
        assertEquals("https://example.invalid/emote.png", block.richTextNodes.single().emoji?.icon_url)
        assertEquals("[emoji]", opus.summary?.rich_text_nodes?.single()?.text)
    }

    @Test
    fun whitespaceRichTextFallsThroughToOriginalBeforeEmoji(): Unit {
        val opus = decodeOpus(
            """{"rich":{
                "type":"RICH_TEXT_NODE_TYPE_TEXT","text":"   ","orig_text":"Original text",
                "emoji":{"text":"[fallback]","icon_url":"https://example.invalid/emote.png"}
            }}"""
        )
        assertEquals("Original text", singleText(opus).text)
        assertEquals("Original text", opus.summary?.text)
        assertEquals("Original text", singleText(opus).richTextNodes.single().orig_text)
    }

    @Test
    fun blankRichFieldsFallThroughToFormulaInActualParagraph(): Unit {
        val opus = decodeOpus(
            """{"rich":{"text":" ","orig_text":""},"formula":{"latex_content":"E=mc^2"}}"""
        )
        assertEquals("E=mc^2", singleText(opus).text)
        assertEquals("E=mc^2", opus.summary?.text)
    }

    @Test
    fun nonblankRichTextKeepsPrecedenceAndFollowingWordOrder(): Unit {
        val opus = decodeOpus(
            """{"rich":{
                "type":"RICH_TEXT_NODE_TYPE_TEXT","text":"Visible","orig_text":"Original",
                "emoji":{"text":"[fallback]","icon_url":"https://example.invalid/emote.png"}
            },"formula":{"latex_content":"ignored"}},
            {"word":{"words":" tail"}}"""
        )
        assertEquals("Visible tail", singleText(opus).text)
        assertEquals("Visible tail", opus.summary?.text)
        assertEquals("Visible", singleText(opus).richTextNodes.first().text)
    }

    @Test
    fun parsedJapaneseAndKoreanLatinRomanizationReachesDisplayRows(): Unit {
        val document = parseSplLyrics(
            primary = "[00:01.00]\u5149\u306b\u306a\u308c\n[00:03.00]\uc0ac\ub791\ud574",
            translation = "[00:01.00]Become light\n[00:03.00]I love you",
            romanization = "[00:01.00]hikari ni nare\n[00:03.00]saranghae",
        )
        assertEquals(listOf("hikari ni nare", "saranghae"), document.lines.map { it.romanization })
        assertEquals(listOf(1_000L, 3_000L), document.lines.map { it.startTimeMs })
        assertEquals(3_000L, document.lines.first().endTimeMs)
        document.lines.forEach { line ->
            val expectedTranslation = line.translations.single()
            assertEquals(
                expectedTranslation to line.romanization,
                resolveDisplaySecondaryRows(line.text, expectedTranslation, line.romanization, true),
            )
        }
    }

    @Test
    fun chineseAndLatinPrimarySuppressPhoneticSecondaryScript(): Unit {
        listOf("\u7559\u4e0b\u5fc3\u6709\u7075\u7280\u7684\u4f60", "Stay with me").forEach { primary ->
            val phoneticSecondary = "\u3053\u3053\u308d"
            val line = parseSplLyrics(
                primary = "[00:01.00]$primary",
                romanization = "[00:01.00]$phoneticSecondary",
            ).lines.single()
            assertNull(line.romanization)
            val rows = resolveDisplaySecondaryRows(primary, "Translation", phoneticSecondary, true)
            assertEquals("Translation", rows.first)
            assertNull(rows.second)
        }
    }

    @Test
    fun displayToggleHidesBothSecondaryRowsWithoutDiscardingParsedLyrics(): Unit {
        val line = parseSplLyrics(
            primary = "[00:01.00]\u5149\u306b\u306a\u308c",
            translation = "[00:01.00]Become light",
            romanization = "[00:01.00]hikari ni nare",
        ).lines.single()
        assertEquals(listOf("Become light"), line.translations)
        assertEquals("hikari ni nare", line.romanization)
        assertEquals(
            null to null,
            resolveDisplaySecondaryRows(line.text, line.translations.single(), line.romanization, false),
        )
        assertEquals("hikari ni nare", line.romanization)
    }

    @Test
    fun duplicateAndMusicNoteSecondaryRowsStayFiltered(): Unit {
        val primary = "\u5149\u306b\u306a\u308c"
        listOf(primary, "\u266ahikari ni nare\u266a").forEach { pseudo ->
            val line = parseSplLyrics(
                primary = "[00:01.00]$primary",
                translation = "[00:01.00]$pseudo",
                romanization = "[00:01.00]$pseudo",
            ).lines.single()
            assertEquals(emptyList(), line.translations)
            assertNull(line.romanization)
            assertEquals(null to null, resolveDisplaySecondaryRows(primary, pseudo, pseudo, true))
        }
    }
}
