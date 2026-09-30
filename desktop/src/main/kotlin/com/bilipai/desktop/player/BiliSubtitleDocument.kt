package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.subtitle.parseBiliSubtitleBody

/** Converts the upstream subtitle body into a local document that libmpv can render. */
object BiliSubtitleDocument {
    fun toSrt(document: String): String {
        require(document.length <= 8 * 1024 * 1024) { "Subtitle document is too large." }
        val lines = parseBiliSubtitleBody(document)
        require(lines.size <= 50_000) { "Subtitle document contains too many cues." }
        val cues = lines.filter { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= MAX_MILLIS }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))
            .mapNotNull { line ->
                val text = line.content.replace("\r\n", "\n").replace('\r', '\n')
                    .replace("\u0000", "").lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n").trim()
                text.takeIf(String::isNotEmpty)?.let { line.copy(content = it.take(5_000)) }
            }
        return cues.mapIndexed { index, cue ->
            "${index + 1}\n${timecode(cue.startMs)} --> ${timecode(cue.endMs)}\n${cue.content}\n"
        }.joinToString("\n")
    }

    private fun timecode(millis: Long): String {
        return "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT,
            millis / 3_600_000, millis / 60_000 % 60, millis / 1_000 % 60, millis % 1_000)
    }

    private const val MAX_MILLIS = 7 * 24 * 60 * 60 * 1_000L
}
