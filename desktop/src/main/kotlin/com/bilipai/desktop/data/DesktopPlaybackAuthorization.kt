package com.bilipai.desktop.data

import com.android.purebilibili.core.store.StoredAccountSession

/** Nonsecret proof of authorization. This is owned by the existing encrypted SessionStore. */
data class DesktopPlaybackAuthorizationReceipt internal constructor(val accountEpoch: Long, val revision: Long)

internal class DesktopPlaybackAuthorization internal constructor(
    val receipt: DesktopPlaybackAuthorizationReceipt,
    val playbackAccount: StoredAccountSession?,
    internal val cookieJar: DesktopOriginalPlaybackAccountCookieJar?,
) {
    override fun toString() = "DesktopPlaybackAuthorization(receipt=$receipt, dedicated=${cookieJar != null})"
}

internal class DesktopOriginalPlaybackAccountCookieJar(account: StoredAccountSession) : okhttp3.CookieJar {
    private val cookieLock = Any()
    private val cookies = mutableMapOf(
        "SESSDATA" to account.sessData,
        "bili_jct" to account.csrf,
        "DedeUserID" to account.mid.toString(),
        "buvid3" to account.buvid3
    ).filterValues { it.isNotBlank() }.toMutableMap()

    override fun saveFromResponse(url: okhttp3.HttpUrl, responseCookies: List<okhttp3.Cookie>) {
        if (!url.isHttps || !DesktopSessionStore.isBilibiliHost(url.host)) return
        synchronized(cookieLock) {
            responseCookies.filter { cookie ->
                DesktopSessionStore.isBilibiliHost(cookie.domain) &&
                    (cookie.domain == url.host || !cookie.hostOnly && url.host.endsWith(".${cookie.domain}"))
            }.forEach { cookie -> cookies[cookie.name] = cookie.value }
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        // v0.2.9 restricts account cookies to the Bilibili domain boundary. This
        // jar is also read directly for native media headers, outside SessionStore.
        if (!url.isHttps || !DesktopSessionStore.isBilibiliHost(url.host)) return emptyList()
        return synchronized(cookieLock) {
            cookies.map { (name, value) ->
                okhttp3.Cookie.Builder().domain("bilibili.com").secure().name(name).value(value).build()
            }
        }
    }
}
