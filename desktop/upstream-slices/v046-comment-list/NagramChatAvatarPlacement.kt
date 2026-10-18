/*
 * Derived from Telegram for Android / NextAlone Nagram, ChatActivity.drawChild().
 * Copyright Nikolai Kudashov, 2013-2018.
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Distributed as part of BiliPai under GPLv3.
 *
 * Source revision: fb56ea9801e5de68c60f0152b444bbe2a0fda520
 * https://github.com/NextAlone/Nagram/blob/fb56ea9801e5de68c60f0152b444bbe2a0fda520/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java#L6607
 *
 * Kotlin extraction of the ordinary, non-round-video avatar placement path.
 * Keep the original clamp order: viewport bottom, group top, then cell bottom.
 * Comment records are independent groups; Telegram albums, sender grouping,
 * checkbox swipes and round-video playback are not BiliPai comment models.
 */
package com.android.purebilibili.feature.video.ui.components

internal const val NAGRAM_CHAT_AVATAR_SIZE_DP = 42

internal fun resolveNagramChatAvatarTop(
    groupTopPx: Int,
    cellBottomPx: Int,
    viewportBottomPx: Int,
    minimumSlotPx: Int,
    imageBottomOffsetPx: Int,
): Int {
    var y = cellBottomPx
    if (y > viewportBottomPx) {
        y = viewportBottomPx
    }
    if (y - minimumSlotPx < groupTopPx) {
        y = groupTopPx + minimumSlotPx
    }
    if (y > cellBottomPx) {
        y = cellBottomPx
    }
    return y - imageBottomOffsetPx
}
