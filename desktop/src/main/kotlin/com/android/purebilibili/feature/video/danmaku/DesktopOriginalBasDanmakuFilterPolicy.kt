// Fixed original v0.2.9 a4b77f894d0a2dd26c0b9fc144b8adb88ac05480; app/src/main/java/com/android/purebilibili/feature/video/danmaku/BasDanmakuFilterPolicy.kt
// Raw SHA-256 363cb7000c2de1037effb1b7a638d96cf7e69f11928b5f7840de08350edaf5da; Git blob c68f44691c763ccf0795192675f1fb64a616e9ba.
package com.android.purebilibili.feature.video.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasElementType
import com.android.purebilibili.danmaku.parser.bas.BasValue
import com.android.purebilibili.danmaku.parser.bas.number

internal fun shouldDisplayBasDanmaku(
    item: BasDanmaku,
    settings: DanmakuTypeFilterSettings,
    blockedMatchers: List<DanmakuBlockRuleMatcher>,
    weightFilterLevel: Int
): Boolean {
    if (!settings.allowSpecial) return false
    if (weightFilterLevel > 0 && !item.isSelf && item.weight < weightFilterLevel) return false
    if (!settings.allowColorful && isColorfulBasDanmaku(item)) return false
    return !shouldBlockDanmakuByMatchers(item.content, blockedMatchers, item.userHash)
}

private fun isColorfulBasDanmaku(item: BasDanmaku): Boolean {
    if (isColorfulDanmaku(item.colorOverride ?: item.color)) return true
    for (element in item.program.elements) {
        val attributes = element.attributes
        when (element.type) {
            BasElementType.TEXT -> {
                val color = item.colorOverride ?: attributes.number("color", 0xFFFFFF.toDouble()).toInt()
                if (isColorfulDanmaku(color)) return true
            }
            BasElementType.BUTTON -> {
                val textColor = item.colorOverride ?: attributes.number("textColor").toInt()
                if (attributes.number("textAlpha", 1.0) > 0 && isColorfulDanmaku(textColor)) return true
                if (attributes.number("fillAlpha", 1.0) > 0 &&
                    isColorfulDanmaku(attributes.number("fillColor", 0xFFFFFF.toDouble()).toInt())) return true
            }
            BasElementType.PATH -> {
                val fillColor = item.colorOverride ?: attributes.number("fillColor", 0xFFFFFF.toDouble()).toInt()
                if (attributes.number("fillAlpha", 1.0) > 0 && isColorfulDanmaku(fillColor)) return true
                if (attributes.number("borderWidth") > 0 && attributes.number("borderAlpha", 1.0) > 0 &&
                    isColorfulDanmaku(attributes.number("borderColor").toInt())) return true
            }
        }
    }
    if (item.colorOverride == null) {
        for (transition in item.program.transitions) {
            val value = transition.properties["color"]
            val color = when (value) {
                is BasValue.Number -> value.value.toInt()
                is BasValue.Array -> (value.values.firstOrNull() as? BasValue.Number)?.value?.toInt()
                else -> null
            }
            if (color != null && isColorfulDanmaku(color)) return true
        }
    }
    return false
}
