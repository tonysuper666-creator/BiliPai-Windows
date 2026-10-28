package com.bilipai.desktop.player

import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.Window

/** ITaskbarList3 owns three fixed buttons; WM_COMMAND is forwarded to the SMTC source lease. */
internal class WindowsTaskbarMediaControls(window: Window, private val command: (WindowsMediaCommand) -> Unit) : AutoCloseable {
    private val hwnd = Native.getWindowPointer(window)
    private var taskbar: Pointer? = null
    private var previous: Pointer? = null
    private var added = false
    private var closed = false
    private var initialized = false
    private var latest: WindowsMediaSnapshot? = null
    private val created = api.RegisterWindowMessageW(WString("TaskbarButtonCreated"))
    private val icons = mutableListOf<Pointer>()
    private interface WindowCallback : StdCallLibrary.StdCallCallback {
        fun invoke(host: Pointer, message: Int, wparam: Long, lparam: Long): Long
    }
    private val proc = object : WindowCallback {
        override fun invoke(host: Pointer, message: Int, wparam: Long, lparam: Long): Long {
            if (message == created) EventQueue.invokeLater { if (!closed) { added = false; latest?.let(::update) } }
            if (!closed && message == 0x111 && (wparam ushr 16).toInt() and 0xffff == 0x1800) {
                val action = when (wparam.toInt() and 0xffff) {
                    0xB101 -> WindowsMediaCommand.PREVIOUS
                    0xB102 -> if (latest?.state?.paused == true) WindowsMediaCommand.PLAY else WindowsMediaCommand.PAUSE
                    0xB103 -> WindowsMediaCommand.NEXT
                    else -> null
                }
                if (action != null) { EventQueue.invokeLater { if (!closed) command(action) }; return 0 }
            }
            return api.CallWindowProcW(previous, host, message, wparam, lparam)
        }
    }
    init {
        check(EventQueue.isDispatchThread())
        check(Native.POINTER_SIZE == 8) { "Taskbar media controls require 64-bit Windows" }
        try {
            val result = Ole32.INSTANCE.CoInitializeEx(null, 2).toInt()
            check(result >= 0 || result == 0x80010106.toInt()) { "Taskbar COM initialization failed: $result" }
            initialized = result >= 0
            val output = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(Guid.GUID("{56FDF344-FD6D-11D0-958A-006097C9A090}"), null, 1,
                Guid.GUID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}"), output).toInt()
            check(hr >= 0) { "Taskbar CoCreateInstance failed: $hr" }
            taskbar = requireNotNull(output.value)
            checkHr(call(3), "Taskbar.HrInit")
            previous = api.SetWindowLongPtrW(hwnd, -4, CallbackReference.getFunctionPointer(proc))
            check(previous != null) { "Taskbar window callback installation failed" }
            listOf("previous", "play", "pause", "next").forEach { icons += createIcon(it) }
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun update(value: WindowsMediaSnapshot) {
        check(EventQueue.isDispatchThread())
        if (closed) return
        latest = value
        val active = value.enabled && value.sourceLease?.isCurrent() == true
        // THUMBBUTTON on Win64: 12 bytes ints, 4 padding, HICON, WCHAR[260], flags, 4 padding.
        val buttons = Memory(552L * 3).apply { clear() }
        val tips = listOf("上一个", if (value.state.paused) "播放" else "暂停", "下一个")
        for (i in 0..2) {
            val offset = 552L * i
            buttons.setInt(offset, 0xE) // THB_ICON | THB_TOOLTIP | THB_FLAGS
            buttons.setInt(offset + 4, 0xB101 + i)
            buttons.setPointer(offset + 16, icons[when (i) { 0 -> 0; 1 -> if (value.state.paused) 1 else 2; else -> 3 }])
            tips[i].forEachIndexed { n, char -> buttons.setShort(offset + 24 + 2L * n, char.code.toShort()) }
            val enabled = active && when (i) { 0 -> value.hasPrevious; 2 -> value.hasNext; else -> true }
            buttons.setInt(offset + 544, if (enabled) 0 else 1)
        }
        checkHr(call(if (added) 16 else 15, hwnd, 3, buttons), "Taskbar.${if (added) "Update" else "Add"}Buttons")
        added = true
    }
    override fun close() {
        if (closed) return
        closed = true
        if (previous != null) {
            api.SetWindowLongPtrW(hwnd, -4, previous)
            previous = null
        }
        icons.forEach { api.DestroyIcon(it) }; icons.clear()
        taskbar?.let { call(2) }; taskbar = null
        if (initialized) { Ole32.INSTANCE.CoUninitialize(); initialized = false }
    }
    private fun call(slot: Int, vararg args: Any?): Int {
        val instance = requireNotNull(taskbar)
        return Function.getFunction(instance.getPointer(0).getPointer(slot * 8L), Function.ALT_CONVENTION).invokeInt(arrayOf(instance, *args))
    }
    private fun checkHr(hr: Int, name: String) { check(hr >= 0) { "$name failed (0x${Integer.toUnsignedString(hr, 16)})" } }
    private fun createIcon(kind: String): Pointer {
        val size = api.GetSystemMetrics(11).coerceAtLeast(16)
        val mask = ByteArray(((size + 15) / 16) * 2 * size) { 0xff.toByte() }
        val color = ByteArray(size * size * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val px = x.toDouble() / size; val py = y.toDouble() / size
            val triangle = px in 0.28..0.72 && kotlin.math.abs(py - .5) < (px - .24) * .7
            val filled = when (kind) {
                "pause" -> py in .25.. .75 && (px in .28.. .42 || px in .58.. .72)
                "previous" -> (py in .25.. .75 && px in .2.. .3) || (px in .35.. .75 && kotlin.math.abs(py - .5) < (.78 - px) * .7)
                "next" -> triangle || (py in .25.. .75 && px in .75.. .85)
                else -> triangle
            }
            if (filled) {
                val index = (y * size + x) * 4
                for (c in 0..3) color[index + c] = 0xff.toByte()
            }
        }
        return requireNotNull(api.CreateIcon(null, size, size, 1, 32, mask, color)) { "Taskbar icon creation failed" }
    }
    private interface Api : StdCallLibrary {
        fun RegisterWindowMessageW(message: WString): Int
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, callback: Pointer?): Pointer?
        fun CallWindowProcW(previous: Pointer?, hwnd: Pointer, message: Int, wparam: Long, lparam: Long): Long
        fun CreateIcon(instance: Pointer?, width: Int, height: Int, planes: Byte, bits: Byte, and: ByteArray, xor: ByteArray): Pointer?
        fun DestroyIcon(icon: Pointer): Boolean
        fun GetSystemMetrics(index: Int): Int
    }
    companion object { private val api: Api by lazy { Native.load("user32", Api::class.java) } }
}
