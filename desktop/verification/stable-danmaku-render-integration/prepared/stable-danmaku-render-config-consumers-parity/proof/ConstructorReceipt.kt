package com.bilipai.desktop.danmaku

import com.bilipai.desktop.player.MpvPlayer
import java.awt.Window
import javax.swing.SwingUtilities
import okhttp3.OkHttpClient

/** Exact required constructor argument ABI; these methods are compiled and never invoked by the fixture. */
internal object ConstructorReceipt {
    fun root(player:MpvPlayer,http:OkHttpClient,window:Window)=DanmakuOverlay(player,
        renderPlatform=DesktopWindowsDanmakuRenderPlatform {window},httpClient=http)
    fun media(player:MpvPlayer,http:OkHttpClient)=DanmakuOverlay(player,
        renderPlatform=DesktopWindowsDanmakuRenderPlatform {requireNotNull(SwingUtilities.getWindowAncestor(player.surface))},httpClient=http)
    fun offline(player:MpvPlayer)=DanmakuOverlay(player,
        renderPlatform=DesktopWindowsDanmakuRenderPlatform {requireNotNull(SwingUtilities.getWindowAncestor(player.surface))})
    fun smoke(player:MpvPlayer,source:DesktopDanmakuSource)=DanmakuOverlay(player,
        renderPlatform=DesktopWindowsDanmakuRenderPlatform {requireNotNull(SwingUtilities.getWindowAncestor(player.surface))},source=source)
}
