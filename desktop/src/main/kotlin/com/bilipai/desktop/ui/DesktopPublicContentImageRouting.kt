package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.feed.publicContentImageHttpClient
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.usesDefaultBilibiliHeaders
import com.bilipai.desktop.network.DesktopNetworkProxyPlatform
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Host split for application image requests.
 *
 * Bilibili hosts keep the owned guest Call.Factory, which still carries the Bilibili Referer and Origin
 * that [usesDefaultBilibiliHeaders] gates. Every other host is public content, for example RSS images:
 * its calls use the anonymous image client of PublicContentNetwork.kt, the Windows counterpart of upstream
 * v0.4.6 `PublicContentImageLoader.kt` (blob 6d53ec0b339e7e8cd70b55949e84a5dabd9280e9 at
 * d6bf8b1655ae910999860bf13e65c8fcea5b345b). That client has no cookie jar or authenticator, strips
 * credentials at every redirect hop and restores a same-site Referer for hotlink protected CDNs.
 * The Bilibili host gate itself is not changed here.
 */
internal fun routedImageCalls(
    bilibiliCalls: Call.Factory,
    publicCalls: Call.Factory,
    owned: () -> Boolean,
): Call.Factory = Call.Factory { request ->
    if (usesDefaultBilibiliHeaders(request.url.host)) {
        bilibiliCalls.newCall(request)
    } else {
        if (!owned()) throw IOException("Public image request owner retired")
        publicCalls.newCall(withDesktopBrowserAgent(request))
    }
}

/**
 * The shared client stamped the desktop browser agent on every request, including RSS images. The plain
 * public client would send the OkHttp default instead, which some image CDNs refuse, so a request that
 * names no agent keeps the one it always had.
 */
private fun withDesktopBrowserAgent(request: Request): Request =
    if (request.header("User-Agent") != null) request
    else request.newBuilder().header("User-Agent", DesktopRepository.USER_AGENT).build()

/**
 * The public image client keeps the shared client's proxy selector and time budget, so RSS images behave
 * exactly as they did through it: the app proxy setting applies, and the 6 s connect and 8 s call limits
 * of the feed client do not cut off large images (15 s connect, 25 s read, no call limit). Feeds and
 * articles stay on the plain public client, as before.
 */
internal fun desktopPublicImageCalls(): OkHttpClient = publicContentImageHttpClient.newBuilder()
    .proxySelector(DesktopNetworkProxyPlatform.buildAppProxySelector())
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .build()
