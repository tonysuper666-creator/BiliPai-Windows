// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeVideoClickRequest.kt
// LF SHA256 108cc19dfffb6fba72908eb0ce5ba26dd63b1ae6a766a53bbb608cc67d88c12e
package com.android.purebilibili.feature.home



enum class HomeVideoClickSource {
    GRID,
    TODAY_WATCH,
    PREVIEW
}

data class HomeVideoClickRequest(
    val bvid: String,
    val dynamicId: String = "",
    val cid: Long = 0L,
    val coverUrl: String = "",
    val isVerticalVideo: Boolean = false,
    val source: HomeVideoClickSource = HomeVideoClickSource.GRID,
    val sourceRoute: String? = null
)

fun resolveHomeCategoryVideoSourceRoute(category: HomeCategory): String {
    return "home?category=${category.name}"
}
