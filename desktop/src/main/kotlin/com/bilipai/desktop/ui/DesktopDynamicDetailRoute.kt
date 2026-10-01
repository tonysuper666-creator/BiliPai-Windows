package com.bilipai.desktop.ui

/** Original raw subject and routed thread IDs, retained across page navigation. */
internal data class DesktopDynamicDetailRoute(
    val dynamicId: String,
    val rootReplyId: Long = 0L,
    val targetReplyId: Long = 0L,
)
