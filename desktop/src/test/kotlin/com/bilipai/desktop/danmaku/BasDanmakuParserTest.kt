package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasMeasurer
import com.android.purebilibili.danmaku.parser.bas.BasTimeline
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Parser/time-line evidence only; this test does not render a Windows BAS scene. */
class BasDanmakuParserTest {
    @Test fun `XML mode nine compiles genuine DSL without flattening line comments`() {
        val source = """def text demo { content="visible BAS" x=10% }
            // Keep this newline or the next statement would become a comment.
            set demo { alpha=0 } 2s
        """.trimIndent()
        val document = DanmakuParser.parseDocument("""<i><d p="1,9,25,16711680,0,0,owner,91">$source</d></i>""")
        assertTrue(document.comments.isEmpty())
        assertTrue(document.advanced.isEmpty())
        assertEquals(1, document.size)
        val item = document.bas.single()
        assertEquals(source, item.source)
        assertEquals(91L, item.id)
        assertEquals(1000L, item.startTimeMs)
        assertEquals("owner", item.userHash)
        assertEquals(0xff0000, item.color)
        assertEquals("visible BAS", item.content)
        assertEquals(2000L, item.durationMs)
        val timeline = BasTimeline(item.program)
        val measure = BasMeasurer { state, _, _ -> state.width = 40f; state.height = 20f }
        timeline.update(1000, 800f, 450f, measure)
        assertEquals(.5f, timeline.states.single().alpha)
        assertEquals(80f, timeline.states.single().x)
        timeline.update(0, 400f, 225f, measure)
        assertEquals(1f, timeline.states.single().alpha)
        assertEquals(40f, timeline.states.single().x)
    }

    @Test fun `protobuf modes seven and nine preserve separate models and original BAS metadata`() {
        val source = "def text demo {content=\"BAS proto\"} set demo {alpha=0} 3s"
        val bytes = reply(9, source, 123, 1500, "original hash", 8, true) +
            reply(7, "[336,219,\"\",3,\"mode seven\"]", 124, 1500) +
            reply(1, "ordinary", 125, 1500) + reply(8, "arbitrary executable code", 126, 1500)
        val document = DanmakuParser.parseProtobuf(listOf(bytes))
        assertEquals(listOf("ordinary"), document.comments.map { it.text })
        assertEquals(listOf("mode seven"), document.advanced.map { it.content })
        assertEquals(.5f, document.advanced.single().startX)
        val item = document.bas.single()
        assertEquals(source, item.source)
        assertEquals(123L, item.id)
        assertEquals(1500L, item.startTimeMs)
        assertEquals("original hash", item.userHash)
        assertEquals(8, item.weight)
        assertTrue(item.isSelf)
    }

    @Test fun `invalid BAS and mode nine arrays never become ordinary scrolling text`() {
        val invalid = listOf("def text broken {content=\"unterminated}", "[336,219,\"\",3,\"legacy array\"]", "alert(1)")
        val xml = "<i>" + invalid.joinToString("") { """<d p="0,9,25,16777215">$it</d>""" } +
            """<d p="0,9,25,16777215">def text good {content="valid"}</d><d p="0,1,25,16777215">ordinary</d></i>"""
        val document = DanmakuParser.parseDocument(xml)
        assertEquals(listOf("ordinary"), document.comments.map { it.text })
        assertEquals(listOf("valid"), document.bas.map { it.content })
        assertTrue(document.advanced.isEmpty())
        val protobuf = DanmakuParser.parseProtobuf(invalid.mapIndexed { index, text -> reply(9, text, index + 1L, 0) })
        assertEquals(0, protobuf.size)
    }

    @Test fun `oversized BAS is rejected rather than compiled from a truncated valid prefix`() {
        val source = "def text prefix {content=\"must not survive truncation\"}" + " ".repeat(16_384)
        assertTrue(DanmakuParser.parseDocument("""<i><d p="0,9,25,16777215">$source</d></i>""").bas.isEmpty())
        assertTrue(DanmakuParser.parseProtobuf(listOf(reply(9, source, 1, 0))).bas.isEmpty())
    }

    @Test fun `deep scenes are bounded but delimiters inside strings and comments are not nesting`() {
        val nested = "def text a {content=\"a\"} " + "{".repeat(65) + "set a {} 1s" + "}".repeat(65)
        assertTrue(DanmakuParser.parseProtobuf(listOf(reply(9, nested, 1, 0))).bas.isEmpty())
        val source = "def text a {content=\"" + "{".repeat(80) + "\"}\n// " + "{".repeat(80) + "\nset a {alpha=0} 1s"
        assertEquals(1, DanmakuParser.parseProtobuf(listOf(reply(9, source, 1, 0))).bas.size)
    }

    @Test fun `offline windows retain independent BAS special programs over seeks and deduplicate exact IDs`() = runBlocking {
        val directory = Files.createTempDirectory("bilipai-bas-window-")
        val segments = (1..4).map { directory.resolve("segment-$it.pb") }
        val special = directory.resolve("special.pb")
        try {
            val source = "def text demo {content=\"retained scene\" duration=20m}"
            val bytes = reply(9, source, 777, 1000)
            segments.forEachIndexed { index, path ->
                Files.write(path, (if (index == 0) bytes else byteArrayOf()) +
                    reply(1, "ordinary ${index + 1}", index + 1L, index * 360_000L + 1000))
            }
            Files.write(special, bytes)
            val loader = DanmakuWindowLoader(OfflineDanmakuSource(segments, listOf(special)), 1,
                durationMs = 1_200_000)
            val initial = loader.initial()
            assertEquals(listOf(777L), initial.document.bas.map { it.id })
            assertEquals(listOf("ordinary 1", "ordinary 2"), initial.document.comments.map { it.text })
            val moved = requireNotNull(loader.move(720_000))
            assertEquals(listOf(2, 3, 4), moved.segments)
            assertEquals(listOf(777L), moved.document.bas.map { it.id })
            assertEquals(listOf("ordinary 2", "ordinary 3", "ordinary 4"), moved.document.comments.map { it.text })
            assertEquals(1_200_000L, initial.document.bas.single().durationMs)
            assertFalse(initial.document.serverDisabled)
        } finally {
            segments.forEach(Files::deleteIfExists); Files.deleteIfExists(special); Files.deleteIfExists(directory)
        }
    }

    @Test fun `missing XML IDs keep distinct BAS scripts instead of colliding at zero`() = runBlocking {
        val file = Files.createTempFile("bilipai-bas-xml-", ".xml")
        try {
            Files.writeString(file, """<i><d p="1,9,25,16777215">def text first {content="first"}</d><d p="1,9,25,16777215">def text second {content="second"}</d></i>""")
            val result = DanmakuWindowLoader(OfflineDanmakuSource(emptyList(), listOf(file)), 1).initial()
            assertEquals(listOf("first", "second"), result.document.bas.map { it.content })
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun `document compiled budget retains whole scenes while mode seven and ordinary remain separate`() {
        val source=(0 until 128).joinToString(" ") {"def text e$it {}"}
        val bytes=(1..34).fold(byteArrayOf()) {data,id->data+reply(9,source,id.toLong(),1000)}+
            reply(7,"[336,219,\"\",3,\"mode seven survives\"]",9000,1000)+reply(1,"ordinary survives",9001,1000)
        val document=DanmakuParser.parseProtobuf(listOf(bytes))
        assertEquals(DesktopBasDocumentBudget.MAX_ELEMENTS,document.bas.sumOf {it.program.elements.size})
        assertEquals(32,document.bas.size)
        assertTrue(document.bas.all {it.program.elements.size==128})
        assertEquals(listOf("mode seven survives"),document.advanced.map {it.content})
        assertEquals(listOf("ordinary survives"),document.comments.map {it.text})
    }

    @Test fun `combined special window deduplicates before applying total compiled scene budget`() = runBlocking {
        val directory=Files.createTempDirectory("bilipai-bas-budget-window-")
        val segment=directory.resolve("segment.pb");val special=directory.resolve("special.pb")
        try {
            val source=(0 until 128).joinToString(" ") {"def text e$it {}"}
            Files.write(segment,(1..32).fold(byteArrayOf()) {data,id->data+reply(9,source,id.toLong(),1000)})
            Files.write(special,reply(9,source,1,1000)+reply(9,source,99,1000))
            val result=DanmakuWindowLoader(OfflineDanmakuSource(listOf(segment),listOf(special)),1).initial()
            assertEquals((1L..32L).toList(),result.document.bas.map {it.id})
            assertEquals(DesktopBasDocumentBudget.MAX_ELEMENTS,result.document.bas.sumOf {it.program.elements.size})
        } finally {Files.deleteIfExists(segment);Files.deleteIfExists(special);Files.deleteIfExists(directory)}
    }

    @Test fun `explosive BAS default is rejected and does not stop the following ordinary or valid scene`() {
        val chain=(0 until 25).joinToString(";") {"p$it=[\"\",p${it+1},p${it+1}]"}+";p25=\"x\""
        val source="def text T($chain){content=p0} let x=T()"
        val document=DanmakuParser.parseProtobuf(listOf(reply(9,source,1,1000)+
            reply(9,"def text good {content=\"bounded\"}",2,1000)+reply(1,"ordinary",3,1000)))
        assertEquals(listOf("bounded"),document.bas.map {it.content})
        assertEquals(listOf("ordinary"),document.comments.map {it.text})
    }

    private fun reply(mode: Int, content: String, id: Long, positionMs: Long,
        hash: String = "", weight: Int = 0, self: Boolean = false): ByteArray =
        field(1, bytes = field(1, value = id) + field(2, value = positionMs) + field(3, value = mode.toLong()) +
            field(4, value = 25) + field(5, value = 0xffffff) + field(6, bytes = hash.toByteArray()) +
            field(7, bytes = content.toByteArray()) + field(9, value = weight.toLong()) + field(29, value = if (self) 1L else 0L))

    private fun field(number: Int, value: Long = 0, bytes: ByteArray? = null): ByteArray =
        if (bytes == null) varint(number * 8L) + varint(value)
        else varint(number * 8L + 2) + varint(bytes.size.toLong()) + bytes

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream()
        do {
            val next = remaining and 0x7f
            remaining = remaining ushr 7
            output.write((next or if (remaining == 0L) 0 else 0x80).toInt())
        } while (remaining != 0L)
        return output.toByteArray()
    }
}
