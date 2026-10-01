package com.android.purebilibili.core.store

enum class DanmakuSettingsScope(
    val keyPrefix: String,
    val badgeLabel: String,
    val subtitle: String
) {
    PORTRAIT(
        keyPrefix = "portrait",
        badgeLabel = "竖屏专用",
        subtitle = "开关、字号和区域与横屏同步，其余样式独立"
    ),
    LANDSCAPE(
        keyPrefix = "landscape",
        badgeLabel = "横屏专用",
        subtitle = "开关、字号和区域与竖屏同步，其余样式独立"
    )
}
