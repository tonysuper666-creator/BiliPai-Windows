package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuType
import com.android.purebilibili.feature.video.danmaku.filterVisibleCommandDanmakuItems

/** Presentation selection only. The existing keyed owner/current/native gate stays at the caller. */
internal fun desktopWindowsVisibleCommandCards(
    items: List<CommandDanmakuItem>,
    hideInteractiveCommands: Boolean,
    attentionOwned: Boolean,
): List<CommandDanmakuItem> = filterVisibleCommandDanmakuItems(items, hideInteractiveCommands)
    .filter { it.type != CommandDanmakuType.ATTENTION || attentionOwned }
