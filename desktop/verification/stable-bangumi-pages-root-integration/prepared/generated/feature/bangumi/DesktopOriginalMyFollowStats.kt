// GENERATED from app/src/main/java/com/android/purebilibili/feature/bangumi/MyFollowStats.kt; pinned LF SHA-256 4ec993ecd0f5493c8d7d4c12e3a925c176d212d9d360aa18c772762ab3a491b1
package com.android.purebilibili.feature.bangumi

data class MyFollowStats(
    val bangumiTotal: Int = 0,
    val cinemaTotal: Int = 0
) {
    val total: Int get() = bangumiTotal + cinemaTotal

    fun totalForType(type: Int): Int {
        return if (type == MY_FOLLOW_TYPE_BANGUMI) bangumiTotal else cinemaTotal
    }
}

fun buildMyFollowStats(
    bangumiTotal: Int,
    cinemaTotal: Int
): MyFollowStats {
    return MyFollowStats(
        bangumiTotal = bangumiTotal.coerceAtLeast(0),
        cinemaTotal = cinemaTotal.coerceAtLeast(0)
    )
}
