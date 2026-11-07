package com.android.purebilibili.navigation

import java.net.URI

// The original pattern stops at the first non-alphanumeric character, so `b23.tv/abc-123`
// yields `b23.tv/abc`; this span keeps every character that could still continue the path.
private val desktopSubmittedShortLink = Regex(
    "(?<![A-Za-z0-9./-])(?:https?://)?(?:[A-Za-z0-9-]+\\.)*b23\\.tv/([A-Za-z0-9_~%./-]*)",
    RegexOption.IGNORE_CASE,
)
private val desktopShortLinkToken = Regex("[A-Za-z0-9]+")

/**
 * Both desktop submit entries (bottom bar, search page) go through the original v0.7.6
 * [resolveSearchSubmitAction]; a short link it opens is kept only when the submitted text
 * carries that complete, non-empty b23.tv token. An empty path or a cut-short token searches
 * the submitted text as before the short-link branch existed. Every other action is unchanged.
 */
internal fun resolveDesktopSearchSubmitAction(rawKeyword: String): SearchSubmitAction {
    val action = resolveSearchSubmitAction(rawKeyword)
    if (action !is SearchSubmitAction.OpenLink) return action
    val opened = runCatching { URI(action.url).rawPath }.getOrNull()?.removePrefix("/")
    val submitted = desktopSubmittedShortLink.find(rawKeyword)?.groupValues?.get(1)?.trimEnd('.')
    if (opened != null && desktopShortLinkToken.matches(opened) && opened == submitted) return action
    return SearchSubmitAction.OpenSearch(rawKeyword.trim())
}
