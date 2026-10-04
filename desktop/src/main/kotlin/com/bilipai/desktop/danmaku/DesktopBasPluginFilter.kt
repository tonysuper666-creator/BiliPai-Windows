// Fixed original v0.2.9 a4b77f894d0a2dd26c0b9fc144b8adb88ac05480; app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuManager.kt
// Raw SHA-256 5951d425ad3aa159468a6c811a534552d927d77fefed8de18580fae73ce4758f; Git blob cbc074ababb0a0ca866aefba731892a243b2de2d.
package com.bilipai.desktop.danmaku

import androidx.compose.ui.graphics.toArgb
import com.android.purebilibili.core.plugin.DanmakuItem as PluginDanmakuItem
import com.android.purebilibili.core.plugin.DanmakuStyle
import com.android.purebilibili.danmaku.parser.bas.*
import com.android.purebilibili.feature.video.danmaku.*

/** Capture both ports from the same original enabled-plugin/JSON batch.
 * Style is called only after the filtered DSL has passed parsing admission.
 * Calls are synchronous stage work, outside source/account/Overlay gates. */
internal class DesktopBasPluginProcessor(
    val filter: (PluginDanmakuItem) -> PluginDanmakuItem?,
    val style: (PluginDanmakuItem) -> DanmakuStyle?,
)

internal enum class DesktopBasRejection {
    SOURCE_PREFLIGHT, DOCUMENT_BUDGET, INVALID_SCRIPT, PROGRAM_LIMIT, INVALID_STYLE,
}

/** Exactly the existing parse/document limits. No truncation, mode conversion or merge. */
private fun parseDesktopBasPluginProgram(
    item: BasDanmaku, source: String, budget: DesktopBasDocumentBudget,
    onRejected: (Long, DesktopBasRejection) -> Unit,
): BasProgram? {
    val estimate = DesktopBasParseBudget.estimate(source)
    if (estimate == null) { onRejected(item.id, DesktopBasRejection.SOURCE_PREFLIGHT); return null }
    if (!budget.reserve(estimate)) { onRejected(item.id, DesktopBasRejection.DOCUMENT_BUDGET); return null }
    return BasScriptParser.parse(source)
}

private fun retainDesktopBasPluginItem(
    original: BasDanmaku, item: BasDanmaku, budget: DesktopBasDocumentBudget,
    onRejected: (Long, DesktopBasRejection) -> Unit,
): Boolean {
    if (!item.fontScale.isFinite() || item.fontScale <= 0f) {
        onRejected(item.id, DesktopBasRejection.INVALID_STYLE); return false
    }
    if (item.program.elements.isEmpty() || item.program.elements.size > 256 || item.program.transitions.size > 1_024) {
        onRejected(item.id, DesktopBasRejection.PROGRAM_LIMIT); return false
    }
    // Changed DSL was already charged before the original parser allocated its AST.
    if (item.source == original.source) {
        val estimate = DesktopBasParseBudget.estimate(item.source)
        if (estimate == null) { onRejected(item.id, DesktopBasRejection.SOURCE_PREFLIGHT); return false }
        if (!budget.reserve(estimate)) { onRejected(item.id, DesktopBasRejection.DOCUMENT_BUDGET); return false }
    }
    if (!budget.retain(item)) { onRejected(item.id, DesktopBasRejection.DOCUMENT_BUDGET); return false }
    return true
}

private fun retainUnchangedDesktopBasBatch(
    items: List<BasDanmaku>, budget: DesktopBasDocumentBudget,
    onRejected: (Long, DesktopBasRejection) -> Unit,
): List<BasDanmaku> {
    val admitted = items.filter { retainDesktopBasPluginItem(it, it, budget, onRejected) }
    return if (admitted.size == items.size) items else admitted
}

/** Call once from the raw BAS document with one shared output budget and a captured
 * plugin batch. Refilter settings from raw input; do not reprocess last plugin output.
 * No source publication happens here. Rejections expose only item ID and a safe reason.
 * Caller still owns current-source checks, cancellation checkpoints and final install. */
internal fun filterDesktopBasDanmaku(
    items: List<BasDanmaku>, config: DanmakuSettings, processor: DesktopBasPluginProcessor?,
    budget: DesktopBasDocumentBudget, onRejected: (Long, DesktopBasRejection) -> Unit,
): List<BasDanmaku> {
        if (items.isEmpty()) return items
        val settings = DanmakuTypeFilterSettings(config.allowScroll, config.allowTop, config.allowBottom, config.allowColorful, config.allowSpecial)
        val blockedRuleMatchers = compileDanmakuBlockRules((config.blockedKeywords + config.blockedRules).distinct())
        if (!config.enabled || !settings.allowSpecial) return emptyList()
        val hasPlugins = processor != null
        if (!hasPlugins && settings.allowColorful && blockedRuleMatchers.isEmpty() && config.weightFilterLevel <= 0) {
            return retainUnchangedDesktopBasBatch(items, budget, onRejected)
        }
        val result = ArrayList<BasDanmaku>(items.size)
        for (item in items) {
            var visible = item
            if (hasPlugins) {
                // Mode 9 plugins receive the script itself; edited scripts must remain valid BAS.
                val sourceItem = PluginDanmakuItem(
                    id = item.id,
                    content = item.source,
                    timeMs = item.startTimeMs,
                    type = 9,
                    color = item.color and 0x00FFFFFF,
                    userId = item.userHash
                )
                val filtered = checkNotNull(processor).filter(sourceItem) ?: continue
                val program = if (filtered.content == item.source) item.program else try {
                    parseDesktopBasPluginProgram(item, filtered.content, budget, onRejected) ?: continue
                } catch (error: BasParseException) {
                    onRejected(item.id, DesktopBasRejection.INVALID_SCRIPT)
                    continue
                }
                val style = checkNotNull(processor).style(filtered)
                val changedColor = filtered.color.takeIf { it != sourceItem.color }
                val color = filtered.color and 0x00FFFFFF
                val colorOverride = style?.textColor?.toArgb()?.and(0x00FFFFFF) ?: changedColor
                val scale = style?.scale ?: item.fontScale
                if (filtered.content != item.source || filtered.timeMs != item.startTimeMs ||
                    color != item.color || colorOverride != item.colorOverride || scale != item.fontScale
                ) {
                    visible = item.copy(
                        source = filtered.content,
                        program = program,
                        startTimeMs = filtered.timeMs,
                        color = color,
                        colorOverride = colorOverride,
                        fontScale = scale
                    )
                }
            }
            if (shouldDisplayBasDanmaku(visible, settings, blockedRuleMatchers, config.weightFilterLevel)) {
                if (retainDesktopBasPluginItem(item, visible, budget, onRejected)) result.add(visible)
            }
        }
        return result
    }
