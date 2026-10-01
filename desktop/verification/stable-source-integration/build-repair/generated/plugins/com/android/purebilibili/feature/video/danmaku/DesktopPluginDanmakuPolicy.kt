// GENERATED from app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuManager.kt; do not edit.
// LF-normalized SHA-256: cfe1d1eed5f1fcb47e1657b559f149698994cee0b8808dea4a7030774ec40c93
package com.android.purebilibili.feature.video.danmaku

import com.android.purebilibili.core.plugin.DanmakuItem as PluginDanmakuItem

import com.android.purebilibili.core.plugin.DanmakuPlugin

import com.android.purebilibili.core.plugin.DanmakuStyle

import com.android.purebilibili.core.plugin.json.JsonPluginManager

import com.bilipai.desktop.plugins.DesktopPluginLog as Log

import kotlin.math.abs

internal object DesktopPluginDanmakuPolicy {
    private const val TAG = "DanmakuManager"

    internal fun runDanmakuFilters(
        item: PluginDanmakuItem,
        nativePlugins: List<DanmakuPlugin>,
        useJsonRules: Boolean
    ): PluginDanmakuItem? {
        var current = item
        nativePlugins.forEach { plugin ->
            val filtered = try {
                plugin.filterDanmaku(current)
            } catch (e: Exception) {
                Log.e(TAG, " Danmaku plugin filter failed: ${plugin.name}", e)
                current
            }
            if (filtered == null) return null
            current = filtered
        }

        if (useJsonRules) {
            val shouldShow = try {
                JsonPluginManager.shouldShowDanmaku(current)
            } catch (e: Exception) {
                Log.e(TAG, " JSON danmaku rule filter failed", e)
                true
            }
            if (!shouldShow) return null
        }

        return current
    }

    internal fun collectDanmakuStyle(
        item: PluginDanmakuItem,
        nativePlugins: List<DanmakuPlugin>,
        useJsonRules: Boolean
    ): DanmakuStyle? {
        var style: DanmakuStyle? = null
        nativePlugins.forEach { plugin ->
            val next = try {
                plugin.styleDanmaku(item)
            } catch (e: Exception) {
                Log.e(TAG, " Danmaku plugin style failed: ${plugin.name}", e)
                null
            }
            style = mergeDanmakuStyle(style, next)
        }

        if (useJsonRules) {
            val next = try {
                JsonPluginManager.getDanmakuStyle(item)
            } catch (e: Exception) {
                Log.e(TAG, " JSON danmaku rule style failed", e)
                null
            }
            style = mergeDanmakuStyle(style, next)
        }

        return style
    }

    private fun mergeDanmakuStyle(base: DanmakuStyle?, incoming: DanmakuStyle?): DanmakuStyle? {
        if (base == null) return incoming
        if (incoming == null) return base
        return DanmakuStyle(
            textColor = incoming.textColor ?: base.textColor,
            borderColor = incoming.borderColor ?: base.borderColor,
            backgroundColor = incoming.backgroundColor ?: base.backgroundColor,
            bold = base.bold || incoming.bold,
            scale = if (abs(incoming.scale - 1.0f) > 0.01f) incoming.scale else base.scale
        )
    }

}
