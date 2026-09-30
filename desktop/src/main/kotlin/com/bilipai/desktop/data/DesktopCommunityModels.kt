package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.ArticleContentBlock
import com.android.purebilibili.feature.article.opusContentBlocksToArticleBlocks
import com.android.purebilibili.feature.article.parseArticleContentBlocks
import com.android.purebilibili.feature.article.selectRicherArticleBlocks
import com.android.purebilibili.feature.message.InboxSessionPaginationPolicy

sealed interface PersonalResource {
    data class History(val item: HistoryItem) : PersonalResource
    data class Favorite(val item: FavoriteData) : PersonalResource
    data class WatchLater(val item: WatchLaterItem) : PersonalResource
}
data class PersonalResourcePage<C>(val items: List<PersonalResource>, val nextCursor: C?)

data class CommunityCollectionPage(val videos: List<VideoCard>, val nextPage: Int?, val title: String,
    val season: SeasonArchivesData? = null, val series: SeriesArchivesData? = null, val favorite: FavoriteResourceData? = null)

/** Pages retain the upstream payload, including content that is not an ordinary video. */
data class DynamicPage(val data: DynamicFeedData, val nextOffset: String?) {
    val items: List<DynamicItem> get() = data.items
}
data class SpaceVideoPage(val data: SpaceVideoData, val nextPage: Int?) {
    val items: List<SpaceVideoItem> get() = data.list.vlist
}
data class RelationPage(val data: FollowingsData, val nextPage: Int?) {
    val items: List<FollowingUser> get() = data.list.orEmpty()
}
data class CommunityCommentTarget(val oid: Long, val type: Int) {
    init { require(oid > 0 && type > 0) }
}

/** Resolve the same subjects as upstream DynamicInteractionPolicy; a forward owns its own discussion. */
fun dynamicCommentTarget(item: DynamicItem): CommunityCommentTarget? {
    fun String.positiveId() = trim().toLongOrNull()?.takeIf { it > 0 }
    val type = item.type.trim()
    if (type == "DYNAMIC_TYPE_FORWARD") item.id_str.positiveId()?.let { return CommunityCommentTarget(it, 17) }
    item.basic?.takeIf { it.comment_type > 0 }?.let { basic ->
        (basic.comment_id_str.positiveId() ?: basic.rid_str.positiveId())?.let { return CommunityCommentTarget(it, basic.comment_type) }
    }
    val major = item.modules.module_dynamic?.major
    val archive = major?.archive?.aid?.positiveId() ?: major?.pgc?.aid?.positiveId()
        ?: major?.ugc_season?.aid?.takeIf { it > 0 }
    if (archive != null) return CommunityCommentTarget(archive, 1)
    major?.draw?.id?.takeIf { it > 0 }?.let { return CommunityCommentTarget(it, 11) }
    if (major?.type == "MAJOR_TYPE_OPUS" || type in setOf("DYNAMIC_TYPE_WORD", "DYNAMIC_TYPE_DRAW",
            "DYNAMIC_TYPE_LIVE_RCMD", "DYNAMIC_TYPE_COMMON_SQUARE", "DYNAMIC_TYPE_COMMON_VERTICAL"))
        return item.id_str.positiveId()?.let { CommunityCommentTarget(it, 17) }
    return null
}

sealed interface CommunitySearchResult {
    data class Videos(val data: SearchTypeData) : CommunitySearchResult
    data class Users(val data: SearchUpData) : CommunitySearchResult
    data class Media(val data: BangumiSearchData) : CommunitySearchResult
    data class LiveRooms(val data: LiveRoomSearchData) : CommunitySearchResult
    data class LiveUsers(val data: SearchLiveUserData) : CommunitySearchResult
    data class Articles(val data: SearchArticleData) : CommunitySearchResult
    data class Topics(val data: SearchTopicData) : CommunitySearchResult
    data class Photos(val data: SearchPhotoData) : CommunitySearchResult
}
data class TypedSearchPage(val type: SearchType, val result: CommunitySearchResult, val nextPage: Int?)
data class CommunityMessageCursor(val id: Long, val time: Long) {
    init { require(id > 0 && time >= 0) }
}
data class ReplyFeedPage(val data: MessageFeedReplyData, val nextCursor: CommunityMessageCursor?)
data class MentionFeedPage(val data: MessageFeedAtData, val nextCursor: CommunityMessageCursor?)
data class LikeFeedPage(val data: MessageFeedLikeData, val nextCursor: CommunityMessageCursor?)
data class SystemNoticePage(val items: List<SystemNoticeItem>, val nextCursor: Long?)
data class CommunityUnread(val privateMessages: MessageUnreadData, val activity: MessageFeedUnreadData)
data class CommunitySessionCursor(val page: Int, val endTs: Long) {
    init { require(page > 0 && endTs >= 0) }
}
data class CommunitySessionPage(val data: SessionListData, val nextCursor: CommunitySessionCursor?)
data class CommunityMessageHistory(val data: MessageHistoryData, val nextEndSeqno: Long?)
data class PublicNotePage(val data: PublicVideoNoteListData, val nextPage: Int?)
data class ArticleDocument(val data: ArticleViewData, val blocks: List<ArticleContentBlock>,
    val opus: DynamicDetailData? = null)

internal fun communityDynamicPage(data: DynamicFeedData, previousOffset: String): DynamicPage =
    DynamicPage(data, data.offset.trim().takeIf { data.has_more && it.isNotBlank() && it != previousOffset })

internal fun communityNumberedNext(page: Int, size: Int, total: Int, itemCount: Int): Int? {
    require(page > 0 && size > 0)
    return (page + 1).takeIf { page < Int.MAX_VALUE && itemCount > 0 &&
        if (total > 0) page.toLong() * size < total else itemCount >= size }
}

internal fun communityMessageNext(cursor: MessageFeedCursor?, previous: CommunityMessageCursor?,
    itemCount: Int): CommunityMessageCursor? = cursor?.takeIf { !it.isEnd && it.id > 0 && itemCount > 0 }
    ?.let { CommunityMessageCursor(it.id, it.time.coerceAtLeast(0)) }?.takeIf { it != previous }

internal fun communitySessionPage(data: SessionListData, previous: CommunitySessionCursor): CommunitySessionPage {
    val raw = data.session_list.orEmpty()
    val next = InboxSessionPaginationPolicy.resolveNextEndTs(raw, previous.endTs)
    val cursor = if (previous.page < Int.MAX_VALUE && data.has_more == 1 && raw.isNotEmpty() && next > 0 && next != previous.endTs)
        CommunitySessionCursor(previous.page + 1, next) else null
    return CommunitySessionPage(data, cursor)
}

internal fun communityMessageHistory(data: MessageHistoryData, previousSeqno: Long): CommunityMessageHistory =
    CommunityMessageHistory(data, data.min_seqno.takeIf { data.has_more == 1 && !data.messages.isNullOrEmpty() &&
        it > 0 && (previousSeqno == 0L || it < previousSeqno) })

internal fun communityArticleDocument(data: ArticleViewData, opus: DynamicDetailData? = null): ArticleDocument {
    val viewBlocks = parseArticleContentBlocks(data.opus?.paragraphs().orEmpty(), data.content, data.ops)
    val opusBlocks = opusContentBlocksToArticleBlocks(opus?.item?.modules?.module_dynamic?.major?.opus?.contentBlocks.orEmpty())
    return ArticleDocument(data, selectRicherArticleBlocks(viewBlocks, opusBlocks), opus)
}

internal fun communitySearchNext(requestedPage: Int, responsePage: Int, totalPages: Int, resultCount: Int,
    video: Boolean): Int? {
    val loaded = maxOf(requestedPage, responsePage.coerceAtLeast(1))
    // Upstream video search continues after every nonempty page; totals are informational.
    return (loaded + 1).takeIf { loaded < Int.MAX_VALUE && resultCount > 0 && (video || loaded < totalPages) }
}
