package com.android.purebilibili.data.model.response
import kotlinx.serialization.SerialName
data class ReplyData(
    //  WBI API 使用 cursor
    val cursor: ReplyCursor = ReplyCursor(),
    //  旧版 API 使用 page
    val page: ReplyPage = ReplyPage(),
    //  桌面端评论配置
    val config: ReplyConfig? = null,
    //  普通评论列表
    val replies: List<ReplyItem>? = emptyList(),
    //  二级评论详情接口会返回根评论，里面的 rcount 可作为详情页数量兜底。
    val root: ReplyItem? = null,
    //  [新增] 置顶评论列表 (WBI API)
    @SerialName("top_replies")
    val topReplies: List<ReplyItem>? = null,
    //  [新增] 热门评论列表
    val hots: List<ReplyItem>? = null,
    //  [新增] WBI API 置顶信息（top.upper/admin/vote）
    val top: ReplyTop? = null,
    //  [新增] UP主信息（包含 UP 置顶评论）
    val upper: ReplyUpper? = null,
    //  [新增] 评论输入控制（占位文案/图片上传开关）
    val control: ReplyPageControl? = null,
    @SerialName("vote_card")
    val voteCard: ReplyVoteCard? = null,
    @SerialName("grpc_next_offset")
    val grpcNextOffset: String = ""
) {
    //  统一获取总评论数
    fun getAllCount(): Int = when {
        cursor.allCount > 0 -> cursor.allCount
        page.acount > 0 -> page.acount
        else -> page.count
    }
    //  统一获取是否结束
    fun getIsEnd(currentPage: Int, currentSize: Int): Boolean {
        return if (cursor.allCount > 0) {
            cursor.isEnd
        } else {
            // 旧版 API 没有 isEnd，用页数判断
            currentSize >= page.count || page.count == 0
        }
    }
    //  [新增] 获取置顶评论（WBI 和旧版 API 兼容）
    fun collectTopReplies(): List<ReplyItem> {
        val result = mutableListOf<ReplyItem>()
        // WBI API: data.top.upper/admin/vote
        top?.upper?.let { result.add(it) }
        top?.admin?.let { result.add(it) }
        top?.vote?.let { result.add(it) }
        // 添加 UP 置顶
        upper?.top?.let { result.add(it) }
        // 添加其他置顶
        topReplies?.let { result.addAll(it) }
        return result.distinctBy { it.rpid }
    }
}

data class ReplyVoteCard(
    @SerialName("vote_id") val voteId: Long = 0L,
    val title: String = "",
    val count: Long = 0L,
    val options: List<ReplyVoteCardOption> = emptyList(),
    @SerialName("my_vote_option") val myVoteOption: Long? = null,
)

data class ReplyVoteCardOption(
    val idx: Long = 0L,
    val desc: String = "",
    val count: Long = 0L,
)
