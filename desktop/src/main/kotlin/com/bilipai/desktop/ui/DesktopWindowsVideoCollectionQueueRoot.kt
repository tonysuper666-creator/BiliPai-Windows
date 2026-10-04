package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.video.player.PlaylistUiState
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.VideoDetails
import com.bilipai.desktop.data.VideoPart
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences

/** Source-owned native presentations borrowing the existing comment/account and playlist authorities. */
@Composable internal fun DesktopWindowsVideoCollectionQueueRoot(
    presentation:DesktopWindowsVideoCollectionQueuePresentation,
    playlist:DesktopOriginalVideoPlaylistBinding,
    onPlayQueue:(List<VideoCard>,VideoCard)->Unit,
    feedback:(String)->Unit,
    share:(String,String,()->Boolean)->Unit,
    admit:(()->Unit)->Boolean,
) {
    val owner=LocalDesktopOriginalCommentRootOwner.current
    val context=checkNotNull(LocalDesktopDynamicTimelinePreferences.current).context
    key(presentation.assembly,presentation.sourceOwner,owner) {
        val latest by rememberUpdatedState(presentation)
        val currentQueue by rememberUpdatedState(onPlayQueue)
        val currentFeedback by rememberUpdatedState(feedback)
        val currentShare by rememberUpdatedState(share)
        val currentAdmission by rememberUpdatedState(admit)
        fun owned()=owner.isOwned() && latest.stillOwned() && latest.assembly.native.isCurrent(latest.sourceOwner)
        val bindings=remember {DesktopCollectionBindings(context,owner.operations,::owned,
            {currentFeedback(it)},{title,text,owns->currentShare(title,text,owns)},
            {action->owner.operations.withOwnedEditorImageAdmission {if(owned())action()}})}
        if(presentation.showCollection && owned()) {
            val info=presentation.success.info
            val details=remember(info){VideoDetails(info.bvid,info.aid,info.title,info.desc,info.pic,
                info.owner.name,info.stat.view.toLong(),info.stat.like.toLong(),
                info.pages.map {VideoPart(it.cid,it.part,it.duration.toLong())},info.owner.mid,info)}
            DesktopWindowsVideoCollectionSheetHost(details,info.cid,bindings,presentation.sourceOwner,::owned,
                {items,selected->if(owned())currentAdmission {if(owned()) {
                    val part=info.pages.firstOrNull {it.cid==selected.preferredCid}
                    if(selected.bvid==info.bvid && part!=null) {
                        // Reuse the same original load entry as Windows playPart;
                        // choosing another part must not push a second detail route.
                        presentation.assembly.playback.loadVideo(info.bvid,info.aid,force=true,cid=part.cid)
                    } else currentQueue(items,selected)
                }}},
                presentation.dismissCollection)
        }
        if(presentation.showPlaybackQueue && owned()) {
            val session=remember{playlist.captureSession()}
            val sessionIdentity=remember(session){Any()}
            fun state()=PlaylistUiState(playlist.playMode.value,playlist.playlist.value,playlist.currentIndex.value,
                playlist.isExternalPlaylist.value,playlist.externalPlaylistSource.value,playlist.shuffleEnabled.value)
            val snapshot=remember{DesktopWindowsVideoQueueSnapshot(sessionIdentity,presentation.sourceOwner,state())}
            DesktopWindowsVideoPlaybackQueueHost(snapshot,
                {owned() && playlist.isSessionCurrent(session)},
                onSelect={expected,index,item->
                    var selected=false
                    if(owned())currentAdmission {
                        if(owned() && playlist.isSessionCurrent(session) && desktopWindowsVideoQueueSelectionIsCurrent(
                                expected,sessionIdentity,presentation.sourceOwner,state(),index,item)) {
                            playlist.playAtIfCurrent(session,expected.state.playlist,index,item)?.let {chosen->
                                presentation.assembly.playback.loadVideo(chosen.bvid,cid=chosen.cid)
                                selected=true
                            }
                        }
                    }
                    selected
                },onDismiss=presentation.dismissPlaybackQueue)
        }
    }
}
