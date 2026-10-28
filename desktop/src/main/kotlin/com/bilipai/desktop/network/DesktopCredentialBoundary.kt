package com.bilipai.desktop.network

import com.bilipai.desktop.data.usesDefaultBilibiliHeaders
import com.bilipai.desktop.player.cache.DesktopMediaOriginHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Match decoded names, but retain the original encoding, order and duplicates of public parameters. */
internal fun removeDesktopCredentialQueryParameters(
    builder: HttpUrl.Builder,
    url: HttpUrl,
    isCredential: (String) -> Boolean,
) {
    val encoded = url.encodedQuery ?: return
    val retained = encoded.split('&').filterIndexed { index, _ ->
        !isCredential(url.queryParameterName(index))
    }
    builder.encodedQuery(if (retained.isEmpty()) null else retained.joinToString("&"))
}

private fun credentialName(name: String): String = name.lowercase().replace("-", "").replace("_", "")

private val credentialNames = setOf(
        "accesstoken", "acesstoken", "accesskey", "refreshtoken", "sessdata", "bilijct", "csrf", "csrftoken",
        "authorization", "proxyauthorization", "cookie", "cookie2", "xbilimid", "buvid", "buvid3", "buvid4",
        "xbilipailoginbuvid", "xbilipaiforcecookie", "xcsrftoken",
    )

private fun isCredentialName(key: String): Boolean = key in credentialNames || key.startsWith("buvid")

// The shipped Akamai-overseas UPOS mirror, not the entire multi-tenant akamaized.net domain.
private fun isBilibiliAkamaiMirror(host: String): Boolean = host == "upos-hz-mirrorakam.akamaized.net"

private fun safeReferer(value: String): String? {
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.encodedQuery == null && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) return value
    return url.newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build().toString()
}

/** Final network-interceptor boundary: invoked after CookieJar and explicit/media cookie overrides,
 * and again for each redirect. The public-content client remains anonymous even on Bilibili hosts. */
internal fun enforceDesktopCredentialBoundary(request: Request): Request {
    if (usesDefaultBilibiliHeaders(request.url.host)) return request
    val media = request.tag(DesktopMediaOriginHeaders::class.java)
    val akamai = isBilibiliAkamaiMirror(request.url.host)
    val explicitMediaOrigin = !akamai && media?.allowsOrigin(request) == true
    val url = request.url.newBuilder().username("").password("")
    removeDesktopCredentialQueryParameters(url, request.url) { name ->
        val key = credentialName(name)
        // UPOS signs buvid as part of uparams. It is a media URL field here, not an account cookie.
        val signedMediaField = akamai && key == "buvid"
        val pluginToken = explicitMediaOrigin && key in setOf("accesstoken", "refreshtoken")
        isCredentialName(key) && !signedMediaField && !pluginToken
    }
    return request.newBuilder().url(url.build()).apply {
        request.headers.names().filter { isCredentialName(credentialName(it)) }.forEach { name ->
            val pluginHeader = explicitMediaOrigin && credentialName(name) in setOf("authorization", "accesstoken", "refreshtoken") &&
                media?.explicitHeader(request, name) == request.header(name)
            if (!pluginHeader) removeHeader(name)
        }
        request.header("Referer")?.let { value ->
            val safe = safeReferer(value)
            if (safe == null) removeHeader("Referer") else header("Referer", safe)
        }
    }.build()
}
