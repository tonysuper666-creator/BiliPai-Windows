package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bilipai.desktop.data.DesktopDynamicCache
import com.bilipai.desktop.data.DesktopDynamicCacheSession
import kotlinx.coroutines.CancellationException

internal val LocalDesktopDynamicCache = staticCompositionLocalOf<DesktopDynamicCache?> { null }
internal val LocalDesktopDynamicCacheSession = staticCompositionLocalOf<DesktopDynamicCacheSession?> { null }

/** A page starts only after its current owner has received the original cold cache. */
@Composable internal fun DesktopDynamicCacheContent(
    cache: DesktopDynamicCache,
    mid: Long,
    epoch: Long,
    onLogin: () -> Unit,
    content: @Composable (DesktopDynamicCacheSession) -> Unit,
) {
    var session by remember(cache, mid, epoch) { mutableStateOf<DesktopDynamicCacheSession?>(null) }
    var failure by remember(cache, mid, epoch) { mutableStateOf<Throwable?>(null) }
    var retry by remember(cache, mid, epoch) { mutableIntStateOf(0) }
    LaunchedEffect(cache, mid, epoch, retry) {
        failure = null
        try {
            val current = cache.openCurrent()
            check(current?.owner?.mid == mid && current.owner.epoch == epoch) { "账号已切换，请重新加载" }
            check(cache.withCurrentSession(current) { session = current }) { "账号已切换，请重新加载" }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error }
    }
    val current = session
    if (current != null) CompositionLocalProvider(LocalDesktopDynamicCacheSession provides current) { content(current) }
    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val error = failure
        if (error == null) DesktopLoadingIndicator()
        else CommunityFailure(error, onLogin) { retry++ }
    }
}
