package com.android.purebilibili.feature.video.viewmodel

internal data class FavoriteFolderMutation(
    val addFolderIds: Set<Long>,
    val removeFolderIds: Set<Long>
)

internal data class FavoriteFolderSaveEvent(
    val aid: Long,
    val isFavorited: Boolean,
    val version: Long
)

internal fun resolveFavoriteFolderMutation(
    original: Set<Long>,
    selected: Set<Long>
): FavoriteFolderMutation {
    return FavoriteFolderMutation(
        addFolderIds = selected - original,
        removeFolderIds = original - selected
    )
}

internal fun resolveFavoriteFolderDialogTargetAid(
    requestedAid: Long?,
    currentAid: Long?
): Long? {
    return requestedAid?.takeIf { it > 0L } ?: currentAid?.takeIf { it > 0L }
}

internal fun shouldSyncFavoriteFolderUiState(
    targetAid: Long?,
    currentAid: Long?
): Boolean {
    return targetAid != null && currentAid != null && targetAid > 0L && targetAid == currentAid
}
