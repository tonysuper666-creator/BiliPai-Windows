package com.android.purebilibili.core.store.navigation
private fun normalizeBottomBarLabelItemId(rawId: String): String {
    val id = rawId.trim()
    if (id.isBlank()) return ""
    return when (id.lowercase()) {
        "home" -> "HOME"
        "dynamic" -> "DYNAMIC"
        "story", "shortvideo", "short_video" -> "STORY"
        "history" -> "HISTORY"
        "listen_video" -> "LISTEN_VIDEO"
        "profile", "mine", "my" -> "PROFILE"
        "favorite", "favourite" -> "FAVORITE"
        "live" -> "LIVE"
        "watchlater", "watch_later" -> "WATCHLATER"
        "settings" -> "SETTINGS"
        "plugins", "plugin", "plugin_center" -> "PLUGINS"
        else -> id.uppercase()
    }
}

internal fun normalizeBottomBarCustomLabel(rawLabel: String): String = rawLabel
    .trim()
    .replace(Regex("\\s+"), " ")
    .take(12)

internal fun parseBottomBarItemLabels(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(',').mapNotNull { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val itemId = normalizeBottomBarLabelItemId(entry.substring(0, separator))
        if (itemId.isBlank()) return@mapNotNull null
        val decoded = runCatching {
            java.net.URLDecoder.decode(
                entry.substring(separator + 1),
                java.nio.charset.StandardCharsets.UTF_8.name()
            )
        }.getOrNull() ?: return@mapNotNull null
        normalizeBottomBarCustomLabel(decoded)
            .takeIf(String::isNotBlank)
            ?.let { itemId to it }
    }.toMap()
}
