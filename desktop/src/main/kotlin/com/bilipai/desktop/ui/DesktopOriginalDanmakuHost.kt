package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalDanmakuSession
import com.android.purebilibili.danmaku.engine.DanmakuItem
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.settings.DesktopDanmakuBlockPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Direct consumption of the original standard factories. This is an ephemeral projection of the sole raw document. */
internal fun originalDanmakuPoolItems(snapshot:com.bilipai.desktop.danmaku.DanmakuPoolSourceSnapshot):List<DanmakuItem> =
    snapshot.comments.mapNotNull { comment ->
        comment.originalLocalItem ?: comment.originalElement?.let(DesktopOriginalDanmakuItemParser::createTextDataFromProto)
            ?: if(comment.originalXmlAttributes!=null&&comment.originalXmlContent!=null)
                DesktopOriginalDanmakuItemParser.createTextData(comment.originalXmlAttributes,comment.originalXmlContent)
            else null
    }

/** Original manager click ownership rule; numeric hash fallback is not replaced with arbitrary CRC guesses. */
internal fun DesktopOriginalDanmakuSession.showOriginalDanmakuItem(item:DanmakuItem,currentMid:Long) {
    val hash=resolveDanmakuClickUserHash(item.userHash)
    val self=item.isSelf || resolveDanmakuClickIsSelf(hash,currentMid)
    showDanmakuMenu(item.danmakuId,item.text.orEmpty(),hash,self)
}

/** Root mounts one instance for the live source. It owns the existing list-entry Boolean, seek controller and platform callbacks. */
@Composable
internal fun DesktopOriginalDanmakuHost(
    overlay:DanmakuOverlay,
    environment:DesktopDanmakuSessionEnvironment,
    session:DesktopOriginalDanmakuSession,
    platform:DesktopDanmakuPlatform,
    blockPreferences:DesktopDanmakuBlockPreferences,
    settingsScope:DanmakuSettingsScope,
    showPool:Boolean,
    currentPositionMs:Long,
    onDismissPool:()->Unit,
    currentBlockRulesRaw:()->String,
) {
    val revision by overlay.poolSourceRevision.collectAsState()
    val snapshot=remember(overlay,environment,revision){overlay.poolSourceFor(environment.cid,environment.sourceVersion)}
    val items=remember(snapshot){snapshot?.let(::originalDanmakuPoolItems).orEmpty()}
    val liked by session.likedDanmakuIds.collectAsState()
    val menu by session.danmakuMenuState.collectAsState()
    val rules by remember(blockPreferences,settingsScope){blockPreferences.getDanmakuBlockRulesRaw(settingsScope)}.collectAsState(currentBlockRulesRaw())
    val scope=environment.scope
    fun block(target:DanmakuBlockActionTarget,text:String) {
        if(!environment.isOwned())return
        val rules=currentBlockRulesRaw()
        val next=if(target==DanmakuBlockActionTarget.KEYWORD)appendDanmakuKeywordBlockRule(rules,text) else appendDanmakuUserHashBlockRule(rules,text)
        val changed=next!=rules
        scope.launch {
            try {blockPreferences.setDanmakuBlockRulesRaw(next,settingsScope)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){environment.showFeedback(failure.message?:"屏蔽规则保存失败")}
        }
        environment.showFeedback(resolveDanmakuBlockActionFeedbackMessage(target,changed))
    }
    if(!environment.isOwned()||!platform.isOwned())return
    CompositionLocalProvider(LocalDesktopDanmakuBindings provides platform) {
        // The original sheet has an explicit empty state, including before a raw document arrives.
        if(showPool)DesktopWindowsPlayerDialog("弹幕列表",onDismissPool) {
            DanmakuPoolSheet(
                danmakuList=items,currentPositionMs=currentPositionMs,onSeekTo=environment::seekTo,
                likedDanmakuIds=liked,onLikeDanmaku={dmid,like->session.likeDanmaku(dmid,like)},
                onRecallDanmaku=session::recallDanmaku,onReportDanmaku={dmid,reason->session.reportDanmaku(dmid,reason)},
                onBlockSender={block(DanmakuBlockActionTarget.USER,it)},onDismiss=onDismissPool)
        }
        if(menu.visible)DanmakuContextMenu(
            text=menu.text,onDismiss=session::hideDanmakuMenu,onLike={session.likeDanmaku(menu.dmid)},
            onRecall={session.recallDanmaku(menu.dmid)},canRecall=menu.isSelf,
            onReport={session.reportDanmaku(menu.dmid,it)},voteCount=menu.voteCount,hasLiked=menu.hasLiked,
            voteLoading=menu.voteLoading,canVote=menu.canVote,timestampJumpMs=resolveDanmakuTimestampJumpMs(menu.text),
            onSeekToTimestamp=environment::seekTo,canBlockKeyword=menu.text.isNotBlank(),
            onBlockKeyword={block(DanmakuBlockActionTarget.KEYWORD,menu.text)},canBlockUser=menu.userHash.isNotBlank(),
            onBlockUser={if(menu.userHash.isBlank())environment.showFeedback("该弹幕缺少发送者标识") else block(DanmakuBlockActionTarget.USER,menu.userHash)})
    }
}
