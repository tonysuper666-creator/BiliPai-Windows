// GENERATED from app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt; do not edit.
// LF-normalized SHA-256: 33e0d081fc70ab587d333eb52a21a193932be2dbc5835f409901139d7a8212dd
package com.bilipai.desktop.network
import okhttp3.OkHttpClient
import java.net.Proxy

internal object DesktopNetworkProxyPlatform {
internal fun buildAppProxySelector(systemSelector: () -> java.net.ProxySelector? = { java.net.ProxySelector.getDefault() }): java.net.ProxySelector {
    return object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?): List<Proxy> {
            val settings = com.android.purebilibili.core.store.NetworkProxyStore.getSync()
            val systemProxies = runCatching {
                systemSelector()?.select(uri).orEmpty()
            }.getOrDefault(emptyList())
            return com.android.purebilibili.core.network.policy.selectAppHttpProxies(
                settings = settings,
                systemProxies = systemProxies,
            )
        }

        override fun connectFailed(
            uri: java.net.URI?,
            sa: java.net.SocketAddress?,
            ioe: java.io.IOException?,
        ) {
            recordDesktopProxyConnectionFailure(ioe)
        }
    }
}

internal fun buildPlaybackOkHttpClient(sharedClient: OkHttpClient): OkHttpClient {
    return sharedClient.newBuilder()
        // Media playback should not inherit device-local proxy apps that may expose
        // an unavailable loopback port and break streaming with ECONNREFUSED.
        .proxy(Proxy.NO_PROXY)
        .build()
}
}
