// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicRichTextPolicy.kt; do not edit.
// LF-normalized SHA-256: 475f01d32fbae8b8c1641b0eb6ce5d5cc72f52a729e65b8a90ac444ed104749d
package com.android.purebilibili.feature.dynamic.components
import com.android.purebilibili.data.model.response.RichTextNode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight

internal fun resolveDynamicRichTextTopicId(node: RichTextNode): Long? {
    node.rid
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let { return it }

    val jumpUrl = node.jump_url.orEmpty()
    DYNAMIC_TOPIC_QUERY_ID_PATTERN.find(jumpUrl)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?.let { return it }

    return DYNAMIC_TOPIC_PATH_ID_PATTERN.find(jumpUrl)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
}

private val DYNAMIC_TOPIC_QUERY_ID_PATTERN =
    """(?:[?&](?:topic_id|topicId)=)(\d+)""".toRegex(RegexOption.IGNORE_CASE)
private val DYNAMIC_TOPIC_PATH_ID_PATTERN =
    """(?:topic|topic-detail)/(\d+)""".toRegex(RegexOption.IGNORE_CASE)

internal const val DYNAMIC_RICH_TEXT_LINK_URL_PREFIX = "URL:"
internal const val DYNAMIC_RICH_TEXT_LINK_USER_PREFIX = "USER:"
internal const val DYNAMIC_RICH_TEXT_LINK_USER_NAME_PREFIX = "USERNAME:"
internal const val DYNAMIC_RICH_TEXT_LINK_VOTE_PREFIX = "VOTE:"
internal const val DYNAMIC_RICH_TEXT_LINK_TOPIC_ID_PREFIX = "TOPIC:"
internal const val DYNAMIC_RICH_TEXT_LINK_TOPIC_KEYWORD_PREFIX = "TOPICKW:"

internal sealed interface DynamicRichTextLinkAction {
    data class Url(val url: String) : DynamicRichTextLinkAction
    data class User(val mid: Long) : DynamicRichTextLinkAction
    data class UserName(val name: String) : DynamicRichTextLinkAction
    data class Vote(val voteId: Long) : DynamicRichTextLinkAction
    data class TopicId(val topicId: Long) : DynamicRichTextLinkAction
    data class TopicKeyword(val keyword: String) : DynamicRichTextLinkAction
}

internal fun resolveDynamicRichTextLinkAction(tag: String): DynamicRichTextLinkAction? {
    return when {
        tag.startsWith(DYNAMIC_RICH_TEXT_LINK_URL_PREFIX) ->
            DynamicRichTextLinkAction.Url(tag.removePrefix(DYNAMIC_RICH_TEXT_LINK_URL_PREFIX))
        tag.startsWith(DYNAMIC_RICH_TEXT_LINK_USER_PREFIX) ->
            tag.removePrefix(DYNAMIC_RICH_TEXT_LINK_USER_PREFIX).toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(DynamicRichTextLinkAction::User)
        tag.startsWith(DYNAMIC_RICH_TEXT_LINK_USER_NAME_PREFIX) ->
            tag.removePrefix(DYNAMIC_RICH_TEXT_LINK_USER_NAME_PREFIX)
                .takeIf { it.isNotBlank() }
                ?.let(DynamicRichTextLinkAction::UserName)
        tag.startsWith(DYNAMIC_RICH_TEXT_LINK_VOTE_PREFIX) ->
            tag.removePrefix(DYNAMIC_RICH_TEXT_LINK_VOTE_PREFIX).toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(DynamicRichTextLinkAction::Vote)
        tag.startsWith(DYNAMIC_RICH_TEXT_LINK_TOPIC_ID_PREFIX) ->
            tag.removePrefix(DYNAMIC_RICH_TEXT_LINK_TOPIC_ID_PREFIX).toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(DynamicRichTextLinkAction::TopicId)
        tag.startsWith(DYNAMIC_RICH_TEXT_LINK_TOPIC_KEYWORD_PREFIX) ->
            tag.removePrefix(DYNAMIC_RICH_TEXT_LINK_TOPIC_KEYWORD_PREFIX)
                .takeIf { it.isNotBlank() }
                ?.let(DynamicRichTextLinkAction::TopicKeyword)
        else -> null
    }
}

internal fun resolveDynamicRichTextNodeToken(node: RichTextNode): String {
    return when {
        node.text.isNotBlank() -> node.text
        node.orig_text.isNotBlank() -> node.orig_text
        node.emoji?.text?.isNotBlank() == true -> node.emoji.text
        else -> ""
    }
}

internal fun dynamicRichTextLinkAnnotation(
    payload: String,
    listener: LinkInteractionListener?,
): LinkAnnotation = LinkAnnotation.Clickable(
    tag = payload,
    styles = null,
    linkInteractionListener = listener,
)

internal fun AnnotatedString.Builder.appendDynamicRichTextTopic(
    node: RichTextNode,
    primaryColor: Color,
    linkListener: LinkInteractionListener?,
) {
    val displayToken = resolveDynamicRichTextNodeToken(node)
    val keyword = displayToken.trim().removePrefix("#").removeSuffix("#").trim()
    val topicId = resolveDynamicRichTextTopicId(node)
    // 带 topicId 优先跳话题详情；无 id 时回落关键词搜索。原生链接单一 tag
    // 承载其中一种载荷，由 [resolveDynamicRichTextLinkAction] 解析。
    val payload = when {
        topicId != null -> DYNAMIC_RICH_TEXT_LINK_TOPIC_ID_PREFIX + topicId
        keyword.isNotEmpty() -> DYNAMIC_RICH_TEXT_LINK_TOPIC_KEYWORD_PREFIX + keyword
        else -> null
    }
    if (payload != null) {
        withLink(dynamicRichTextLinkAnnotation(payload, linkListener)) {
            withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.SemiBold)) {
                append(displayToken)
            }
        }
    } else {
        withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.SemiBold)) {
            append(displayToken)
        }
    }
}
