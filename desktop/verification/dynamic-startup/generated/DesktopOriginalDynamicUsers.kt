// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt; do not edit.
// LF-normalized SHA-256: 9981e964c6b90b51b93bfc7808490043fe60b460a8cc6491027afbbe2d0d13f5
package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.*
private const val DYNAMIC_FOLLOWINGS_PAGE_SIZE = 50
data class SidebarUser(
    val uid: Long,
    val name: String,
    val face: String,
    val isLive: Boolean = false,
    val lastActiveTs: Long = 0L,
    val isPinned: Boolean = false,
    val isHidden: Boolean = false
)

internal data class DynamicStartupLoadPlan(
    val refreshFeedImmediately: Boolean,
    val loadLiveStatusImmediately: Boolean,
    val loadFollowingsImmediately: Boolean,
    val followingsHydrationDelayMs: Long,
    val initialFollowingsPageLimit: Int
)

internal object DesktopOriginalDynamicUserPreferenceKeys {
    const val PREFS_DYNAMIC_USERS = "dynamic_user_prefs"
    const val KEY_PINNED_USERS = "dynamic_pinned_users"
    const val KEY_HIDDEN_USERS = "dynamic_hidden_users"
    const val KEY_SELECTED_TAB = "dynamic_selected_tab"
}

internal fun resolveDynamicStartupLoadPlan(): DynamicStartupLoadPlan {
    return DynamicStartupLoadPlan(
        refreshFeedImmediately = true,
        loadLiveStatusImmediately = true,
        loadFollowingsImmediately = false,
        followingsHydrationDelayMs = 1_200L,
        initialFollowingsPageLimit = 1
    )
}

internal fun resolveDynamicFollowingsPageLimit(isStartupHydration: Boolean): Int {
    return if (isStartupHydration) 1 else 3
}

internal fun hasLoadedAllDynamicFollowings(
    pageSize: Int,
    accumulatedCount: Int,
    reportedTotal: Int,
): Boolean {
    return pageSize < DYNAMIC_FOLLOWINGS_PAGE_SIZE ||
        (reportedTotal > 0 && accumulatedCount >= reportedTotal)
}

internal fun extractUsersFromFollowings(followings: List<FollowingUser>): List<SidebarUser> {
    return followings.map { user ->
        SidebarUser(
            uid = user.mid,
            name = user.uname,
            face = user.face,
            isLive = false,
            lastActiveTs = 0  // 关注列表没有活跃时间，排序优先级最低
        )
    }
}

internal fun extractUsersFromLive(rooms: List<LiveRoom>): List<SidebarUser> {
    val nowSeconds = System.currentTimeMillis() / 1000
    return rooms.map { room ->
        SidebarUser(
            uid = room.uid,
            name = room.uname,
            face = room.face,
            isLive = true,
            lastActiveTs = nowSeconds  // 直播中视作最近活跃
        )
    }
}

internal fun applyUserPreferences(users: List<SidebarUser>, pinned:Set<Long>, hidden:Set<Long>, showHidden:Boolean): List<SidebarUser> {
    return users
        .map { user ->
            user.copy(
                isPinned = pinned.contains(user.uid),
                isHidden = hidden.contains(user.uid)
            )
        }
        .filter { showHidden || !it.isHidden }
        .sortedWith(
            compareByDescending<SidebarUser> { it.isPinned }
                .thenByDescending { it.isLive }
                .thenByDescending { it.lastActiveTs }
                .thenBy { it.name }
        )
}
