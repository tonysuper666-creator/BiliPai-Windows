package com.android.purebilibili.feature.list

import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.FavFolderSource

internal enum class FavoriteResourceOrder(
    val apiValue: String,
    val label: String
) {
    FAVORITE_TIME("mtime", "收藏时间"),
    PLAY_COUNT("view", "播放量"),
    PUBLISH_TIME("pubtime", "投稿时间")
}

internal fun canCleanInvalidFavoriteResources(folder: FavFolder?): Boolean {
    return folder?.source == FavFolderSource.OWNED
}

// attr 是属性位集合，仅最低位表示私密；其他位不影响公开状态。
internal fun isFavoriteFolderPrivate(attr: Int): Boolean = (attr and 1) != 0

internal fun resolveFavoriteCleanInvalidConfirmText(folderTitle: String): String {
    val title = folderTitle.trim().ifEmpty { "当前收藏夹" }
    return "确认清理「$title」里的失效内容吗？本操作会修改远端收藏夹。"
}
