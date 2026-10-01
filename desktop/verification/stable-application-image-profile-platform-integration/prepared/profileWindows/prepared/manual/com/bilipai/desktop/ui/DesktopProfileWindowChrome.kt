package com.bilipai.desktop.ui

import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.awt.EventQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

/** The same Root decorated window; no Android bars or extra native window are created.
 * Source-owned requests only lease its actual DWM title mode. Disposal resolves CURRENT
 * Root theme/background, never restores a stale captured theme or races Home's client actor. */
internal class DesktopWindowsProfileChrome(
    private val root:Window,
    private val clientPolicy:DesktopHomeWindowsClientPolicy,
    private val currentRootThemeLight:()->Boolean,
    private val diagnostic:(Throwable)->Unit,
) {
    private interface Dwm:StdCallLibrary {
        fun DwmGetWindowAttribute(window:Pointer,attribute:Int,value:Pointer,size:Int):Int
        fun DwmSetWindowAttribute(window:Pointer,attribute:Int,value:Pointer,size:Int):Int
    }
    private val dwm:Dwm by lazy {Native.load("dwmapi",Dwm::class.java)}
    private val sequence=AtomicLong()
    private val requests=linkedMapOf<Long,Boolean>() // Only touched on the actual EDT.
    private fun <T> onSwing(action:()->T):T {
        if(EventQueue.isDispatchThread())return action()
        val task=FutureTask(action);EventQueue.invokeAndWait(task);return task.get()
    }
    private fun apply(light:Boolean) {
        require(root.isDisplayable){"窗口标题主题暂不可用"}
        val hwnd=Native.getWindowPointer(root)
        Memory(4).use {value->
            check(dwm.DwmGetWindowAttribute(hwnd,1,value,4)>=0){"当前 Windows 不支持窗口框架接口"}
            value.setInt(0,if(light)0 else 1)
            check(dwm.DwmSetWindowAttribute(hwnd,20,value,4)>=0){"窗口标题主题更新失败"}
        }
        // Existing Home actor reads current Root theme color when its queued EDT action executes.
        clientPolicy.applyHomeSystemBars(light,light)
    }
    fun acquire(control:Boolean,light:Boolean,checkpoint:()->Unit):AutoCloseable {
        if(!control)return AutoCloseable {} // Original explicitly declines system/chrome control.
        val token=sequence.incrementAndGet()
        onSwing {checkpoint();apply(light);requests[token]=light}
        val closed=java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable {
            if(closed.compareAndSet(false,true))onSwing {
                requests.remove(token)
                try {apply(requests.values.lastOrNull() ?: currentRootThemeLight())}
                catch(error:Exception){diagnostic(error)}
            }
        }
    }
    /** Root theme effect uses this same lease authority; active original chrome still wins. */
    fun refreshCurrentRootTheme()=onSwing {apply(requests.values.lastOrNull() ?: currentRootThemeLight())}
}
