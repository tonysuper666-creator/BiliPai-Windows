// GENERATED from app/src/main/java/com/android/purebilibili/core/util/FeedMergeUtils.kt; do not edit.
// LF-normalized SHA-256: 0a86cfce58fc32cde7b2d746c34f3ed60beb9ddd69f098a94ec2ccf72109ac2f
package com.android.purebilibili.core.util

/**
 * 将刷新结果按时间顺序插到顶部，并保持现有列表不被清空。
 */
fun <T, K> prependDistinctByKey(
    existing: List<T>,
    incoming: List<T>,
    keySelector: (T) -> K
): List<T> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming.distinctBy(keySelector)

    val seen = existing.asSequence().map(keySelector).toHashSet()
    val prepended = incoming.filter { item ->
        seen.add(keySelector(item))
    }
    return if (prepended.isEmpty()) existing else prepended + existing
}

/**
 * 分页加载时只追加新项，避免跨页重复。
 */
fun <T, K> appendDistinctByKey(
    existing: List<T>,
    incoming: List<T>,
    keySelector: (T) -> K
): List<T> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming.distinctBy(keySelector)

    val seen = existing.asSequence().map(keySelector).toHashSet()
    val appended = incoming.filter { item ->
        seen.add(keySelector(item))
    }
    return if (appended.isEmpty()) existing else existing + appended
}
