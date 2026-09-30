package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.*

/** Live Compose viewport width, the original Android LocalConfiguration field's Windows binding. */
internal object DesktopDynamicWindowConfiguration {
    data class Bounds(val screenWidthDp:Int)
    val current:Bounds @Composable get()=Bounds((LocalWindowInfo.current.containerSize.width/LocalDensity.current.density).toInt())
}
private interface DynamicAnimationSystem:StdCallLibrary {
    fun SystemParametersInfoW(action:Int,param:Int,value:IntByReference,flags:Int):Boolean
}
private fun desktopReduceMotion():Boolean {
    // Offscreen fixtures have no OS accessibility consumer. Static badges avoid an endless render clock.
    if(GraphicsEnvironment.isHeadless())return true
    if(!System.getProperty("os.name").startsWith("Windows",true))return false
    return runCatching {
        val api=Native.load("user32",DynamicAnimationSystem::class.java,W32APIOptions.UNICODE_OPTIONS)
        val animations=IntByReference(1)
        api.SystemParametersInfoW(0x1042,0,animations,0)&&animations.value==0
    }.getOrDefault(false)
}
/** Read-only Windows SPI_GETCLIENTAREAANIMATION; no HWND or user preference is changed. */
@Composable internal fun rememberDesktopDynamicReduceMotion():Boolean {
    var value by remember{mutableStateOf(desktopReduceMotion())}
    LaunchedEffect(Unit){while(isActive){delay(1000);value=withContext(Dispatchers.IO){desktopReduceMotion()}}}
    return value
}
