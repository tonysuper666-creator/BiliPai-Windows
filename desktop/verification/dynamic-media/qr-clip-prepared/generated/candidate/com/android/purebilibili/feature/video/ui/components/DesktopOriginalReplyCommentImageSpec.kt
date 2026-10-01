// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyCommentImageSaver.kt; do not edit.
// LF-normalized SHA-256: 2c7ab6898c5bfe9b334ba2b71393fe69c0dbe77050b964e41a3892245119f19d
package com.android.purebilibili.feature.video.ui.components
import com.android.purebilibili.data.model.response.ReplyItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
internal data class ReplyCommentImageSpec(
    val authorName: String,
    val message: String,
    val metadataText: String,
    val qrUrl: String,
    val footerText: String,
    val generatedAtText: String
)

internal fun buildReplyCommentImageSpec(
    item: ReplyItem,
    generatedAtMillis: Long = System.currentTimeMillis()
): ReplyCommentImageSpec {
    val url = resolveReplyCommentShareUrl(item)
    val likeText = item.like.takeIf { it > 0 }?.let { "${it}赞" }
    val metadata = listOfNotNull(
        formatTime(item.ctime).takeIf { item.ctime > 0L },
        likeText
    ).joinToString(" · ")
    val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(generatedAtMillis))
    return ReplyCommentImageSpec(
        authorName = item.member.uname.ifBlank { "未知用户" },
        message = item.content.message.trim().ifBlank { "（空评论）" },
        metadataText = metadata,
        qrUrl = url,
        footerText = "识别二维码，查看评论",
        generatedAtText = generatedAt
    )
}
