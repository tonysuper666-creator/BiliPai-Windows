package com.bilipai.desktop.ui

import com.bilipai.desktop.danmaku.DanmakuSettings

/** The original slider dispatch needs renderer properties, not a second player or preference store. */
internal interface DesktopOriginalDanmakuSliderPreviewTarget {
    var opacity: Float
    var fontScale: Float
    var fontWeight: Int
    var speedFactor: Float
    var displayArea: Float
    var strokeWidth: Float
    var lineHeight: Float
    var scrollDurationSeconds: Float
    var staticDurationSeconds: Float
}

/** Root binds these callbacks to its existing overlay and exact accepted presentation admission. */
internal class DesktopOriginalDanmakuSettingsPreviewTarget(
    private val readSettings: () -> DanmakuSettings,
    private val writeSettings: (DanmakuSettings) -> Unit,
    private val admit: (() -> Unit) -> Boolean,
) : DesktopOriginalDanmakuSliderPreviewTarget {
    private fun update(change: (DanmakuSettings) -> DanmakuSettings) {
        // Read inside the admission: retain other changes made since the previous drag event.
        admit { writeSettings(change(readSettings())) }
    }

    override var opacity: Float
        get() = readSettings().opacity
        set(value) { update { it.copy(opacity = value) } }
    override var fontScale: Float
        get() = readSettings().fontScale
        set(value) { update { it.copy(fontScale = value) } }
    override var fontWeight: Int
        get() = readSettings().fontWeight
        set(value) { update { it.copy(fontWeight = value) } }
    override var speedFactor: Float
        get() = readSettings().speedFactor
        set(value) { update { it.copy(speedFactor = value) } }
    override var displayArea: Float
        get() = readSettings().displayAreaRatio
        set(value) { update { it.copy(displayAreaRatio = value) } }
    override var strokeWidth: Float
        get() = readSettings().strokeWidth
        set(value) { update { it.copy(strokeWidth = value, strokeEnabled = value > 0) } }
    override var lineHeight: Float
        get() = readSettings().lineHeight
        set(value) { update { it.copy(lineHeight = value) } }
    override var scrollDurationSeconds: Float
        get() = readSettings().scrollDurationSeconds
        set(value) { update { it.copy(scrollDurationSeconds = value) } }
    override var staticDurationSeconds: Float
        get() = readSettings().staticDurationSeconds
        set(value) { update { it.copy(staticDurationSeconds = value) } }
}
