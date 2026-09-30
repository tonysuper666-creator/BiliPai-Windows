// GENERATED from app/src/main/java/com/android/purebilibili/data/repository/LiveRepository.kt; do not edit.
// LF-normalized SHA-256: 28748c0fd10125e603a8c0f28fff63781f87d3350e1446cc355622f447f8f86d
package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.response.*

internal fun desktopOriginalDynamicFollowedLiveUsers(resp:FollowedLiveResponse):List<LiveRoom> {
    val followedRooms = resp.data?.list
        ?.filter { it.liveStatus == 1 }
        ?: emptyList()

    val liveRooms = followedRooms.map { it.toLiveRoom() }
    return liveRooms.distinctBy { it.roomid }
}
