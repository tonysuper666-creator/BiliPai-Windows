package com.bilipai.desktop.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Desktop help for the actual Windows controls. No preference, permission or account writes. */
@Composable internal fun DesktopWindowsTipsSettings(onBack: () -> Unit) {
    DesktopWindowsSettingsPane("Windows 使用帮助", onBack) {
        DesktopWindowsSettingsGroup("窗口与显示") {
            SelectionContainer {
                Text("Ctrl＋滚轮、Ctrl＋加减调整整个窗口；Ctrl＋0 恢复默认 125%。也可在外观设置中调整窗口缩放。")
            }
            Text("F11 切换全屏；Esc 退出全屏或返回上一页。字体大小和字重可在外观设置中单独调整。",
                style = MaterialTheme.typography.bodySmall)
        }
        DesktopWindowsSettingsGroup("播放与音频") {
            Text("播放器内可调整音量、静音、进度和播放速度。播放设置保留解码、画质、字幕、后台播放和播完行为。")
            Text("音频设备与 WASAPI 独占的更改从下一次播放应用。请以当前输出状态和实际格式为准；设备占用失败时可关闭独占后重新播放。",
                style = MaterialTheme.typography.bodySmall)
        }
        DesktopWindowsSettingsGroup("评论与本机文件") {
            Text("评论默认排序和详细时间在播放设置中调整。缓存、下载目录、设置备份和 WebDAV 在存储设置中管理。")
            Text("主题、字体、壁纸与插件保留桌面可用功能；插件网络、CDN 和播放加速在插件页面管理。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
