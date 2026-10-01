package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import com.bilipai.desktop.plugins.DesktopLottieAsset
import kotlinx.coroutines.*

internal val LocalDesktopBgmLottieLoader=staticCompositionLocalOf<suspend(String)->ByteArray> {
    error("Original BGM empty animation requires the current operations owner")
}

/** The original online Lottie uses Root's existing anonymous owner transport and Skottie renderer. */
@Composable
internal fun DesktopBgmLottie(url:String,size:Dp) {
    val loader=LocalDesktopBgmLottieLoader.current
    var asset by remember(url,loader) { mutableStateOf<DesktopLottieAsset?>(null) }
    var seconds by remember(url,loader) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(url,loader) {
        var loaded:DesktopLottieAsset?=null
        try {
            val bytes=loader(url)
            loaded=withContext(Dispatchers.IO){DesktopLottieAsset.decode(bytes)}
            ensureActive();asset=loaded;loaded=null
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){ /* Preserve original blank animation while a resource cannot load. */ }
        finally{loaded?.close()}
    }
    DisposableEffect(asset){val current=asset;onDispose{current?.close()}}
    LaunchedEffect(asset) {
        if(asset==null)return@LaunchedEffect
        val start=withFrameNanos{it}
        while(isActive)withFrameNanos{seconds=(it-start)/1_000_000_000.0}
    }
    val current=asset;val time=seconds
    Canvas(Modifier.size(size)){if(current!=null)drawIntoCanvas{current.render(it.nativeCanvas,this.size.width,this.size.height,time)}}
}
