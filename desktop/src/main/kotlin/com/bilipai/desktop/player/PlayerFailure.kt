package com.bilipai.desktop.player

import java.net.URI

enum class PlayerFailureKind { NETWORK, DECODER, AUDIO_OUTPUT, VIDEO_OUTPUT, FILE_IO, UNSUPPORTED, UNKNOWN }

/** Public failures contain no playback URLs, HTTP headers or account credentials. */
data class PlayerFailure(
    val kind: PlayerFailureKind,
    val nativeCode: Int?,
    val safeMessage: String,
    val diagnostics: List<String> = emptyList(),
    val sourceVersion: Long,
    val attemptId: Long,
    val httpStatus: Int? = null,
    /** Actual warning/error timeout evidence retained before secret redaction. */
    val networkTimedOut: Boolean = false,
)

/** Only warning/error events are retained, and only a terminal file error publishes a failure. */
internal class PlayerDiagnostics(private val capacity: Int = 32, private val maximumCharacters: Int = 16_384) {
    private val lines = ArrayDeque<String>()
    private var characters = 0
    private var headerSecrets: List<String> = emptyList()
    private var streamHeaderPattern: Regex? = null
    private var networkEvidence = false
    private var networkTimedOut = false
    private var decoderEvidence = false
    private var fileEvidence = false
    private var httpStatus: Int? = null

    fun reset(source: PlaybackSource?) {
        lines.clear(); characters = 0
        networkEvidence = false; networkTimedOut = false
        decoderEvidence = false; fileEvidence = false; httpStatus = null
        val explicit = source?.streamHeaders.orEmpty()
        headerSecrets = buildList {
            addAll(explicit.values.filter(String::isNotBlank))
            val cookies = listOf(source?.cookieHeader.orEmpty(), explicit.playbackHeader("Cookie").orEmpty())
            cookies.forEach { cookie -> cookie.split(';').forEach { item ->
                item.substringAfter('=', "").trim().takeIf(String::isNotEmpty)?.let(::add)
            } }
            listOf("Authorization", "Proxy-Authorization").forEach { name ->
                explicit.playbackHeader(name)?.substringAfter(' ', "")?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }.distinct().sortedByDescending(String::length)
        streamHeaderPattern = explicit.keys.takeIf { it.isNotEmpty() }?.let { keys ->
            Regex("(?im)(?<![A-Za-z0-9!#\u0024%&'*+.^_`|~-])(" + keys.joinToString("|") { Regex.escape(it) } + ")\\s*[:=].*\u0024")
        }
    }

    fun append(prefix: String, message: String) {
        val bounded = "${prefix.take(96)}: ${message.take(8_192)}"
        // Retain only typed evidence before redaction, since a numeric Cookie value
        // can occur in an otherwise harmless HTTP status or native error number.
        val evidence = bounded.lowercase()
        HTTP_STATUS.find(evidence)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { httpStatus = it }
        networkEvidence = networkEvidence || NETWORK.containsMatchIn(evidence)
        networkTimedOut = networkTimedOut || NETWORK_TIMEOUT.containsMatchIn(evidence)
        decoderEvidence = decoderEvidence || DECODER.containsMatchIn(evidence)
        fileEvidence = fileEvidence || LOCAL_FILE.containsMatchIn(evidence)
        val safe = sanitize(bounded).trim().take(2_000)
        if (safe.isBlank()) return
        lines.addLast(safe); characters += safe.length
        while (lines.size > capacity || characters > maximumCharacters) characters -= lines.removeFirst().length
    }

    fun sanitize(value: String): String {
        var result = value.take(8_192).replace(Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]"), " ")
        // mpv/FFmpeg error messages can contain a complete signed URL or echoed header.
        result = HEADER.replace(result) { "${it.groupValues[1]}: <redacted>" }
        streamHeaderPattern?.let { pattern -> result = pattern.replace(result) { "${it.groupValues[1]}: <redacted>" } }
        headerSecrets.forEach { secret -> result = result.replace(secret, "<redacted>") }
        result = URL.replace(result) { match ->
            val uri = runCatching { URI(match.value) }.getOrNull()
            val host = uri?.host?.takeIf { it.length <= 253 }
            if (host == null) "<media URL>" else "${uri.scheme.lowercase()}://$host/<redacted>"
        }
        result = SECRET_ASSIGNMENT.replace(result) { "${it.groupValues[1]}=<redacted>" }
        result = QUERY_ASSIGNMENT.replace(result) { "${it.groupValues[1]}<redacted>" }
        return result.take(2_000)
    }

    fun failure(code: Int?, message: String, sourceVersion: Long, attemptId: Long): PlayerFailure {
        val safeMessage = sanitize(message)
        val kind = when {
            code == -14 -> PlayerFailureKind.AUDIO_OUTPUT
            code == -15 -> PlayerFailureKind.VIDEO_OUTPUT
            httpStatus != null || networkEvidence -> PlayerFailureKind.NETWORK
            decoderEvidence -> PlayerFailureKind.DECODER
            fileEvidence -> PlayerFailureKind.FILE_IO
            code == -17 || code == -18 || code == -19 -> PlayerFailureKind.UNSUPPORTED
            else -> PlayerFailureKind.UNKNOWN
        }
        return PlayerFailure(kind, code, safeMessage, lines.toList(), sourceVersion, attemptId, httpStatus,
            networkTimedOut = kind == PlayerFailureKind.NETWORK && networkTimedOut)
    }

    companion object {
        private val HEADER = Regex("(?im)\\b(cookie|set-cookie|authorization|proxy-authorization|http-header-fields)\\s*[:=].*$")
        private val URL = Regex("(?i)[a-z][a-z0-9+.-]*://[^\\s\\\"'<>]+")
        private val SECRET_ASSIGNMENT = Regex("(?i)\\b(SESSDATA|bili_jct|DedeUserID(?:__ckMd5)?|access_token|refresh_token|token|signature|sign|session(?:id)?)\\s*=\\s*[^\\s;&\\\"']+")
        private val QUERY_ASSIGNMENT = Regex("([?&][A-Za-z0-9_.~-]+=)[^\\s;&\\\"']+")
        private val HTTP_STATUS = Regex("(?:http(?: error| status| response(?: code)?)?|server returned|http/[0-9.]+)\\s*[:=]?\\s*([45][0-9]{2})\\b")
        private val NETWORK = Regex("connection (?:timed? ?out|refused|reset|failed)|(?:network|operation|read) (?:is unreachable|timed? ?out)|failed to resolve|could not resolve|name or service not known|temporary failure in name resolution|tls (?:error|handshake)|ssl (?:error|handshake)|server returned [45][0-9]{2}")
        private val DECODER = Regex("(?:decoder|decode|decoding|vd_lavc|ad_lavc|d3d11va|dxva2|hwdec|renderer).*(?:failed|failure|error|not found|unsupported|could not|cannot)|(?:failed|could not|cannot).*(?:decoder|decode|decoding|renderer)")
        private val NETWORK_TIMEOUT = Regex("connection timed? ?out|(?:network|operation|read) timed? ?out")
        private val LOCAL_FILE = Regex("no such file or directory|file not found|permission denied|access (?:is )?denied|cannot open local file")
    }
}
