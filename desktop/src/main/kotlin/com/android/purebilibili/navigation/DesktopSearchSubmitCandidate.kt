package com.android.purebilibili.navigation

import java.net.URI
import com.android.purebilibili.core.util.isBilibiliShortHost

// Share prose (including adjacent Chinese text) separates candidates. URL/path characters do
// not: consume the whole span even when it contains an unknown or illegal character.
private val desktopSubmittedLinkSpans = Regex("[^\\s\\p{IsHan}，。！？；：（）【】《》“”‘’]+")
private val desktopShortLinkHostMention = Regex("b23\\.tv(?=[:/?#]|$)", RegexOption.IGNORE_CASE)
private val desktopShortLinkPath = Regex("/[A-Za-z0-9]+")

private fun unwrapDesktopSubmittedLink(span: String): String {
    var candidate = span
    val wrappers = mapOf('(' to ')', '[' to ']', '{' to '}', '<' to '>', '\"' to '\"', '\'' to '\'')
    while (true) {
        val closing = wrappers[candidate.firstOrNull()] ?: break
        candidate = candidate.drop(1)
        if (candidate.endsWith(closing)) candidate = candidate.dropLast(1)
    }
    // Sentence punctuation may follow the path. Inside a query/fragment it is URL data.
    return if ('?' in candidate || '#' in candidate) candidate
    else candidate.trimEnd('.', ',', '!', ';', ')', ']', '}', '>')
}

/**
 * Both desktop submit entries (bottom bar, search page) go through the original v0.7.6
 * [resolveSearchSubmitAction], but only after validating a complete short-link candidate.
 * Never ask the original prefix-matching extractor to establish the candidate's boundary.
 * Invalid candidates search the whole submission; unrelated submits keep the original policy.
 */
internal fun resolveDesktopSearchSubmitAction(rawKeyword: String): SearchSubmitAction {
    val candidate = desktopSubmittedLinkSpans.findAll(rawKeyword)
        .map { unwrapDesktopSubmittedLink(it.value) }
        .firstOrNull { desktopShortLinkHostMention.containsMatchIn(it) }
        ?: return resolveSearchSubmitAction(rawKeyword)
    val url = if (candidate.startsWith("b23.tv/", ignoreCase = true)) "https://$candidate" else candidate
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri == null || !(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) ||
        !isBilibiliShortHost(uri.host) || uri.rawUserInfo != null ||
        !desktopShortLinkPath.matches(uri.rawPath.orEmpty())
    ) return SearchSubmitAction.OpenSearch(rawKeyword.trim())

    val action = resolveSearchSubmitAction(candidate)
    // The original extractor omits fragments. Keep all validated query/fragment bytes.
    return if (action is SearchSubmitAction.OpenLink) SearchSubmitAction.OpenLink(url) else action
}
