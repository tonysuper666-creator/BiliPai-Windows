package com.bilipai.desktop.settings

import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import java.util.Locale

/** Text document decoding only; this does not provide or emulate an Android player. */
internal object DesktopStandardSubtitleParser {
    object MimeTypes {
        const val TEXT_SSA = "text/x-ssa"
        const val TEXT_VTT = "text/vtt"
        const val APPLICATION_SUBRIP = "application/x-subrip"
    }

    fun parse(source: String, mimeType: String): List<SubtitleCue> {
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = when (mimeType) {
            MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT -> parseTimedBlocks(normalized)
            MimeTypes.TEXT_SSA -> parseAss(normalized)
            else -> throw IllegalArgumentException("不支持的字幕格式：$mimeType")
        }
        return cues.filter { it.endMs > it.startMs && it.content.isNotBlank() }.sortedBy { it.startMs }
    }

    private val timing = Regex("""^\s*(\S+)\s+-->\s+(\S+)(?:\s+.*)?$""")
    private val timestamp = Regex("""^(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{1,3})$""")

    private fun timeMs(value: String): Long? {
        val match = timestamp.matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        if (minutes >= 60 || seconds >= 60) return null
        return try {
            Math.addExact(Math.multiplyExact(hours, 3_600_000L), minutes * 60_000 + seconds * 1000 +
                match.groupValues[4].padEnd(3, '0').toLong())
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun parseTimedBlocks(source: String): List<SubtitleCue> = buildList {
        for (block in source.split(Regex("\n[\t ]*\n"))) {
            val lines = block.trim().lines()
            val first = lines.firstOrNull() ?: continue
            // These WebVTT blocks can contain text resembling a cue and must stay metadata.
            if (first == "WEBVTT" || first.startsWith("WEBVTT ") || first.startsWith("WEBVTT\t") ||
                first == "NOTE" || first.startsWith("NOTE ") || first.startsWith("NOTE\t") ||
                first == "STYLE" || first == "REGION") continue
            // Optional SRT number / VTT identifier precedes the timestamp; neither is cue text.
            val index = if (timing.matches(first)) 0 else 1
            val times = lines.getOrNull(index)?.let(timing::matchEntire) ?: continue
            val start = timeMs(times.groupValues[1]) ?: continue
            val end = timeMs(times.groupValues[2]) ?: continue
            add(SubtitleCue(start, end, plainText(lines.drop(index + 1).joinToString("\n"))))
        }
    }

    private fun parseAss(source: String): List<SubtitleCue> = buildList {
        var inEvents = false
        var fields = emptyList<String>()
        for (line in source.lineSequence().map(String::trim)) {
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                fields = emptyList()
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val startIndex = fields.indexOf("start")
            val endIndex = fields.indexOf("end")
            val textIndex = fields.indexOf("text")
            // In ASS/SSA, Text is the last field and may itself contain commas.
            if (startIndex < 0 || endIndex < 0 || textIndex != fields.lastIndex || textIndex < 0) continue
            val values = line.substringAfter(':').split(',', limit = fields.size)
            if (values.size != fields.size) continue
            val start = timeMs(values[startIndex]) ?: continue
            val end = timeMs(values[endIndex]) ?: continue
            val text = values[textIndex].replace(Regex("""\{[^}]*}"""), "")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
            add(SubtitleCue(start, end, text))
        }
    }

    private val entities = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|nbsp|quot|apos);""")

    private fun plainText(source: String): String {
        val text = source.replace(Regex("""<rt\b[^>]*>.*?</rt\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]*>"""), "")
        return entities.replace(text) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
                "quot" -> "\""; "apos" -> "'"
                else -> {
                    val codePoint = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16)
                        else entity.drop(1).toIntOrNull()
                    if (codePoint != null && Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF)
                        String(Character.toChars(codePoint)) else match.value
                }
            }
        }.replace('\u00A0', ' ').trim()
    }
}
