package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import com.android.purebilibili.core.network.policy.AppHttpProxySettings
import com.android.purebilibili.core.store.NetworkProxyStore
import com.android.purebilibili.feature.settings.DesktopNetworkProxyFields
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** The original singleton is initialized before the original fields observe it. */
internal class DesktopNetworkProxyBindings(
    val context: DesktopPluginContext,
    private val sharedApplicationClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
) {
    private val writer=Mutex()
    init { NetworkProxyStore.init(context) }
    suspend fun saveAndAwait(value:AppHttpProxySettings) = writer.withLock {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val previous=NetworkProxyStore.getSync(context)
            NetworkProxyStore.save(context,value)
            // OkHttp can reuse idle connections without asking the dynamic selector.
            // Retire idle routes after successful persistence; active requests keep the original semantics.
            if (previous!=NetworkProxyStore.getSync()) sharedApplicationClient.connectionPool.evictAll()
        }
    }
    fun save(value:AppHttpProxySettings):Job=scope.launch {
        try {saveAndAwait(value)}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){onFailure(failure)}
    }
}

internal val LocalDesktopNetworkProxyBindings = staticCompositionLocalOf<DesktopNetworkProxyBindings> {
    error("Actual application proxy binding is required")
}

@Composable
internal fun DesktopNetworkProxySettings(
    context:DesktopPluginContext,
    sharedApplicationClient:OkHttpClient,
    onFailure:(Throwable)->Unit,
    modifier:Modifier=Modifier,
) {
    val scope=rememberCoroutineScope()
    val latestFailure by rememberUpdatedState(onFailure)
    val binding=remember(context,sharedApplicationClient,scope){DesktopNetworkProxyBindings(context,sharedApplicationClient,scope){latestFailure(it)}}
    CompositionLocalProvider(LocalDesktopNetworkProxyBindings provides binding) {Column(modifier){DesktopNetworkProxyFields()}}
}
