package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.repository.BlockedUpRelationSource
import com.android.purebilibili.data.repository.BlockedUpWriteResult
import com.android.purebilibili.feature.video.ui.components.ReplyCommentImageSpec
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.flow.Flow

/** A required adapter for the mounted existing account/subject. No transport,
 * account, preferences backing or raw reply list is constructed by a renderer. */
internal interface DesktopCommentPlatform {
    val context: DesktopPluginContext
    val collapsedReplyPreviewLimit: Int
    val subReplyLoadedCountEnabled: Flow<Boolean>
    val emotes: DesktopDynamicEmotes
    fun isOwned(): Boolean
    fun pickCommentImages(maxItems: Int, onSelected: (List<String>) -> Unit)
    fun showFeedback(message: String)
    fun copyText(text: String, label: String)
    fun shareText(text: String, title: String)
    suspend fun videoTitle(bvid: String): Result<String?>
    suspend fun translateReply(type: Long, oid: Long, rpid: Long): Result<String?>
    suspend fun blockUser(mid: Long, name: String, face: String, relationSource: BlockedUpRelationSource): BlockedUpWriteResult
    suspend fun saveCommentImage(spec: ReplyCommentImageSpec): Boolean
}

internal val LocalDesktopCommentBindings = staticCompositionLocalOf<DesktopCommentPlatform> {
    error("Original reply owner is not mounted")
}
