package com.bilipai.desktop.settings

import com.android.purebilibili.core.util.PinyinUtils
import com.android.purebilibili.feature.settings.SettingsSearchResult
import com.bilipai.desktop.ui.DesktopWindowsSettingsStrings

/*
 * Item-level matching over desktopSettingsItemDirectory. The tiers are the ones of the upstream v0.4.6 index
 * (SettingsSearchPolicy.scoreSettingsSearchMatch): exact title 7, exact alias 6, prefix 4, substring 3, pinyin 2, description
 * text 1. Three Windows differences. Upstream's tier 5 (a query that merely contains a whole alias or title) is left out: it
 * would turn a query for a removed phone control into the neighbouring page (通知权限 would list the notification rows), and
 * the Windows catalog never matched that way either. The description tier also reads the group heading but not the rail
 * title (a query for 屏蔽 must not list every row of the privacy page). And a query of several words matches a row when every
 * word matches it. The section-level catalog (DesktopWindowsSettingsCatalog) is not changed: it keeps its own results, and
 * this index adds the rows.
 */

/** One matched row: the breadcrumb and the description snippet are what the result row shows under the title. */
internal data class DesktopSettingsItemHit(
    val item: DesktopSettingsItem,
    val tier: Int,
    val path: String,
    val snippet: String,
) {
    /** Path, then the snippet, on separate lines, as the upstream result row shows them. */
    val subtitle: String get() = listOf(path, snippet).filter { it.isNotBlank() }.joinToString("\n")

    /** The same record the section results use, so opening a row goes through the one original dispatch. */
    fun toSearchResult(): SettingsSearchResult = SettingsSearchResult(
        item.target, item.title, item.description,
        item.group ?: DesktopWindowsSettingsStrings.railTitle(item.category).orEmpty(), item.focus,
    )
}

internal const val DESKTOP_SETTINGS_ITEM_SEARCH_LIMIT = 20
private const val ITEM_SNIPPET_LIMIT = 140
private const val ITEM_SNIPPET_LEAD = 40
private val ITEM_SEPARATOR_PATTERN = Regex("[\\s/&+\\-_:：·()（）]+")
private val ITEM_WHITESPACE_PATTERN = Regex("\\s+")

private data class ItemMatch(val tier: Int, val coverage: Int)

/** Upstream's normalisation: no spaces and none of the separators, lower case. */
internal fun normalizeDesktopSettingsSearchText(value: String): String =
    value.trim().lowercase().replace(ITEM_SEPARATOR_PATTERN, "")

/** Latin queries of two or more letters also match the pinyin of a title or alias (full pinyin or initials). */
private fun pinyinMatches(text: String, normalizedQuery: String): Boolean =
    normalizedQuery.length >= 2 && normalizedQuery.all { it.code < 128 && it.isLetterOrDigit() } &&
        PinyinUtils.matches(text = text.replace(" ", ""), query = normalizedQuery)

private fun scoreAgainst(item: DesktopSettingsItem, normalized: String): ItemMatch? {
    if (normalized.isEmpty()) return null
    val title = normalizeDesktopSettingsSearchText(item.title)
    val aliases = item.aliases.map(::normalizeDesktopSettingsSearchText).filter(String::isNotEmpty)
    if (title == normalized) return ItemMatch(7, normalized.length)
    if (normalized in aliases) return ItemMatch(6, normalized.length)
    if (title.startsWith(normalized) || aliases.any { it.startsWith(normalized) }) return ItemMatch(4, normalized.length)
    if (title.contains(normalized) || aliases.any { it.contains(normalized) }) return ItemMatch(3, normalized.length)
    if (pinyinMatches(item.title, normalized) || item.aliases.any { pinyinMatches(it, normalized) }) return ItemMatch(2, normalized.length)
    val text = listOfNotNull(item.description, item.group).map(::normalizeDesktopSettingsSearchText)
    if (text.any { it.contains(normalized) }) return ItemMatch(1, normalized.length)
    return null
}

private fun scoreItem(item: DesktopSettingsItem, query: String): ItemMatch? {
    scoreAgainst(item, normalizeDesktopSettingsSearchText(query))?.let { return it }
    val words = query.split(ITEM_WHITESPACE_PATTERN).filter { it.isNotEmpty() }
    if (words.size < 2) return null
    val parts = words.map { word -> scoreAgainst(item, normalizeDesktopSettingsSearchText(word)) ?: return null }
    return ItemMatch(parts.minOf { it.tier }, parts.sumOf { it.coverage })
}

/** The part of a long description around the query; a description of at most 140 characters is shown whole. */
internal fun desktopSettingsDescriptionSnippet(description: String, query: String): String {
    if (description.length <= ITEM_SNIPPET_LIMIT) return description
    val queryIndex = description.indexOf(query.trim(), ignoreCase = true).coerceAtLeast(0)
    val start = (queryIndex - ITEM_SNIPPET_LEAD).coerceAtLeast(0)
    val end = (start + ITEM_SNIPPET_LIMIT).coerceAtMost(description.length)
    return (if (start > 0) "…" else "") + description.substring(start, end) + if (end < description.length) "…" else ""
}

/**
 * The rows that match [query], best tier first, then the longest match, then directory order (which is page order).
 * A blank query and a non-positive limit give nothing.
 */
internal fun resolveDesktopSettingsItemSearch(
    query: String,
    maxResults: Int = DESKTOP_SETTINGS_ITEM_SEARCH_LIMIT,
    directory: List<DesktopSettingsItem> = desktopSettingsItemDirectory,
): List<DesktopSettingsItemHit> {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || maxResults <= 0) return emptyList()
    return directory.withIndex()
        .mapNotNull { (index, item) -> scoreItem(item, trimmed)?.let { Triple(it, index, item) } }
        .sortedWith(compareByDescending<Triple<ItemMatch, Int, DesktopSettingsItem>> { it.first.tier }
            .thenByDescending { it.first.coverage }
            .thenBy { it.second })
        .take(maxResults)
        .map { (match, _, item) ->
            DesktopSettingsItemHit(item, match.tier, desktopSettingsItemPath(item),
                desktopSettingsDescriptionSnippet(item.description, trimmed))
        }
}

/**
 * The section results that still add something under the rows: a section whose page and group anchor a shown row already
 * opens is the same destination, so it is left out. With no rows the sections stay exactly as they are.
 */
internal fun desktopSettingsVisibleSectionResults(
    sections: List<SettingsSearchResult>,
    hits: List<DesktopSettingsItemHit>,
): List<SettingsSearchResult> {
    if (hits.isEmpty()) return sections
    val covered = hits.map { it.item.target to it.item.focus }.toSet()
    return sections.filterNot { (it.target to it.focusId) in covered }
}
