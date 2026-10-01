// Original source app/src/main/java/com/android/purebilibili/feature/home/policy/HomeFeedScrollRestorePolicy.kt
// LF SHA256 4052122d98eeb925b932db1b0ab104ff871e0546469a3bc6bfc2a5d96be25449
package com.android.purebilibili.feature.home.policy

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.android.purebilibili.feature.home.HomeCategory
import com.android.purebilibili.feature.home.PopularSubCategory

internal data class HomeFeedScrollAnchor(
    val category: HomeCategory,
    val popularSubCategory: PopularSubCategory,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val headerOffsetPx: Float
)

internal val HomeFeedScrollAnchorSaver: Saver<HomeFeedScrollAnchor?, Any> = listSaver(
    save = { anchor ->
        if (anchor == null) {
            listOf(false)
        } else {
            listOf(
                true,
                anchor.category.name,
                anchor.popularSubCategory.name,
                anchor.firstVisibleItemIndex,
                anchor.firstVisibleItemScrollOffset,
                anchor.headerOffsetPx
            )
        }
    },
    restore = { values ->
        if (values.isEmpty() || values[0] != true) {
            null
        } else {
            HomeFeedScrollAnchor(
                category = enumValueOf(values[1] as String),
                popularSubCategory = enumValueOf(values[2] as String),
                firstVisibleItemIndex = values[3] as Int,
                firstVisibleItemScrollOffset = values[4] as Int,
                headerOffsetPx = (values[5] as Number).toFloat()
            )
        }
    }
)

internal fun captureHomeFeedScrollAnchor(
    category: HomeCategory,
    popularSubCategory: PopularSubCategory,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    headerOffsetPx: Float
): HomeFeedScrollAnchor {
    return HomeFeedScrollAnchor(
        category = category,
        popularSubCategory = popularSubCategory,
        firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
        headerOffsetPx = headerOffsetPx
    )
}

internal fun shouldRestoreHomeFeedScrollAnchor(
    isTopLevelActive: Boolean,
    hasSyncedPagerWithState: Boolean,
    anchor: HomeFeedScrollAnchor?
): Boolean {
    return isTopLevelActive && hasSyncedPagerWithState && anchor != null
}

internal fun shouldApplyHomeFeedScrollAnchor(
    currentIndex: Int,
    currentOffset: Int,
    currentHeaderOffsetPx: Float,
    anchor: HomeFeedScrollAnchor,
    offsetTolerancePx: Int = 1,
    headerTolerancePx: Float = 0.5f
): Boolean {
    if (currentIndex != anchor.firstVisibleItemIndex) return true
    if (kotlin.math.abs(currentOffset - anchor.firstVisibleItemScrollOffset) > offsetTolerancePx) {
        return true
    }
    return kotlin.math.abs(currentHeaderOffsetPx - anchor.headerOffsetPx) > headerTolerancePx
}

/**
 * Home feed should keep a stable bottom inset while the bottom bar may auto-hide
 * or briefly remount after secondary navigation. Changing contentPadding on every
 * visibility toggle reflows LazyGrid and reads as an automatic scroll jump.
 */
