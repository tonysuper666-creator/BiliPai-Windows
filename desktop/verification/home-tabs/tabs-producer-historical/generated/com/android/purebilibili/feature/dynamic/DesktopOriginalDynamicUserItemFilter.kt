// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicInteractionPolicy.kt; do not edit.
// LF-normalized SHA-256: 0322b2835924409fbb3716524d292c040cdcdec15769b19e272e52bd7839b6f7
package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.DynamicItem

internal fun shouldIncludeDynamicItemInVideoTab(item: DynamicItem): Boolean {
    return when (item.type.trim()) {
        "DYNAMIC_TYPE_AV",
        "DYNAMIC_TYPE_UGC_SEASON" -> true
        else -> {
            val major = item.modules.module_dynamic?.major
            (major?.archive != null || major?.ugc_season != null) &&
                !shouldIncludeDynamicItemInPgcTab(item)
        }
    }
}

internal fun shouldIncludeDynamicItemInPgcTab(item: DynamicItem): Boolean {
    if (item.type.trim() in setOf(
            "DYNAMIC_TYPE_PGC",
            "DYNAMIC_TYPE_PGC_UNION"
        )
    ) {
        return true
    }

    val major = item.modules.module_dynamic?.major
    return major?.type == "MAJOR_TYPE_PGC" || major?.pgc != null
}

internal fun shouldIncludeDynamicItemInArticleTab(item: DynamicItem): Boolean {
    return when (item.type.trim()) {
        "DYNAMIC_TYPE_ARTICLE",
        "DYNAMIC_TYPE_DRAW",
        "DYNAMIC_TYPE_WORD" -> true
        else -> {
            val major = item.modules.module_dynamic?.major
            major?.opus != null || major?.draw != null || major?.article != null
        }
    }
}
