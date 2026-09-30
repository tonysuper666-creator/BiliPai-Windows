package com.bilipai.desktop.plugins

import okhttp3.OkHttpClient
import okhttp3.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.net.URI
import java.util.Locale
import java.util.concurrent.FutureTask

object DesktopPluginNetwork {
    val protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
    // Public subscription/SponsorBlock traffic shares no login cookie jar or auth interceptor.
    val publicClient: OkHttpClient by lazy { OkHttpClient.Builder().protocols(protocols).build() }
}

object DesktopPluginLog {
    fun d(tag: String, message: String) = Unit
    fun w(tag: String, message: String) = System.err.println("Plugin $tag warning")
    fun e(tag: String, message: String, error: Throwable) = System.err.println("Plugin $tag failure: ${error.javaClass.simpleName}")
}

/** URI binding preserves the original validateImportUrl result rather than throwing. */
object DesktopPluginUrl {
    fun parse(value: String): URI = runCatching { URI(value) }.getOrElse { URI("") }
}

/** Android parseColor's accepted #RRGGBB/#AARRGGBB and named ARGB values. */
object DesktopPluginColor {
    private val names = mapOf("black" to 0xff000000, "darkgray" to 0xff444444, "gray" to 0xff888888,
        "lightgray" to 0xffcccccc, "white" to 0xffffffff, "red" to 0xffff0000, "green" to 0xff00ff00,
        "blue" to 0xff0000ff, "yellow" to 0xffffff00, "cyan" to 0xff00ffff, "magenta" to 0xffff00ff,
        "aqua" to 0xff00ffff, "fuchsia" to 0xffff00ff, "darkgrey" to 0xff444444, "grey" to 0xff888888,
        "lightgrey" to 0xffcccccc, "lime" to 0xff00ff00, "maroon" to 0xff800000, "navy" to 0xff000080,
        "olive" to 0xff808000, "purple" to 0xff800080, "silver" to 0xffc0c0c0, "teal" to 0xff008080)
    fun parseColor(value: String): Int {
        if (!value.startsWith('#')) return names[value.lowercase(Locale.ROOT)]?.toInt() ?: error("Unknown color")
        require(value.length == 7 || value.length == 9) { "Unknown color" }
        val color = value.substring(1).toLong(16)
        return (if (value.length == 7) color or 0xff000000 else color).toInt()
    }
}

/** Real AWT window visibility binding for the original process STARTED check. */
object DesktopPluginLifecycle {
    fun isAppVisible(): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        fun visibleNow(): Boolean = Window.getWindows().any { window ->
            window.isShowing && window.type != Window.Type.POPUP &&
                (window !is Frame || window.extendedState and Frame.ICONIFIED == 0)
        }
        if (EventQueue.isDispatchThread()) return visibleNow()
        val task = FutureTask<Boolean> { visibleNow() }
        EventQueue.invokeAndWait(task)
        return task.get()
    }
}

/** Optional device-local aggregates; no UUID, account, cookie or remote analytics backend. */
object DesktopPluginAnalytics {
    private val lock = Any()
    private val scope = DesktopPluginScopeRegistry.create("local-statistics", Dispatchers.IO)
    private var context: DesktopPluginContext? = null
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _skipRequests = MutableStateFlow<Map<String, Int>>(emptyMap())
    val automaticSkipRequests: StateFlow<Map<String, Int>> = _skipRequests.asStateFlow()

    fun initialize(context: DesktopPluginContext) = synchronized(lock) {
        this.context = context
        val prefs = context.getSharedPreferences("plugin_local_statistics", DesktopPluginContext.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean("enabled", false)
        _skipRequests.value = prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith("skip_") && value is Int) key.removePrefix("skip_") to value else null
        }.toMap()
    }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            context?.getSharedPreferences("plugin_local_statistics", DesktopPluginContext.MODE_PRIVATE)?.edit()?.putBoolean("enabled", enabled)?.apply()
            _enabled.value = enabled
        }
    }

    fun logSponsorBlockSkip(videoId: String, segmentType: String) {
        if (!_enabled.value) return
        scope.launch {
            synchronized(lock) {
                if (!_enabled.value) return@synchronized
                val next = _skipRequests.value.toMutableMap()
                next[segmentType] = (next.getOrDefault(segmentType, 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                try {
                    context?.getSharedPreferences("plugin_local_statistics", DesktopPluginContext.MODE_PRIVATE)?.edit()?.apply {
                        next.forEach { (category, count) -> putInt("skip_$category", count) }
                    }?.apply()
                    _skipRequests.value = next
                } catch (error: Exception) { DesktopPluginLog.e("local_statistics", "Statistics storage failed", error) }
            }
        }
    }
}
