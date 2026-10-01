package com.android.purebilibili.feature.video.share
import com.bilipai.desktop.ui.DesktopVideoShareBindings
import com.android.purebilibili.core.util.FormatUtils
import kotlinx.coroutines.*
import java.nio.file.Path
import java.nio.file.Files

internal data class VideoShareCoverFile(val path:Path,val mimeType:String) {
    val uri:java.net.URI get()=path.toUri()
}
internal suspend fun prepareVideoShareCoverFile(context:DesktopVideoShareBindings,payload:VideoSharePayload):VideoShareCoverFile? {
    val coverUrl=FormatUtils.resolveVideoCoverUrl(url=payload.coverUrl,useLowQuality=false)
    if(coverUrl.isBlank())return null
    return withContext(Dispatchers.IO) {
        try {
            val mimeType=resolveVideoShareCoverMimeType(coverUrl)
            val bytes=context.files.bytes(coverUrl)
            val bvid=payload.bvid.ifBlank {"video"}
            require(bvid.matches(Regex("[A-Za-z0-9_-]+")))
            context.files.publish("BiliPai_share_${bvid}.${resolveVideoShareCoverExtension(mimeType)}",mimeType) {target -> Files.write(target,bytes);Unit}
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:Exception) {currentCoroutineContext().ensureActive();if(!context.isOwned())throw CancellationException("分享页面已退役");null}
    }
}
__ORIGINAL_MIME__
__ORIGINAL_EXTENSION__
