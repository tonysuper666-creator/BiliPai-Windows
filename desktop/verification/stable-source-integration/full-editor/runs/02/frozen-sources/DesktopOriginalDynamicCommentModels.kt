// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt; do not edit.
// LF-normalized SHA-256: b573d51d5ffd08d5f3bac2c09ab1b0f055a9494e8f92e0d9098feafa5258a517
package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.ReplyItem
import kotlinx.collections.immutable.*
enum class CommentSortMode(val apiMode: Int, val label: String) {
    HOT(3, "最热"),
    NEWEST(2, "最新");

    companion object {
        fun fromApiMode(mode: Int): CommentSortMode = entries.find { it.apiMode == mode } ?: HOT
    }
}

data class SubReplyUiState(
    val sortMode: SubReplySortMode = SubReplySortMode.TIME,
    val visible: Boolean = false,
    val rootReply: ReplyItem? = null,
    val items: ImmutableList<ReplyItem> = persistentListOf(),
    val baseItems: ImmutableList<ReplyItem> = persistentListOf(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val page: Int = 1,
    val basePage: Int = 1,
    val isEnd: Boolean = false,
    val baseIsEnd: Boolean = false,
    val error: String? = null,
    val upMid: Long = 0,
    val grpcNextOffset: String? = null,
    val baseGrpcNextOffset: String? = null,
    val conversationAnchor: ReplyItem? = null,
    val targetReplyId: Long = 0,
    // [新增] 消散动画状态
    val dissolvingIds: ImmutableSet<Long> = persistentSetOf()
)
