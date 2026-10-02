package com.android.purebilibili.feature.search
import com.android.purebilibili.data.model.response.SearchType
internal val defaultSearchFilterTabOrder: List<SearchType> = listOf(
    SearchType.VIDEO,
    SearchType.BANGUMI,
    SearchType.MEDIA_FT,
    SearchType.LIVE,
    SearchType.LIVE_USER,
    SearchType.UP,
    SearchType.ARTICLE,
    SearchType.TOPIC,
    SearchType.PHOTO
)

internal fun resolveSearchFilterTabs(
    savedOrder: List<String> = emptyList()
): List<SearchType> {
    val knownByValue = SearchType.entries.associateBy(SearchType::value)
    val ordered = savedOrder.mapNotNull(knownByValue::get).distinct()
    val remaining = (defaultSearchFilterTabOrder + SearchType.entries)
        .distinct()
        .filterNot(ordered::contains)
    return ordered + remaining
}

