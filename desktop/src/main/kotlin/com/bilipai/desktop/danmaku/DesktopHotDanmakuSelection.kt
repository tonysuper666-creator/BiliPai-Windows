package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuItem

/** The entries the counted hot bar shows, which the ordinary renderer must not draw a second time.
 *
 * Windows port of the Android engine's DanmakuVisibility (v0.4.6, f31c3b83): a scheduled item is hidden when its server
 * id is selected, or when its content before any duplicate-merge count suffix equals a selected text. Hidden items keep
 * their schedule slot and lifetime, so closing the bar brings them back where they would be. The value is immutable and
 * copies the shown entries; the renderer reads it together with the hot bar reservation of the same source.
 */
internal data class DesktopHotDanmakuSelection(val ids: Set<Long>, val texts: Set<String>) {
    val isEmpty: Boolean get() = ids.isEmpty() && texts.isEmpty()

    fun isVisible(danmakuId: Long, text: String? = null): Boolean =
        danmakuId !in ids && (text == null || text !in texts)

    /** The pre-merge content of a merged comment is its original text, never "text x3". */
    fun isVisible(comment: DanmakuComment): Boolean =
        isVisible(comment.serverId, comment.originalRenderItem?.mergedOriginalText ?: comment.text)

    companion object {
        val NONE = DesktopHotDanmakuSelection(emptySet(), emptySet())

        fun of(items: List<DanmakuItem>): DesktopHotDanmakuSelection =
            if (items.isEmpty()) NONE else DesktopHotDanmakuSelection(
                items.mapTo(HashSet(items.size)) { it.danmakuId },
                items.mapTo(HashSet(items.size)) { it.text.orEmpty() },
            )
    }
}
