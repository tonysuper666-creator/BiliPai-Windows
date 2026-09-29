package com.bilipai.desktop.danmaku

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
)

/** Secure streaming parser for Bilibili's public XML comment endpoint. */
object DanmakuParser {
    const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
    private const val MAX_COMMENTS = 25_000

    fun parse(xml: String): List<DanmakuComment> = parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    fun parse(input: InputStream): List<DanmakuComment> {
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
        val handler = object : DefaultHandler() {
            var pending: DanmakuComment? = null
            val text = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if (qName != "d") return
                pending = null
                text.clear()
                if (comments.size >= MAX_COMMENTS) return
                val fields = attributes.getValue("p")?.split(',') ?: return
                if (fields.size < 4) return
                val time = fields[0].toDoubleOrNull() ?: return
                val mode = fields[1].toIntOrNull() ?: return
                val size = fields[2].toIntOrNull() ?: return
                val color = fields[3].toIntOrNull() ?: return
                if (!time.isFinite() || time < 0 || mode !in setOf(1, 2, 3, 4, 5)) return
                pending = DanmakuComment(comments.size, time, mode, size.coerceIn(12, 48), color and 0xffffff, "")
            }
            override fun characters(characters: CharArray, start: Int, length: Int) {
                if (pending != null && text.length < 300) text.append(characters, start, minOf(length, 300 - text.length))
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (qName != "d") return
                pending?.let {
                    val content = text.toString().replace(Regex("[\\r\\n\\t]+"), " ").trim()
                    if (content.isNotEmpty()) comments += it.copy(text = content)
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
        return comments.sortedWith(compareBy<DanmakuComment> { it.timeSeconds }.thenBy { it.id })
    }
}
