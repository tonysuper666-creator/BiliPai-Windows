// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyComponents.kt; do not edit.
// LF-normalized SHA-256: c9897c512da820ad9fcee9b3f35c4915c556623723d5da3cc7f574b653c3c267
package com.android.purebilibili.feature.video.ui.components
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.foundation.text.appendInlineContent
import com.android.purebilibili.core.util.BilibiliUrlParser
import com.android.purebilibili.core.theme.calculateContrastRatio
import com.android.purebilibili.core.ui.OfficialVerifyBadgeTone
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.components.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
private val EMOTE_TOKEN_PATTERN = """\[(.*?)\]""".toRegex()
private const val COMMENT_INLINE_UP_BADGE_ID = "comment_inline_up_badge"
private const val COMMENT_INLINE_VERIFY_PERSONAL_BADGE_ID = "comment_inline_verify_personal_badge"
private const val COMMENT_INLINE_VERIFY_ORGANIZATION_BADGE_ID = "comment_inline_verify_organization_badge"
internal const val COMMENT_INLINE_TOP_BADGE_ID = "comment_inline_top_badge"
internal const val COMMENT_URL_TAG = "URL"
internal const val COMMENT_TIMESTAMP_TAG = "TIMESTAMP"
internal const val COMMENT_USER_TAG = "USER"
internal const val COMMENT_TOPIC_TAG = "TOPIC"
internal const val COMMENT_VOTE_TAG = "VOTE"

/** 富评论原生链接 payload 前缀：LinkAnnotation.Clickable 用单一 tag 承载「类型:载荷」。 */
internal const val RICH_COMMENT_LINK_URL_PREFIX = "URL:"
internal const val RICH_COMMENT_LINK_USER_PREFIX = "USER:"
internal const val RICH_COMMENT_LINK_TOPIC_PREFIX = "TOPIC:"
internal const val RICH_COMMENT_LINK_VOTE_PREFIX = "VOTE:"
internal const val RICH_COMMENT_LINK_TS_PREFIX = "TS:"

/** 构建原生 [LinkAnnotation.Clickable]；框架在 Text 内部处理点击，天然优先于划选/条目长按。 */
internal fun commentLinkAnnotation(
    payload: String,
    listener: LinkInteractionListener?,
): LinkAnnotation = LinkAnnotation.Clickable(
    tag = payload,
    styles = null,
    linkInteractionListener = listener,
)

/** 评论富文本链接动作（纯数据，供分发与测试）。 */
internal sealed interface RichCommentLinkAction {
    data class Url(val url: String) : RichCommentLinkAction
    data class User(val mid: Long) : RichCommentLinkAction
    data class Topic(val topic: String) : RichCommentLinkAction
    data class Vote(val voteId: Long) : RichCommentLinkAction
    data class Timestamp(val seconds: Long) : RichCommentLinkAction
}

internal fun resolveRichCommentLinkAction(tag: String): RichCommentLinkAction? {
    return when {
        tag.startsWith(RICH_COMMENT_LINK_URL_PREFIX) ->
            RichCommentLinkAction.Url(tag.removePrefix(RICH_COMMENT_LINK_URL_PREFIX))
        tag.startsWith(RICH_COMMENT_LINK_USER_PREFIX) ->
            tag.removePrefix(RICH_COMMENT_LINK_USER_PREFIX).toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(RichCommentLinkAction::User)
        tag.startsWith(RICH_COMMENT_LINK_TOPIC_PREFIX) ->
            tag.removePrefix(RICH_COMMENT_LINK_TOPIC_PREFIX)
                .takeIf { it.isNotBlank() }
                ?.let(RichCommentLinkAction::Topic)
        tag.startsWith(RICH_COMMENT_LINK_VOTE_PREFIX) ->
            tag.removePrefix(RICH_COMMENT_LINK_VOTE_PREFIX).toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(RichCommentLinkAction::Vote)
        tag.startsWith(RICH_COMMENT_LINK_TS_PREFIX) ->
            tag.removePrefix(RICH_COMMENT_LINK_TS_PREFIX).toLongOrNull()
                ?.let(RichCommentLinkAction::Timestamp)
        else -> null
    }
}
internal val COMMENT_TIMESTAMP_PATTERN =
    """(?<!\d)(\d{1,2})\s*[:：]\s*(\d{2})(?:\s*[:：]\s*(\d{2}))?(?!\d)""".toRegex()
internal val COMMENT_URL_PATTERN =
    """((https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|])""".toRegex()
internal val COMMENT_INLINE_BVID_PATTERN =
    Regex("""(?<![A-Za-z0-9])BV[a-zA-Z0-9]{10}(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
internal val COMMENT_VOTE_PATTERN = Regex("""\{vote:(\d+)\}""")
internal const val COLLAPSED_SUB_REPLY_PREVIEW_LIMIT = 3
const val COMMENT_PICTURE_TAG_PREFIX = "comment_picture_"
const val COMMENT_ACTION_BUTTON_TAG_PREFIX = "comment_action_button_"
const val COMMENT_SUB_REPLY_PREVIEW_TAG_PREFIX = "comment_sub_reply_preview_"
const val COMMENT_VIEW_ALL_REPLIES_TAG_PREFIX = "comment_view_all_replies_"
internal const val COMMENT_DECORATION_DECODE_MAX_PX = 512

internal data class ReplyItemLayoutPolicy(
    val horizontalPaddingDp: Int,
    val avatarSizeDp: Int,
    val avatarContentSpacingDp: Int,
    val actionButtonSizeDp: Int,
    val decorationWidthReserveDp: Int,
    val decorationImageWidthDp: Int,
    val decorationImageHeightDp: Int,
    val decorationMinWidthDp: Int
) {
    val dividerStartPaddingDp: Int
        get() = horizontalPaddingDp + avatarSizeDp + avatarContentSpacingDp
    val contentSpacingDp: Int
        get() = avatarSizeDp + avatarContentSpacingDp
}

internal fun resolveReplyItemLayoutPolicy(): ReplyItemLayoutPolicy {
    return ReplyItemLayoutPolicy(
        horizontalPaddingDp = 12,
        avatarSizeDp = 36,
        avatarContentSpacingDp = 8,
        actionButtonSizeDp = 40,
        decorationWidthReserveDp = 64,
        decorationImageWidthDp = 44,
        decorationImageHeightDp = 36,
        decorationMinWidthDp = 64
    )
}

/**
 * B站头像框素材按「外框画布 > 脸部圆形」绘制：框铺满外层，脸居中缩小，
 * 否则框与脸同尺寸会挤在边缘、前后层看起来错位。
 */
internal const val REPLY_AVATAR_FACE_FRACTION_WITH_PENDANT = 0.72f

internal fun resolveReplyAvatarFaceFraction(hasPendant: Boolean): Float {
    return if (hasPendant) REPLY_AVATAR_FACE_FRACTION_WITH_PENDANT else 1f
}

internal fun resolveReplyItemHeaderEndPaddingDp(
    hasBiliPaiDecoration: Boolean,
    policy: ReplyItemLayoutPolicy = resolveReplyItemLayoutPolicy()
): Int {
    return policy.actionButtonSizeDp + if (hasBiliPaiDecoration) policy.decorationWidthReserveDp else 0
}

internal fun resolveReplyItemContentStartPaddingDp(
    containerWidth: Dp,
    policy: ReplyItemLayoutPolicy = resolveReplyItemLayoutPolicy()
): Int {
    return if (containerWidth >= 280.dp) policy.contentSpacingDp else policy.horizontalPaddingDp
}

internal fun resolveReplyItemTextColumnWidthDp(
    containerWidthDp: Int,
    policy: ReplyItemLayoutPolicy = resolveReplyItemLayoutPolicy()
): Int {
    return (
        containerWidthDp -
            policy.horizontalPaddingDp * 2 -
            policy.avatarSizeDp -
            policy.avatarContentSpacingDp
        ).coerceAtLeast(0)
}

internal enum class ReplyLevelBadgeAsset {
    LEVEL_0,
    LEVEL_1,
    LEVEL_2,
    LEVEL_3,
    LEVEL_4,
    LEVEL_5,
    LEVEL_6,
    LEVEL_6_SENIOR
}

internal fun resolveReplyLevelBadgeAsset(
    level: Int,
    isSeniorMember: Boolean
): ReplyLevelBadgeAsset? {
    return when {
        isSeniorMember && level == 6 -> ReplyLevelBadgeAsset.LEVEL_6_SENIOR
        level == 0 -> ReplyLevelBadgeAsset.LEVEL_0
        level == 1 -> ReplyLevelBadgeAsset.LEVEL_1
        level == 2 -> ReplyLevelBadgeAsset.LEVEL_2
        level == 3 -> ReplyLevelBadgeAsset.LEVEL_3
        level == 4 -> ReplyLevelBadgeAsset.LEVEL_4
        level == 5 -> ReplyLevelBadgeAsset.LEVEL_5
        level == 6 -> ReplyLevelBadgeAsset.LEVEL_6
        else -> null
    }
}

internal fun parseCommentTimestampSeconds(match: MatchResult): Long? {
    val first = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
    val second = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
    val third = match.groupValues.getOrNull(3)?.toIntOrNull()
    return if (third != null) {
        first * 3600L + second * 60L + third
    } else {
        first * 60L + second
    }
}

internal fun collectRenderableEmoteKeys(
    text: String,
    emoteMap: Map<String, String>
): Set<String> {
    if (text.isEmpty() || emoteMap.isEmpty()) return emptySet()
    return EMOTE_TOKEN_PATTERN.findAll(text)
        .map { it.value }
        .filter { emoteMap.containsKey(it) }
        .toSet()
}

/**
 * 是否挂载 SelectionContainer。
 *
 * 有可交互注解（@/链接/话题/投票/时间戳）时必须关闭划选：SelectionContainer
 * 在存在选区（长按复制后）会消费后续点击来清除选区，把注解点击静默吞掉；
 * 长按复制走条目级操作面板，不依赖划选容器。
 */
internal fun shouldEnableRichCommentSelection(
    hasRenderableEmotes: Boolean = false,
    hasInteractiveAnnotations: Boolean = false
): Boolean = !hasInteractiveAnnotations

// 纯 Text 标签渲染成本低；滚动/播放期间保持稳定显示，对齐底栏 dragFloor 不因 motion 切换可见性。
@Suppress("UNUSED_PARAMETER")
internal fun shouldShowReplySpecialLabel(
    lightweightMode: Boolean
): Boolean = true

internal fun shouldShowReplyIdentityDecorations(enabled: Boolean): Boolean = enabled

internal fun shouldShowReplySubPreview(
    hideSubPreview: Boolean,
    lightweightMode: Boolean
): Boolean = !hideSubPreview

internal fun normalizeCollapsedSubReplyPreviewLimit(value: Int): Int = value.coerceIn(1, 10)

internal fun resolveReplySpecialLabelText(
    cardLabels: List<ReplyCardLabel>?,
    showUpFlag: Boolean,
    upAction: ReplyUpAction?
): String? {
    val serverLabel = cardLabels.orEmpty()
        .asSequence()
        .map { it.textContent.trim() }
        .firstOrNull { it.isNotEmpty() }
    if (!serverLabel.isNullOrEmpty()) return serverLabel
    return if (showUpFlag && upAction?.like == true) "UP主觉得很赞" else null
}

internal fun resolveReplyDisplayLikeCount(
    baseLikeCount: Int,
    initialAction: Int,
    isLiked: Boolean
): Int {
    return when {
        isLiked && initialAction != 1 -> baseLikeCount + 1
        !isLiked && initialAction == 1 -> baseLikeCount - 1
        else -> baseLikeCount
    }
}

internal fun resolveReplyLocationText(location: String?): String? {
    if (location.isNullOrBlank()) return null
    val cleanLocation = location
        .removePrefix("IP属地：")
        .removePrefix("IP属地")
        .trim()
    return if (cleanLocation.isNotEmpty()) "IP归属地：$cleanLocation" else null
}

internal fun buildSubReplyPreviewPrefix(
    userName: String,
    isUpComment: Boolean,
    officialVerifyTone: OfficialVerifyBadgeTone? = null
): List<String> {
    return buildList {
        add(userName)
        when (officialVerifyTone) {
            OfficialVerifyBadgeTone.PERSONAL -> {
                add(" ")
                add("[VERIFY_PERSONAL]")
            }
            OfficialVerifyBadgeTone.ORGANIZATION -> {
                add(" ")
                add("[VERIFY_ORGANIZATION]")
            }
            null -> Unit
        }
        if (isUpComment) {
            add(" ")
            add("[UP]")
        }
        add(": ")
    }
}

internal fun resolveReplyItemContentType(item: ReplyItem): String {
    return when {
        !item.cardLabels.isNullOrEmpty() -> "reply_labeled"
        !item.content.pictures.isNullOrEmpty() -> "reply_media"
        !item.replies.isNullOrEmpty() || item.rcount > 0 -> "reply_thread"
        else -> "reply_plain"
    }
}

internal fun shouldShowReplyTopBadge(
    item: ReplyItem,
    isPinned: Boolean
): Boolean = isPinned || item.replyControl?.isUpTop == true

internal fun shouldShowReplyTopAction(
    currentMid: Long,
    upMid: Long,
    item: ReplyItem
): Boolean {
    return currentMid > 0L && currentMid == upMid && item.root == 0L
}

internal fun resolveReplyTopActionLabel(isCurrentlyTop: Boolean): String {
    return if (isCurrentlyTop) "取消置顶" else "置顶"
}

internal fun resolveReplyThreadCount(item: ReplyItem): Int {
    return maxOf(
        item.count,
        item.rcount,
        item.replies.orEmpty().size
    ).coerceAtLeast(0)
}

internal fun shouldOpenReplyThreadFromRootClick(item: ReplyItem): Boolean {
    return resolveReplyThreadCount(item) > 0
}

internal fun resolveSubReplyPreviewSummaryLabel(
    replyCount: Int,
    hasUpReply: Boolean
): String {
    val count = replyCount.coerceAtLeast(0)
    return if (hasUpReply) {
        "UP主等人 共${count}条回复"
    } else {
        "共${count}条回复"
    }
}

internal fun resolveSubReplyOpenTargetId(rootReplyId: Long, clickedReplyId: Long): Long {
    return clickedReplyId.takeIf { it > 0L && it != rootReplyId } ?: 0L
}

internal fun resolveReplyCommentShareUrl(item: ReplyItem): String {
    val rootId = if (item.root > 0L) item.root else item.rpid
    return buildString {
        append("https://www.bilibili.com/video/av")
        append(item.oid)
        append("?comment_on=1&comment_root_id=")
        append(rootId)
        if (item.root > 0L && item.rpid > 0L) {
            append("&comment_secondary_id=")
            append(item.rpid)
        }
    }
}

internal fun buildReplyCommentShareText(item: ReplyItem): String {
    return buildString {
        append(item.member.uname.ifBlank { "未知用户" })
        append(": ")
        append(item.content.message.trim())
        val url = resolveReplyCommentShareUrl(item)
        if (url.isNotBlank()) {
            append('\n')
            append(url)
        }
    }
}

internal fun resolveReplyMemberMid(item: ReplyItem): Long {
    return item.member.mid.toLongOrNull()?.takeIf { it > 0L }
        ?: item.mid.takeIf { it > 0L }
        ?: 0L
}

internal fun shouldSupportReplyShare(item: ReplyItem): Boolean {
    return item.replyControl?.supportShare ?: true
}

internal enum class ReplyActionSheetAction {
    COPY_ALL,
    FREE_COPY,
    COPY_USERNAME,
    QUERY_AUTHOR_HISTORY,
    SAVE,
    SHARE,
    REPLY,
    BLOCK_USER,
    REPORT,
    CHECK_FRAUD,
    TOGGLE_TOP,
    DELETE
}

internal fun buildReplyActionSheetActions(
    canDelete: Boolean,
    canReport: Boolean,
    canShare: Boolean,
    canBlockUser: Boolean,
    topActionLabel: String? = null,
    canCopyUsername: Boolean = true,
    canQueryAuthorHistory: Boolean = false,
): List<ReplyActionSheetAction> {
    return buildList {
        add(ReplyActionSheetAction.COPY_ALL)
        add(ReplyActionSheetAction.FREE_COPY)
        if (canCopyUsername) {
            add(ReplyActionSheetAction.COPY_USERNAME)
        }
        if (canQueryAuthorHistory) add(ReplyActionSheetAction.QUERY_AUTHOR_HISTORY)
        add(ReplyActionSheetAction.SAVE)
        if (canShare) {
            add(ReplyActionSheetAction.SHARE)
        }
        add(ReplyActionSheetAction.REPLY)
        if (canBlockUser) {
            add(ReplyActionSheetAction.BLOCK_USER)
        }
        if (canReport) {
            add(ReplyActionSheetAction.REPORT)
        }
        if (canDelete) {
            add(ReplyActionSheetAction.CHECK_FRAUD)
        }
        if (!topActionLabel.isNullOrBlank()) {
            add(ReplyActionSheetAction.TOGGLE_TOP)
        }
        if (canDelete) {
            add(ReplyActionSheetAction.DELETE)
        }
    }
}

private fun resolveReplyActionSheetLabel(
    action: ReplyActionSheetAction,
    topActionLabel: String?
): String {
    return when (action) {
        ReplyActionSheetAction.COPY_ALL -> "复制全部"
        ReplyActionSheetAction.FREE_COPY -> "自由复制"
        ReplyActionSheetAction.COPY_USERNAME -> "复制用户名"
        ReplyActionSheetAction.QUERY_AUTHOR_HISTORY -> "查询作者历史"
        ReplyActionSheetAction.SAVE -> "保存评论"
        ReplyActionSheetAction.SHARE -> "分享评论"
        ReplyActionSheetAction.REPLY -> "回复"
        ReplyActionSheetAction.BLOCK_USER -> "屏蔽用户"
        ReplyActionSheetAction.REPORT -> "举报"
        ReplyActionSheetAction.CHECK_FRAUD -> "检测评论状态"
        ReplyActionSheetAction.TOGGLE_TOP -> topActionLabel.orEmpty()
        ReplyActionSheetAction.DELETE -> "删除"
    }
}

private fun isReplyActionDestructive(action: ReplyActionSheetAction): Boolean {
    return action == ReplyActionSheetAction.REPORT ||
        action == ReplyActionSheetAction.BLOCK_USER ||
        action == ReplyActionSheetAction.DELETE
}

internal fun resolveReplyCommentImageSaveToast(success: Boolean): String {
    return if (success) "评论图片已保存到相册" else "保存评论图片失败"
}

internal data class ReplyVideoReference(
    val bvid: String,
    val navigationUrl: String
)

internal fun resolveReplyVideoReference(text: String): ReplyVideoReference? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    val parsed = BilibiliUrlParser.parse(trimmed)
    val bvid = parsed.bvid?.takeIf { it.isNotBlank() } ?: return null
    val standaloneReference = trimmed.equals(bvid, ignoreCase = true) ||
        trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("bilibili://", ignoreCase = true) ||
        trimmed.startsWith("b23.tv", ignoreCase = true) ||
        trimmed.startsWith("www.bilibili.com", ignoreCase = true) ||
        trimmed.startsWith("m.bilibili.com", ignoreCase = true)
    if (!standaloneReference) return null

    return ReplyVideoReference(
        bvid = bvid,
        navigationUrl = resolveReplyVideoNavigationUrl(bvid)
    )
}

internal fun resolveReplyVideoDisplayText(
    resolvedTitle: String?,
    fallbackText: String
): String = resolvedTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: fallbackText.trim()

internal fun resolveReplyVideoNavigationUrl(bvid: String): String =
    "https://www.bilibili.com/video/$bvid"

internal fun resolveReplyTopicNavigationUrl(topic: String): String {
    val encoded = URLEncoder.encode(topic, StandardCharsets.UTF_8.name())
    return "bilibili://search?keyword=$encoded"
}

internal fun resolveReplyContentUrlNavigationUrl(
    rawToken: String,
    url: ReplyContentUrl
): String {
    // 动态/图文链接的 app_url_schema 偶尔会被服务端下发成 bilibili://video/{动态ID}。
    // 先保留可解析为动态的 Web URL，避免把动态 ID 当视频 aid 打开。
    listOf(url.url, rawToken).firstOrNull(::isReplyDynamicNavigationUrl)?.let { return it }
    return listOf(
        url.appUrlSchema,
        url.url,
        rawToken
    ).firstOrNull { it.isNotBlank() }.orEmpty()
}

private fun isReplyDynamicNavigationUrl(value: String): Boolean {
    val target = value.trim().takeIf { it.isNotEmpty() } ?: return false
    val parsed = BilibiliUrlParser.parse(target)
    return parsed.getVideoId() == null && parsed.getDynamicTargetId() != null
}

internal fun resolveReplyContentUrlDisplayText(
    rawToken: String,
    url: ReplyContentUrl
): String {
    return url.title.trim().takeIf { it.isNotEmpty() } ?: rawToken
}

internal fun resolveReplyContentUrlPrefixInlineId(rawToken: String): String {
    return "comment_url_prefix_${rawToken.hashCode()}"
}

internal data class ReplyLeadingRichTextReference(
    val label: String,
    val navigationUrl: String
)

internal fun resolveReplyNoteNavigationUrl(noteCvidStr: String): String {
    val cvid = noteCvidStr.trim().removePrefix("cv").toLongOrNull()?.takeIf { it > 0L }
    return if (cvid != null) "https://www.bilibili.com/read/cv$cvid" else ""
}

internal fun resolveReplyOpusNavigationUrl(opusId: Long): String {
    return if (opusId > 0L) "https://www.bilibili.com/opus/$opusId" else ""
}

internal fun resolveReplyLeadingRichTextReferences(
    content: ReplyContent?,
    noteCvidStr: String = ""
): List<ReplyLeadingRichTextReference> {
    content ?: return emptyList()
    val noteUrl = content.richText.note?.clickUrl?.takeIf { it.isNotBlank() }
        ?: resolveReplyNoteNavigationUrl(noteCvidStr).takeIf { it.isNotBlank() }
    if (!noteUrl.isNullOrBlank()) {
        return listOf(ReplyLeadingRichTextReference(label = "笔记 ", navigationUrl = noteUrl))
    }

    val opusUrl = resolveReplyOpusNavigationUrl(content.richText.opus?.opusId ?: 0L)
    return if (opusUrl.isNotBlank()) {
        listOf(ReplyLeadingRichTextReference(label = "笔记 ", navigationUrl = opusUrl))
    } else {
        emptyList()
    }
}

internal fun resolveReplyVoteDisplayText(
    voteId: Long,
    title: String?
): String {
    val cleanTitle = title?.trim().orEmpty()
    return if (cleanTitle.isNotEmpty()) "投票: $cleanTitle" else "投票: $voteId"
}

internal suspend fun resolveReplyVideoTitle(
    reference: ReplyVideoReference?,
    cache: MutableMap<String, String>,
    titleProvider: suspend (String) -> String?
): String? {
    reference ?: return null
    val cached = cache[reference.bvid]?.trim().takeUnless { it.isNullOrEmpty() }
    if (cached != null) return cached

    val resolved = titleProvider(reference.bvid)?.trim().takeUnless { it.isNullOrEmpty() }
    if (resolved != null) {
        cache[reference.bvid] = resolved
    }
    return resolved
}

internal fun buildRichCommentAnnotatedString(
    text: String,
    prefix: AnnotatedString? = null,
    renderableEmoteKeys: Set<String> = emptySet(),
    richUrls: Map<String, ReplyContentUrl> = emptyMap(),
    atNameToMid: Map<String, Long> = emptyMap(),
    topics: Set<String> = emptySet(),
    voteTitle: String? = null,
    leadingReferences: List<ReplyLeadingRichTextReference> = emptyList(),
    maxTimestampSeconds: Long? = null,
    color: Color = Color.Unspecified,
    timestampColor: Color = Color.Unspecified,
    urlColor: Color = Color.Unspecified,
    linkListener: LinkInteractionListener? = null
): AnnotatedString {
    return buildAnnotatedString {
        if (prefix != null) {
            append(prefix)
        }
        leadingReferences.forEach { reference ->
            if (reference.navigationUrl.isNotBlank()) {
                withLink(
                    commentLinkAnnotation(
                        payload = RICH_COMMENT_LINK_URL_PREFIX + reference.navigationUrl,
                        listener = linkListener,
                    )
                ) {
                    withStyle(SpanStyle(color = urlColor, fontWeight = FontWeight.Medium)) {
                        append(reference.label)
                    }
                }
            }
        }

        val replyPattern = "^回复 @(.*?) :".toRegex()
        val replyMatch = replyPattern.find(text)
        var startIndex = 0
        if (replyMatch != null) {
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) {
                append(replyMatch.value)
            }
            startIndex = replyMatch.range.last + 1
        }

        val remainingText = text.substring(startIndex)

        data class MatchInfo(
            val range: IntRange,
            val type: String,
            val value: String,
            val seconds: Long = 0L,
            val annotation: String = value,
            val displayText: String = value,
            val inlineContentId: String? = null,
            val priority: Int = 5
        )

        val allMatches = mutableListOf<MatchInfo>()

        fun addExactTokenMatch(
            token: String,
            type: String,
            annotation: String = token,
            displayText: String = token,
            inlineContentId: String? = null,
            priority: Int = 0
        ) {
            if (token.isBlank()) return
            var searchStart = 0
            while (searchStart < remainingText.length) {
                val index = remainingText.indexOf(token, startIndex = searchStart)
                if (index < 0) break
                allMatches.add(
                    MatchInfo(
                        range = index until index + token.length,
                        type = type,
                        value = token,
                        annotation = annotation,
                        displayText = displayText,
                        inlineContentId = inlineContentId,
                        priority = priority
                    )
                )
                searchStart = index + token.length
            }
        }

        richUrls.forEach { (token, url) ->
            addExactTokenMatch(
                token = token,
                type = "rich_url",
                annotation = resolveReplyContentUrlNavigationUrl(token, url),
                displayText = resolveReplyContentUrlDisplayText(token, url),
                inlineContentId = url.prefixIcon.takeIf { it.isNotBlank() }?.let {
                    resolveReplyContentUrlPrefixInlineId(token)
                },
                priority = 0
            )
        }

        atNameToMid.forEach { (name, mid) ->
            if (mid > 0L) {
                addExactTokenMatch(
                    token = "@$name",
                    type = "user",
                    annotation = mid.toString(),
                    priority = 0
                )
            }
        }

        topics.forEach { topic ->
            addExactTokenMatch(
                token = "#$topic#",
                type = "topic",
                annotation = topic,
                priority = 0
            )
        }

        EMOTE_TOKEN_PATTERN.findAll(remainingText).forEach { match ->
            allMatches.add(MatchInfo(match.range, "emote", match.value, priority = 1))
        }

        COMMENT_TIMESTAMP_PATTERN.findAll(remainingText).forEach { match ->
            val totalSeconds = parseCommentTimestampSeconds(match) ?: return@forEach
            if (maxTimestampSeconds != null && totalSeconds > maxTimestampSeconds) return@forEach
            allMatches.add(MatchInfo(match.range, "timestamp", match.value, totalSeconds, priority = 2))
        }

        COMMENT_VOTE_PATTERN.findAll(remainingText).forEach { match ->
            val voteId = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@forEach
            allMatches.add(
                MatchInfo(
                    range = match.range,
                    type = "vote",
                    value = match.value,
                    annotation = voteId.toString(),
                    displayText = resolveReplyVoteDisplayText(voteId, voteTitle),
                    priority = 2
                )
            )
        }

        COMMENT_URL_PATTERN.findAll(remainingText).forEach { match ->
            allMatches.add(MatchInfo(match.range, "url", match.value, priority = 3))
        }

        COMMENT_INLINE_BVID_PATTERN.findAll(remainingText).forEach { match ->
            val bvid = BilibiliUrlParser.parse(match.value).bvid?.takeIf { it.isNotBlank() } ?: return@forEach
            allMatches.add(
                MatchInfo(
                    range = match.range,
                    type = "video",
                    value = match.value,
                    annotation = resolveReplyVideoNavigationUrl(bvid),
                    priority = 4
                )
            )
        }

        allMatches.sortWith(compareBy<MatchInfo> { it.range.first }.thenBy { it.priority })

        var lastIndex = 0
        allMatches.forEach { matchInfo ->
            if (lastIndex < matchInfo.range.first) {
                append(remainingText.substring(lastIndex, matchInfo.range.first))
            }
            if (matchInfo.range.first >= lastIndex) {
                when (matchInfo.type) {
                    "emote" -> {
                        if (matchInfo.value in renderableEmoteKeys) {
                            appendInlineContent(id = matchInfo.value, alternateText = matchInfo.value)
                        } else {
                            append(matchInfo.value)
                        }
                    }

                    "timestamp" -> {
                        withLink(
                            commentLinkAnnotation(
                                payload = RICH_COMMENT_LINK_TS_PREFIX + matchInfo.seconds,
                                listener = linkListener,
                            )
                        ) {
                            withStyle(SpanStyle(color = timestampColor, fontWeight = FontWeight.Medium)) {
                                append(matchInfo.value)
                            }
                        }
                    }

                    "user" -> {
                        withLink(
                            commentLinkAnnotation(
                                payload = RICH_COMMENT_LINK_USER_PREFIX + matchInfo.annotation,
                                listener = linkListener,
                            )
                        ) {
                            withStyle(SpanStyle(color = urlColor, fontWeight = FontWeight.Medium)) {
                                append(matchInfo.value)
                            }
                        }
                    }

                    "topic" -> {
                        withLink(
                            commentLinkAnnotation(
                                payload = RICH_COMMENT_LINK_TOPIC_PREFIX + matchInfo.annotation,
                                listener = linkListener,
                            )
                        ) {
                            withStyle(SpanStyle(color = urlColor, fontWeight = FontWeight.Medium)) {
                                append(matchInfo.value)
                            }
                        }
                    }

                    "vote" -> {
                        withLink(
                            commentLinkAnnotation(
                                payload = RICH_COMMENT_LINK_VOTE_PREFIX + matchInfo.annotation,
                                listener = linkListener,
                            )
                        ) {
                            withStyle(SpanStyle(color = urlColor, fontWeight = FontWeight.Medium)) {
                                append(matchInfo.displayText)
                            }
                        }
                    }

                    "url",
                    "rich_url",
                    "video" -> {
                        withLink(
                            commentLinkAnnotation(
                                payload = RICH_COMMENT_LINK_URL_PREFIX + matchInfo.annotation,
                                listener = linkListener,
                            )
                        ) {
                            withStyle(SpanStyle(color = urlColor, textDecoration = TextDecoration.Underline)) {
                                if (matchInfo.inlineContentId != null) {
                                    appendInlineContent(id = matchInfo.inlineContentId, alternateText = " ")
                                }
                                append(matchInfo.displayText)
                            }
                        }
                    }
                }
                lastIndex = matchInfo.range.last + 1
            }
        }

        if (lastIndex < remainingText.length) {
            append(remainingText.substring(lastIndex))
        }
    }
}

internal fun resolveVisibleSubReplies(
    replies: List<ReplyItem>?,
    expanded: Boolean,
    collapsedLimit: Int = COLLAPSED_SUB_REPLY_PREVIEW_LIMIT
): List<ReplyItem> {
    val previewReplies = replies.orEmpty()
    val limit = normalizeCollapsedSubReplyPreviewLimit(collapsedLimit)
    return if (expanded) previewReplies else previewReplies.take(limit)
}

internal fun resolveInitialSubReplyPreviewExpanded(
    @Suppress("UNUSED_PARAMETER") previewReplyCount: Int
): Boolean = false

internal fun shouldShowInlineSubReplyToggle(
    previewReplyCount: Int,
    collapsedLimit: Int = COLLAPSED_SUB_REPLY_PREVIEW_LIMIT
): Boolean = previewReplyCount > normalizeCollapsedSubReplyPreviewLimit(collapsedLimit)

internal fun resolveInlineSubReplyToggleLabel(expanded: Boolean): String {
    return if (expanded) "收起回复" else "展开回复"
}

internal data class FanGroupTagVisual(
    val fanNumber: String,
    val cardBgImageUrl: String?,
    val fanColorHex: String = ""
)

internal fun resolveSailingFan(cardBgs: List<ReplySailingCardBg>): ReplySailingFan? {
    return cardBgs.asSequence()
        .mapNotNull { it.fan }
        .firstOrNull { it.numDesc.isNotBlank() || it.number > 0 }
}

internal fun resolveSailingDecorationImage(cardBgs: List<ReplySailingCardBg>): String? {
    return cardBgs.asSequence()
        .map { it.image }
        .firstOrNull { it.isNotBlank() }
}

internal fun resolveFanGroupDecorationCardBgs(member: ReplyMember): List<ReplySailingCardBg> {
    return listOfNotNull(
        member.userSailingV2?.cardBgWithFocus,
        member.userSailing?.cardBgWithFocus,
        member.userSailingV2?.cardBg,
        member.userSailing?.cardBg
    )
}

/**
 * 评论接口会同时携带传统 [ReplyMember.pendant] 与 user_sailing 的新挂件。
 * 优先使用 v2 的增强帧，能保留官方透明挂件的完整轮廓；旧字段作为兼容回退。
 */
internal fun resolveReplyMemberPendantImage(member: ReplyMember): String? {
    return sequenceOf(
        member.userSailingV2?.pendant,
        member.userSailing?.pendant,
        member.pendant
    )
        .flatMap { pendant ->
            sequenceOf(
                pendant?.imageEnhanceFrame,
                pendant?.imageEnhance,
                pendant?.image
            )
        }
        .map(::normalizeHttpImageUrl)
        .firstOrNull { it.isNotBlank() }
}

internal fun resolveFanGroupTagVisual(
    fan: ReplySailingFan?,
    cardBgImage: String?,
    fanColorHex: String? = null
): FanGroupTagVisual? {
    fan ?: return null
    val fanNumber = fan.numDesc.ifBlank {
        if (fan.number > 0) fan.number.toString().padStart(6, '0') else ""
    }
    if (fanNumber.isBlank()) return null
    return FanGroupTagVisual(
        fanNumber = fanNumber,
        cardBgImageUrl = cardBgImage?.takeIf { it.isNotBlank() },
        fanColorHex = fanColorHex?.takeIf { it.isNotBlank() } ?: fan.color
    )
}

internal fun resolveFanGroupLabelText(fanNumber: String): String {
    val digits = fanNumber.filter(Char::isDigit)
    if (digits.isBlank()) return ""
    return "CO.${digits.padStart(6, '0')}"
}

internal fun resolveFanGroupNumberText(fanNumber: String): String {
    val digits = fanNumber.filter(Char::isDigit)
    return digits.takeIf { it.isNotBlank() }?.padStart(6, '0').orEmpty()
}

internal fun resolveFanGroupLabelTextColor(
    fanColorHex: String?,
    backgroundColor: Color,
    fallbackColor: Color,
    minimumContrast: Float = 4.5f
): Color {
    val candidate = parseHexColorOrNull(fanColorHex) ?: return fallbackColor
    return if (calculateContrastRatio(candidate, backgroundColor) >= minimumContrast) {
        candidate
    } else {
        fallbackColor
    }
}

internal fun resolveFanGroupVisualFromMemberAndSailing(
    member: ReplyMember,
    cardBgs: List<ReplySailingCardBg>
): FanGroupTagVisual? {
    val sailingFan = resolveSailingFan(cardBgs)
    val legacyNumber = member.garbCardNumber.trim()
    val sailingNumber = sailingFan?.numDesc?.ifBlank {
        if (sailingFan.number > 0) sailingFan.number.toString().padStart(6, '0') else ""
    }.orEmpty()
    val fanNumber = when {
        sailingNumber.isNotBlank() -> sailingNumber
        legacyNumber.isNotBlank() -> legacyNumber
        else -> ""
    }
    if (fanNumber.isBlank()) return null

    val image = when {
        member.garbCardImageWithFocus.isNotBlank() -> member.garbCardImageWithFocus
        member.garbCardImage.isNotBlank() -> member.garbCardImage
        else -> resolveSailingDecorationImage(cardBgs).orEmpty()
    }
    val fanColorHex = member.garbCardFanColor
        .takeIf { it.isNotBlank() }
        ?: sailingFan?.color
        ?: ""

    return FanGroupTagVisual(
        fanNumber = fanNumber,
        cardBgImageUrl = image.takeIf { it.isNotBlank() },
        fanColorHex = fanColorHex
    )
}

internal fun resolveReplyPreviewTextContent(
    item: ReplyItem,
    isLiked: Boolean = item.action == 1,
    onLikeClick: (() -> Unit)? = null,
    onReplyClick: (() -> Unit)? = null
): ImagePreviewTextContent {
    val originalSizeLabels = item.content.pictures.orEmpty().map { picture ->
        resolveCommentImageOriginalSizeLabel(picture.imgSize.takeIf { it > 0f })
    }
    return ImagePreviewTextContent(
        headline = item.member.uname,
        body = item.content.message,
        placement = ImagePreviewTextPlacement.TOP_BAR,
        commentContext = ImagePreviewCommentContext(
            replyId = item.rpid,
            authorName = item.member.uname,
            avatarUrl = item.member.avatar,
            timeText = formatTime(item.ctime),
            body = item.content.message,
            originalSizeLabels = originalSizeLabels,
            likeCount = item.like,
            liked = isLiked,
            onLikeClick = onLikeClick,
            onReplyClick = onReplyClick
        )
    )
}

//  优化后的颜色常量 (使用 MaterialTheme 替代硬编码)
// private val SubReplyBgColor = Color(0xFFF7F8FA)  // OLD
// private val TextSecondaryColor = Color(0xFF9499A0)  // OLD
// private val TextTertiaryColor = Color(0xFFB2B7BF)   // OLD

internal fun normalizeHttpImageUrl(url: String?): String {
    if (url.isNullOrBlank()) return ""
    val text = url.trim()
    val lower = text.lowercase(Locale.ROOT)
    val looksLikeHostPath = !text.startsWith("/") &&
        text.substringBefore('/').contains('.')
    return when {
        text.startsWith("//") -> "https:$text"
        lower.startsWith("http://") -> "https://${text.substringAfter("://")}"
        lower.startsWith("https://") -> text
        looksLikeHostPath -> "https://$text"
        else -> text
    }
}

internal fun resolveDecorationImageUrl(url: String?): String {
    return normalizeHttpImageUrl(url)
}

private fun parseHexColorOrNull(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val text = hex.trim().removePrefix("#")
    val argb = when (text.length) {
        6 -> "FF$text"
        8 -> text
        else -> return null
    }
    return runCatching { Color(argb.toLong(16).toInt()) }.getOrNull()
}

// 评论行组合期热路径：共享 formatter，避免每条评论格式化时间都新建 SimpleDateFormat。
// 仅主线程（Compose 组合）调用，不涉及 SimpleDateFormat 的线程安全问题。

private val replyPublishDayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

fun formatTime(timestamp: Long): String {
    val date = Date(timestamp * 1000)
    return replyPublishDayFormatter.format(date)
}
