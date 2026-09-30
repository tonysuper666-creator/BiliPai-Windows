// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicScreenStatePolicy.kt; do not edit.
// LF-normalized SHA-256: 49dec34dcbd22dd89f1468e18b87e99e309ff646badc836174b81065d39bff0c
package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.*
import kotlin.math.max
internal const val DYNAMIC_UP_PANEL_ALL_UID = -1L
internal fun resolveDynamicUpPanelUsers(
    users: List<SidebarUser>,
    selfUid: Long,
    selfFace: String = ""
): List<SidebarUser> {
    val selfItem = selfUid.takeIf { it > 0L }?.let { uid ->
        SidebarUser(
            uid = uid,
            name = "我",
            face = selfFace
        )
    }
    val rest = users.filterNot { user ->
        user.uid == DYNAMIC_UP_PANEL_ALL_UID || (selfUid > 0L && user.uid == selfUid)
    }
    return listOfNotNull(selfItem) + rest
}

internal fun isDynamicUpPanelAllShortcut(uid: Long?): Boolean {
    return uid == DYNAMIC_UP_PANEL_ALL_UID
}

internal fun isDynamicUpPanelShortcut(uid: Long, selfUid: Long): Boolean {
    return uid == DYNAMIC_UP_PANEL_ALL_UID || (selfUid > 0L && uid == selfUid)
}

internal fun isDynamicUpPanelItemSelected(
    selectedUserId: Long?,
    itemUid: Long
): Boolean {
    return if (itemUid == DYNAMIC_UP_PANEL_ALL_UID) {
        selectedUserId == null
    } else {
        selectedUserId == itemUid
    }
}

internal fun resolveDynamicSelectedUserIdAfterClick(
    selectedUserId: Long?,
    clickedUserId: Long?
): Long? {
    if (clickedUserId == null || isDynamicUpPanelAllShortcut(clickedUserId)) return null
    // UP selection behaves like a filter/indicator, not a toggle. Re-selecting the
    // active author must keep the scoped feed intact; the top-level “全部” tab is the
    // explicit way back to the mixed timeline.
    return selectedUserId.takeIf { it == clickedUserId } ?: clickedUserId
}

internal fun shouldUseSelectedUserDynamicFeed(
    selectedTab: Int,
    selectedUserId: Long?
): Boolean {
    return selectedTab == 4 && selectedUserId != null
}

internal fun resolveDynamicSelectedUserForTab(
    selectedTab: Int,
    selectedUserId: Long?
): Long? {
    return selectedUserId.takeIf { selectedTab == 4 }
}

internal fun resolveDynamicTabAfterUserSelection(
    selectedUserId: Long?,
    clickedUserId: Long?,
    currentTab: Int
): Int {
    val nextUserId = resolveDynamicSelectedUserIdAfterClick(selectedUserId, clickedUserId)
    return when {
        nextUserId != null -> 4
        currentTab == 4 -> 0
        else -> currentTab
    }
}

internal fun resolveDynamicSelectedTab(
    savedTab: Int?,
    tabCount: Int
): Int {
    if (tabCount <= 0) return 0
    return savedTab?.takeIf { it in 0 until tabCount } ?: 0
}

internal fun resolveDynamicFeedRequestType(selectedTab: Int): String {
    return when (selectedTab) {
        1 -> "video"
        2 -> "pgc"
        3 -> "article"
        else -> "all"
    }
}

internal fun shouldShowDynamicHorizontalUserList(
    isHorizontalMode: Boolean,
    selectedTab: Int,
    allTabHorizontalUserListVisible: Boolean
): Boolean {
    if (!isHorizontalMode) return false
    return selectedTab == 4 || allTabHorizontalUserListVisible
}

internal fun resolveHorizontalUserListVerticalPaddingDp(): Int {
    return 4
}

internal fun extractUsersFromDynamicItems(items: List<DynamicItem>): List<SidebarUser> {
    val latestByUser = mutableMapOf<Long, SidebarUser>()
    // 用于防止不同 synthetic mid 伪装成相同名字+头像的重复账号刷屏
    val seenIdentities = mutableMapOf<String, Long>()

    items.forEach { item ->
        if (!isDynamicItemRealUser(item)) return@forEach
        val author = item.modules.module_author ?: return@forEach
        val identityKey = "${author.name.trim()}|${author.face.trim()}"
        val existingOwnerMid = seenIdentities[identityKey]
        if (existingOwnerMid != null && existingOwnerMid != author.mid) {
            // 已有相同名称与头像的真实账号，忽略不同 mid 的重复条目
            return@forEach
        }
        seenIdentities[identityKey] = author.mid

        val lastActive = author.pub_ts.takeIf { it > 0L } ?: 0L
        val existing = latestByUser[author.mid]
        if (existing == null || lastActive > existing.lastActiveTs) {
            latestByUser[author.mid] = SidebarUser(
                uid = author.mid,
                name = author.name,
                face = author.face,
                isLive = false,
                lastActiveTs = lastActive
            )
        }
    }
    return latestByUser.values.toList()
}

internal fun resolveMergedFollowedUsers(
    followingUsers: List<SidebarUser>,
    liveUsers: List<SidebarUser>,
    dynamicUsers: List<SidebarUser> = emptyList()
): List<SidebarUser> {
    if (followingUsers.isNotEmpty()) {
        val merged = followingUsers.associateBy { it.uid }.toMutableMap()
        // 关注的直播中 UP
        liveUsers.forEach { liveUser ->
            val existing = merged[liveUser.uid]
            if (existing != null) {
                merged[liveUser.uid] = existing.copy(
                    isLive = true,
                    lastActiveTs = max(existing.lastActiveTs, liveUser.lastActiveTs)
                )
            } else {
                // 来自 getFollowedLive 的用户本身即为已关注且正在直播，可加入
                merged[liveUser.uid] = liveUser
            }
        }
        // 动态活跃信息：仅对白名单中的关注用户进行活跃时间与信息丰富，绝不新增未关注用户
        dynamicUsers.forEach { dynamicUser ->
            val existing = merged[dynamicUser.uid]
            if (existing != null) {
                merged[dynamicUser.uid] = existing.copy(
                    name = if (dynamicUser.name.isNotBlank()) dynamicUser.name else existing.name,
                    face = if (dynamicUser.face.isNotBlank()) dynamicUser.face else existing.face,
                    lastActiveTs = max(existing.lastActiveTs, dynamicUser.lastActiveTs)
                )
            }
        }
        return merged.values.toList()
    } else {
        // 未完成全量关注加载时的兜底策略
        val merged = mutableMapOf<Long, SidebarUser>()
        val seenIdentities = mutableSetOf<String>()
        (liveUsers + dynamicUsers).forEach { user ->
            val identityKey = "${user.name.trim()}|${user.face.trim()}"
            if (identityKey.isNotBlank() && seenIdentities.contains(identityKey) && !merged.containsKey(user.uid)) {
                // 忽略相同姓名头像但不同 uid 的重复项
                return@forEach
            }
            val existing = merged[user.uid]
            if (existing == null) {
                merged[user.uid] = user
                if (identityKey.isNotBlank()) seenIdentities.add(identityKey)
            } else {
                merged[user.uid] = existing.copy(
                    name = if (user.name.isNotBlank()) user.name else existing.name,
                    face = if (user.face.isNotBlank()) user.face else existing.face,
                    isLive = existing.isLive || user.isLive,
                    lastActiveTs = max(existing.lastActiveTs, user.lastActiveTs)
                )
            }
        }
        return merged.values.toList()
    }
}

internal fun isDynamicItemRealUser(item: DynamicItem): Boolean {
    val author = item.modules.module_author ?: return false
    if (author.mid <= 0L || author.name.isBlank()) return false
    // 若 B 站接口明确标记未关注，绝不作为已关注候选
    if (author.following == false) return false

    val type = item.type.trim()
    if (type in setOf(
            "DYNAMIC_TYPE_UGC_SEASON",
            "DYNAMIC_TYPE_PGC",
            "DYNAMIC_TYPE_PGC_UNION",
            "DYNAMIC_TYPE_COURSES_SEASON"
        )
    ) {
        return false
    }

    val major = item.modules.module_dynamic?.major
    if (major?.ugc_season != null || major?.pgc != null) {
        return false
    }

    return true
}
