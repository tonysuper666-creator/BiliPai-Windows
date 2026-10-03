package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.AdvancedDanmakuData
import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.danmaku.parser.DesktopAdvancedDanmakuParser
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

data class DanmakuComment(
    val id: Int,
    val timeSeconds: Double,
    val mode: Int,
    val size: Int,
    val color: Int,
    val text: String,
    val serverId: Long = 0,
    val userHash: String = "",
    val weight: Int = 0,
    val isVipGradualColor: Boolean = false,
    // Original source payload for the original list factories; display/plugin processing remains separate.
    val originalElement: DanmakuProto.DanmakuElem? = null,
    val originalXmlAttributes: String? = null,
    val originalXmlContent: String? = null,
    // Exact neutral original local item, not a fabricated server element or second list.
    val originalLocalItem: com.android.purebilibili.danmaku.engine.DanmakuItem? = null,
    val originalLocalInjectionPhase:Any? = null,
)

data class DanmakuDocument(val comments: List<DanmakuComment> = emptyList(), val advanced: List<AdvancedDanmakuData> = emptyList(), val serverDisabled: Boolean = false) {
    val size: Int get() = comments.size + advanced.size
}

/** Secure streaming parser for Bilibili's public XML comment endpoint. */
object DanmakuParser {
    const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
    private const val MAX_COMMENTS = 25_000
    private const val MAX_ADVANCED_COMMENTS = 5_000

    fun parse(xml: String): List<DanmakuComment> = parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    fun parse(input: InputStream): List<DanmakuComment> = parseDocument(input).comments

    fun parseDocument(xml: String): DanmakuDocument = parseDocument(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    fun parseDocument(input: InputStream): DanmakuDocument {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val parser = factory.newSAXParser()
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val comments = mutableListOf<DanmakuComment>()
        val advanced = mutableListOf<AdvancedDanmakuData>()
        val handler = object : DefaultHandler() {
            var pending: DanmakuComment? = null
            val text = StringBuilder()
            val sourceText = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (qName != "d") return
                pending = null
                text.clear(); sourceText.clear()
                if (comments.size >= MAX_COMMENTS && advanced.size >= MAX_ADVANCED_COMMENTS) return
                val fields = attributes.getValue("p")?.split(',') ?: return
                if (fields.size < 4) return
                val time = fields[0].toDoubleOrNull() ?: return
                val mode = fields[1].toIntOrNull() ?: return
                val size = fields[2].toIntOrNull() ?: return
                val color = fields[3].toIntOrNull() ?: return
                if (!time.isFinite() || time < 0 || (mode !in 1..7 && mode != 9)) return
                if (mode >= 7 && advanced.size >= MAX_ADVANCED_COMMENTS || mode <= 6 && comments.size >= MAX_COMMENTS) return
                pending = DanmakuComment(comments.size, time, mode, size.coerceIn(12, 48), color and 0xffffff, "",
                    serverId = fields.getOrNull(7)?.toLongOrNull() ?: 0L, userHash = fields.getOrNull(6).orEmpty().take(200),
                    originalXmlAttributes = attributes.getValue("p"))
            }
            override fun characters(characters: CharArray, start: Int, length: Int) {
                if (pending != null && pending?.mode in 1..6) sourceText.append(characters, start, length)
                val limit = if ((pending?.mode ?: 0) >= 7) 16_384 else 300
                if (pending != null && text.length < limit) text.append(characters, start, minOf(length, limit - text.length))
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (qName != "d") return
                pending?.let {
                    val content = text.toString().replace(Regex("[\\r\\n\\t]+"), " ").trim()
                    if (content.isNotEmpty()) {
                        if (it.mode >= 7) {
                            DesktopAdvancedDanmakuParser.parseAdvancedDanmaku(content, (it.timeSeconds * 1000).toLong(), it.color)
                                ?.let { parsed -> normalizeAdvanced(parsed)?.let(advanced::add) }
                        } else comments += it.copy(text = content, originalXmlContent = sourceText.toString())
                    }
                }
                pending = null
            }
        }
        parser.parse(object : FilterInputStream(input) {
            var consumed = 0
            fun count(amount: Int) {
                if (amount > 0) consumed += amount
                check(consumed <= MAX_DOCUMENT_BYTES) { "Danmaku document is too large." }
            }
            override fun read(): Int = super.read().also { if (it != -1) count(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also(::count)
        }, handler)
        return DanmakuDocument(comments.sortedWith(compareBy<DanmakuComment> { it.timeSeconds }.thenBy { it.id }), advanced.sortedBy { it.startTimeMs })
    }

    fun parseProtobuf(segments: List<ByteArray>): DanmakuDocument {
        val comments = mutableListOf<DanmakuComment>()
        val advanced = mutableListOf<AdvancedDanmakuData>()
        require(segments.sumOf { it.size.toLong() } <= MAX_DOCUMENT_BYTES) { "Danmaku window is too large." }
        var serverDisabled = false
        segments.forEach { bytes ->
            val reply = DanmakuProto.parseReply(bytes)
            if (reply.state == 1) serverDisabled = true
            reply.elems.take(MAX_COMMENTS).forEach { item ->
                if (item.progress < 0 || item.content.isBlank()) return@forEach
                when (item.mode) {
                    in 1..6 -> if (comments.size < MAX_COMMENTS) {
                        val count = item.count.coerceAtLeast(0)
                        val content = item.content.take(300).replace(Regex("[\\r\\n\\t]+"), " ")
                        comments += DanmakuComment(comments.size, item.progress / 1000.0, item.mode,
                            item.fontsize.coerceIn(12, 48), item.color and 0xffffff,
                            if (count > 1) "$content x$count" else content, item.id, item.midHash.take(200), item.weight,
                            item.colorful == DanmakuProto.DmColorfulTypeVipGradualColor, originalElement = item)
                    }
                    7, 9 -> if (advanced.size < MAX_ADVANCED_COMMENTS && item.content.length <= 16_384) {
                        DesktopAdvancedDanmakuParser.parseAdvancedDanmaku(item.content, item.progress.toLong(), item.color and 0xffffff)
                            ?.copy(id = "proto_${item.id}_${item.progress}")?.let { normalizeAdvanced(it)?.let(advanced::add) }
                    }
                }
            }
        }
        return DanmakuDocument(comments.sortedWith(compareBy<DanmakuComment> { it.timeSeconds }.thenBy { it.id }),
            advanced.distinctBy { it.id }.sortedBy { it.startTimeMs }, serverDisabled)
    }

    /** Reject pathological numeric values before they reach AWT transforms; parser functions remain upstream code. */
    private fun normalizeAdvanced(value: AdvancedDanmakuData): AdvancedDanmakuData? {
        if (value.content.isBlank() || value.durationMs <= 0 || value.startTimeMs < 0 ||
            listOf(value.startX, value.startY, value.endX, value.endY, value.alphaStart, value.alphaEnd,
                value.rotateZ, value.rotateY, value.fontSize).any { !it.isFinite() }) return null
        return value.copy(content = value.content.take(2_000), durationMs = value.durationMs.coerceAtMost(300_000),
            translationDurationMs = value.translationDurationMs.coerceIn(0, 300_000),
            translationDelayMs = value.translationDelayMs.coerceIn(0, 300_000),
            path = value.path.take(2_000), fontSize = value.fontSize.coerceIn(10f, 96f))
    }
}
