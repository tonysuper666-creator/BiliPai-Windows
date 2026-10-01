package com.bilipai.desktop.player

import java.util.Collections
import java.util.Locale

/** Platform HTTP transport validation; the original JS stream remains the only plugin media schema. */
internal fun copyPlaybackStreamHeaders(headers: Map<String, String>): Map<String, String> {
    require(headers.size <= 64) { "Too many playback HTTP headers." }
    val unique = linkedMapOf<String, Pair<String, String>>()
    var bytes = 0L
    headers.forEach { (name, value) ->
        require(name.length in 1..128 && name.all { it in HTTP_HEADER_TOKEN }) { "Invalid playback HTTP header name." }
        require(value.none { it == '\u007f' || (it < ' ' && it != '\t') }) { "Invalid playback HTTP header value." }
        val valueBytes = value.toByteArray(Charsets.UTF_8).size
        require(valueBytes <= 8_192) { "Playback HTTP header value is too long." }
        bytes += name.length + valueBytes + 4L
        require(bytes <= 32_768) { "Playback HTTP headers are too large." }
        // Media3/FFmpeg names are case-insensitive. The last explicitly supplied spelling/value wins.
        unique[name.lowercase(Locale.ROOT)] = name to value
    }
    return Collections.unmodifiableMap(linkedMapOf<String, String>().apply {
        unique.values.forEach { (name, value) -> put(name, value) }
    })
}

/** Copy before handing ownership to the native actor; callers cannot later mutate its credentials or segments. */
internal fun PlaybackSource.immutableSnapshot(): PlaybackSource = copy(
    progressiveSegments = Collections.unmodifiableList(progressiveSegments.toList()),
    streamHeaders = copyPlaybackStreamHeaders(streamHeaders),
)

internal fun Map<String, String>.playbackHeader(name: String): String? =
    entries.lastOrNull { it.key.equals(name, ignoreCase = true) }?.value

private const val HTTP_HEADER_TOKEN = "!#\u0024%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
