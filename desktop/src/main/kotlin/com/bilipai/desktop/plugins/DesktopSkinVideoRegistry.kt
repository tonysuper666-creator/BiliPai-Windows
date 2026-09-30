package com.bilipai.desktop.plugins

import com.bilipai.desktop.player.MpvPlayer

/** Media3 constants bound for the unchanged upstream profile video repeat policy. */
object DesktopSkinVideoRepeat { const val REPEAT_MODE_OFF = 0; const val REPEAT_MODE_ONE = 1 }

/** Decorative instances are independently muted and also quiesced before archive restoration. */
object DesktopSkinVideoRegistry {
    private val lock = Any()
    private var stopped = false
    private val players = mutableSetOf<MpvPlayer>()
    internal fun register(player: MpvPlayer) = synchronized(lock) {
        check(!stopped) { "装扮播放器已停止" }; players.add(player)
    }
    internal fun release(player: MpvPlayer) { synchronized(lock) { players.remove(player) }; player.close() }
    internal fun shutdownForRestore() {
        val owned = synchronized(lock) { stopped = true; players.toList().also { players.clear() } }
        owned.forEach { it.close() }
    }
}
