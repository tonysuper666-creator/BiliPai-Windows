package com.bilipai.desktop.ui

/**
 * Every text of the LEGACY player control bar (ui/DesktopWindowsVideoControlBar.kt, slice V2b). The bar file itself spells no
 * Chinese (UX hygiene suite); it reads these constants. The accessible names are the ones the Windows fixtures click by name
 * and the ones of the spec section 7 list, so each is spelled exactly as the bar spelled it before the restyle. The bar is
 * frozen with the LEGACY layout (spec 4.13), so it keeps its own copy beside DesktopPlayerStrings, and
 * test_ux_control_bar.py asserts that the names both objects hold are equal.
 */
internal object DesktopWindowsVideoControlBarStrings {
    // Accessible names (spec 7 and the Windows fixtures): one per button, the name of the node the fixtures click.
    const val PLAY = "播放"
    const val PAUSE = "暂停"
    const val PREVIOUS = "上一集"
    const val NEXT = "下一集"
    const val VOLUME_AND_MUTE = "音量与静音"
    const val SPEED = "倍速"
    const val QUALITY = "画质"
    const val CHAPTERS = "视频章节"
    const val DETAILS = "详情"
    const val MINI_PLAYER = "浮窗"
    const val FULLSCREEN = "全屏"
    const val EXIT_FULLSCREEN = "退出全屏"
    const val MORE_ACTIONS = "更多播放操作"
    const val SEEK_BAR = "播放进度"

    // Menu rows.
    const val MUTE = "静音"
    const val UNMUTE = "取消静音"
    const val VOLUME = "音量"
    const val COLLECTION = "视频合集"
    const val PLAYBACK_QUEUE = "播放队列"
    const val SEND_DANMAKU = "发送弹幕"
    const val SHARE_VIDEO = "分享视频"
    const val DOWNLOAD_AUDIO = "仅下载音频"
    const val SAVE_COVER = "保存封面"
    const val SCREENSHOT = "视频截图（S）"
    const val SCREENSHOT_BUSY = "截图中…"
    const val INTRODUCTION_AND_SETTINGS = "简介、分P与播放设置"

    // The name of the Space key in a tooltip hint (spec 4.5: the tooltip of the play button reads play, then Space).
    const val KEY_SPACE = "空格"

    /** The text of the speed pill and of a row of the speed menu: the speed, then the multiplication sign. */
    fun speedValue(speedText: String): String = "${speedText}×"

    /** A row of the speed list inside the compact more menu. */
    fun speedRow(speedText: String): String = "倍速 ${speedText}×"

    /** A row of the quality list inside the compact more menu. */
    fun qualityRow(label: String): String = "画质 · ${label}"

    /** A chapter row: its start time, a middle dot and its title. */
    fun chapterRow(time: String, title: String): String = "${time} · ${title}"

    /** The value label of the volume slider row. */
    fun volumePercent(volume: Int): String = "${volume}%"
}
