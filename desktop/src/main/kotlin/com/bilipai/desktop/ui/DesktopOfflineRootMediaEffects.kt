package com.bilipai.desktop.ui

import com.android.purebilibili.feature.download.OfflineMiniPlayerPayload
import com.bilipai.desktop.player.PictureInPictureController
import com.bilipai.desktop.player.WindowsMediaSession
import com.bilipai.desktop.player.WindowsMediaSnapshot

/** Adapter of the two EXISTING Root native actors. It constructs neither actor and owns no persistent state. */
internal class DesktopOfflineRootMediaEffects(
    private val systemMedia:WindowsMediaSession?,
    private val pictureInPicture:PictureInPictureController?,
    private val backend:DesktopOfflineTaskPlayerBinding,
    private val feedback:(String)->Unit,
) : DesktopOfflineMediaEffects {
    private var reportedUnavailable=false
    override fun publish(payload:OfflineMiniPlayerPayload,player:DesktopOfflineMpvControl) {
        if(!player.isOwned() || !backend.isOwned() || backend.memory.current!=player.taskId)return
        val source=player.nativePlayer.currentSourceSnapshot() ?: return
        val owner=systemMedia?.sourceLeaseFor(player.nativePlayer,source)
        val state=player.nativePlayer.state.value
        if(systemMedia!=null && owner==null)return
        systemMedia?.update(WindowsMediaSnapshot(
            title=payload.title,artist=payload.owner,mediaId=payload.bvid,state=state,isAudio=state.audioOnly,
            hasPrevious=backend.memory.previous!=null,hasNext=backend.memory.next!=null,
            enabled=state.loading||state.durationSeconds>0||state.ended,sourceLease=owner,
        )) ?: run {
            if(!reportedUnavailable){reportedUnavailable=true;feedback("Windows 系统媒体控制当前不可用")}
        }
        pictureInPicture?.updateTitle(payload.title)
        pictureInPicture?.updateQueueControls(backend.memory.previous!=null,backend.memory.next!=null)
        // Original cover is fully rendered in the UI. Existing SMTC actor has no cover-art ABI:
        // do not invent an uploaded thumbnail, another media session, or a success callback.
    }
    override fun clearIfOwned(player:DesktopOfflineMpvControl) {
        if(!player.isOwned() || !backend.isOwned() || backend.memory.current!=player.taskId)return
        pictureInPicture?.close()
        systemMedia?.update(WindowsMediaSnapshot(title="BiliPai",state=player.nativePlayer.state.value,enabled=false))
    }
}
