package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi

/** Explicit fixture owner for existing protocol tests. Production has no default owner/cache. */
internal fun createTestVideoEngagementProtocol(
    api: BilibiliApi,
    readCsrf: () -> String?,
    readMid: () -> Long?,
    readSessData: () -> String?,
    readAccessToken: () -> String?,
    ownerCheckpoint: () -> Unit,
    confirmFollow: (FollowStateChange) -> Unit,
    folderProtocol: DesktopOriginalFavoriteFolderProtocol,
): DesktopOriginalVideoEngagementProtocol = DesktopOriginalVideoEngagementProtocol(
    api, readCsrf, readMid, readSessData, readAccessToken, ownerCheckpoint, confirmFollow, folderProtocol,
    VideoInteractionStatusCache(), FollowingSnapshotGuard(),
    { ownerCheckpoint(); readMid() to readSessData() },
    { block -> ownerCheckpoint(); block(); ownerCheckpoint() },
)
