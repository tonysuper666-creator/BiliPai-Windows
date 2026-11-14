package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.SettingsSearchResult

/** Decisions maintained during upstream merges; unknown item IDs are handled by the host. */
internal enum class DesktopPlacementTreatment { available, hidden, unavailable, replaced }

internal data class DesktopPlacementRow(
    val id: String,
    val desktopCategory: String,
    val treatment: DesktopPlacementTreatment,
    val reason: String,
    val destination: String? = null,
)

internal fun parseDesktopSettingsPlacement(
    filename: String,
    text: String,
    targetTable: Boolean,
): Map<String, DesktopPlacementRow> {
    val columns = if (targetTable) listOf("SettingsSearchTarget", "上游分区（resolveSettingsRootCategoryForSearchTarget）", "桌面分区", "落点", "处理", "原因")
        else listOf("settingId", "上游标题", "上游分区", "桌面分区", "处理", "原因")
    val lines = text.lines()
    require(lines.isNotEmpty() && lines.first().removePrefix("# ").split('\t') == columns) {
        "$filename:1: invalid or missing column header"
    }
    val rows = linkedMapOf<String, DesktopPlacementRow>()
    lines.drop(1).forEachIndexed { index, line ->
        val number = index + 2
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        val values = line.split('\t', limit = 6)
        require(values.size == 6 && values[0].isNotBlank()) { "$filename:$number: expected six columns and a non-empty ID" }
        val treatment = DesktopPlacementTreatment.entries.find { it.name == values[4] }
            ?: error("$filename:$number: invalid treatment '${values[4]}'")
        val row = DesktopPlacementRow(values[0], values[if (targetTable) 2 else 3], treatment, values[5],
            if (targetTable) values[3] else null)
        check(rows.putIfAbsent(row.id, row) == null) { "$filename:$number: duplicate ID '${row.id}'" }
    }
    require(rows.isNotEmpty()) { "$filename:1: placement table has no rows" }
    return rows
}

internal fun loadDesktopSettingsPlacement(filename: String, targetTable: Boolean): Map<String, DesktopPlacementRow> {
    val path = "settings-placement/$filename"
    val source = object {}.javaClass.classLoader.getResourceAsStream(path)
        ?: error("$filename:1: missing packaged resource $path")
    return source.bufferedReader(Charsets.UTF_8).use { parseDesktopSettingsPlacement(filename, it.readText(), targetTable) }
}

internal val desktopSettingsItemPlacement by lazy { loadDesktopSettingsPlacement("settings-item-placement.tsv", false) }
internal val desktopSettingsTargetPlacement by lazy { loadDesktopSettingsPlacement("settings-target-placement.tsv", true) }

/** Detail renderers mounted by DesktopSettingsTree; a category row never creates a detail renderer. */
internal fun desktopSettingsSearchHasMountedDetail(result: SettingsSearchResult): Boolean {
    val row = desktopSettingsTargetPlacement[result.target.name] ?: return false
    return result.page?.name == row.desktopCategory && row.treatment == DesktopPlacementTreatment.available &&
        row.destination == "detail"
}

/** Item availability is independent of the target row (ANIMATION is replaced, its available items survive). */
internal fun desktopSettingsSearchCategoryDestination(result: SettingsSearchResult): SettingsRootCategory? {
    val category = result.page ?: return null
    return if (desktopSettingsSearchHasMountedDetail(result)) null else category
}
