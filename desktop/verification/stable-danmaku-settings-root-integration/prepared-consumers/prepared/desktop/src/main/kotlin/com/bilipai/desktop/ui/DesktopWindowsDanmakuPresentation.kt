package com.bilipai.desktop.ui

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.runtime.*
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener

internal enum class DesktopPhysicalDisplayOrientation { LANDSCAPE,PORTRAIT }

@Composable internal fun rememberDesktopWindowsDanmakuPresentation(window:Window,state:WindowState):DesktopDanmakuPresentationBinding {
    val revision=remember(window){mutableIntStateOf(0)}
    DisposableEffect(window) {
        val listener=object:ComponentAdapter(){
            override fun componentResized(event:ComponentEvent){revision.intValue++}
            override fun componentMoved(event:ComponentEvent){revision.intValue++}
        }
        val configuration=PropertyChangeListener{revision.intValue++}
        window.addComponentListener(listener);window.addPropertyChangeListener("graphicsConfiguration",configuration)
        onDispose{window.removeComponentListener(listener);window.removePropertyChangeListener("graphicsConfiguration",configuration)}
    }
    return remember(window,state){DesktopDanmakuPresentationBinding {
        revision.intValue
        desktopDanmakuPresentation(state.placement){desktopPhysicalDisplayOrientation(window)}
    }}
}

/** Windows display configuration, never the video or window aspect ratio. */
internal fun desktopDanmakuPresentation(
    placement:WindowPlacement,
    physicalDisplay:()->DesktopPhysicalDisplayOrientation,
):DesktopDanmakuPresentation=when(placement) {
    WindowPlacement.Fullscreen->when(physicalDisplay()){
        DesktopPhysicalDisplayOrientation.LANDSCAPE->DesktopDanmakuPresentation.FULLSCREEN_LANDSCAPE
        DesktopPhysicalDisplayOrientation.PORTRAIT->DesktopDanmakuPresentation.FULLSCREEN_PORTRAIT
    }
    else->DesktopDanmakuPresentation.INLINE
}

/** Existing JNA dependency; MONITORINFOEXW / DEVMODEW fields are read from the current Root monitor. */
private interface DesktopDanmakuDisplayApi:StdCallLibrary {
    fun MonitorFromWindow(window:Pointer,flags:Int):Pointer?
    fun GetMonitorInfoW(monitor:Pointer,info:Pointer):Boolean
    fun EnumDisplaySettingsW(device:WString,mode:Int,settings:Pointer):Boolean
}
private val danmakuDisplayApi by lazy{Native.load("user32",DesktopDanmakuDisplayApi::class.java)}

internal fun desktopPhysicalDisplayOrientation(window:Window):DesktopPhysicalDisplayOrientation {
    check(window.isDisplayable){"Danmaku presentation requires the actual displayable Root window"}
    val monitor=checkNotNull(danmakuDisplayApi.MonitorFromWindow(Native.getWindowPointer(window),2)){"Root monitor is unavailable"}
    Memory(104).use { info ->
        info.clear();info.setInt(0,104)
        check(danmakuDisplayApi.GetMonitorInfoW(monitor,info)){"Root monitor information is unavailable"}
        val device=info.getWideString(40)
        Memory(220).use { mode ->
            mode.clear();mode.setShort(68,220.toShort())
            check(danmakuDisplayApi.EnumDisplaySettingsW(WString(device),-1,mode)){"Root physical display mode is unavailable"}
            val rotation=mode.getInt(84)
            val width=mode.getInt(172);val height=mode.getInt(176)
            check(rotation in 0..3&&width>0&&height>0){"Root physical display configuration is invalid"}
            return if(height>width)DesktopPhysicalDisplayOrientation.PORTRAIT else DesktopPhysicalDisplayOrientation.LANDSCAPE
        }
    }
}
