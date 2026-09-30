package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.FollowingUser

internal fun buildBlockedUpImportItemsFromRemoteBlacks(
    users: List<FollowingUser>
): List<BlockedUpImportItem> {
    return users.mapNotNull { user ->
        val mid = user.mid.takeIf { it > 0L } ?: return@mapNotNull null
        BlockedUpImportItem(
            mid = mid,
            name = user.uname.trim().takeIf { it.isNotEmpty() } ?: "UP主$mid",
            face = user.face,
            sign = user.sign
        )
    }
}
