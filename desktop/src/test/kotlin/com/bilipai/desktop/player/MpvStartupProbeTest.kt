package com.bilipai.desktop.player

import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvStartupProbeTest {
    @Test fun `probe initializes native client without a video surface or sound device`() {
        val native = ProbeNative()
        MpvStartupProbe.verify(native)
        assertEquals(mapOf("config" to "no", "load-scripts" to "no", "terminal" to "no", "vo" to "null", "ao" to "null"), native.options)
        assertTrue(native.initialized)
        assertEquals(1, native.destroyCount)
    }

    @Test fun `a missing native handle cannot acknowledge startup`() {
        val native = ProbeNative(createHandle = false)
        assertFailsWith<IllegalStateException> { MpvStartupProbe.verify(native) }
        assertFalse(native.initialized)
        assertEquals(0, native.destroyCount)
    }

    @Test fun `a rejected option fails health verification and destroys the native handle`() {
        val native = ProbeNative(failedOption = "load-scripts")
        val failure = assertFailsWith<IllegalStateException> { MpvStartupProbe.verify(native) }
        assertTrue(failure.message.orEmpty().contains("load-scripts"))
        assertFalse(native.initialized)
        assertEquals(1, native.destroyCount)
    }

    @Test fun `failed initialization fails health verification and destroys the native handle`() {
        val native = ProbeNative(initializeResult = -1)
        assertFailsWith<IllegalStateException> { MpvStartupProbe.verify(native) }
        assertTrue(native.initialized)
        assertEquals(1, native.destroyCount)
    }

    private class ProbeNative(
        val createHandle: Boolean = true,
        val failedOption: String? = null,
        val initializeResult: Int = 0,
    ) : MpvNative {
        val options = linkedMapOf<String, String>()
        var initialized = false
        var destroyCount = 0
        override fun mpv_create(): Pointer? = if (createHandle) Pointer(1L) else null
        override fun mpv_set_option_string(handle: Pointer, name: String, value: String): Int {
            options[name] = value
            return if (name == failedOption) -1 else 0
        }
        override fun mpv_initialize(handle: Pointer): Int { initialized = true; return initializeResult }
        override fun mpv_request_log_messages(handle: Pointer, level: String): Int = error("Unexpected playback log subscription")
        override fun mpv_terminate_destroy(handle: Pointer) { destroyCount++ }
        override fun mpv_error_string(error: Int): String = "test native error"
        override fun mpv_set_property_string(handle: Pointer, name: String, value: String): Int = error("Unexpected playback call")
        override fun mpv_get_property_string(handle: Pointer, name: String): Pointer? = error("Unexpected playback call")
        override fun mpv_set_property(handle: Pointer, name: String, format: Int, data: Pointer): Int = error("Unexpected shader call")
        override fun mpv_get_property(handle: Pointer, name: String, format: Int, data: Pointer): Int = error("Unexpected shader call")
        override fun mpv_free_node_contents(node: Pointer) = error("Unexpected shader call")
        override fun mpv_command(handle: Pointer, args: StringArray): Int = error("Unexpected playback call")
        override fun mpv_command_node(handle: Pointer, args: Pointer, result: Pointer?): Int = error("Unexpected playback call")
        override fun mpv_wait_event(handle: Pointer, timeout: Double): Pointer = error("Unexpected playback call")
        override fun mpv_render_context_create(result: com.sun.jna.ptr.PointerByReference, handle: Pointer, params: Pointer): Int = error("Unexpected software render call in memory-only native fixture")
        override fun mpv_render_context_set_update_callback(context: Pointer, callback: MpvRenderUpdateCallback, data: Pointer?): Unit = error("Unexpected software render call in memory-only native fixture")
        override fun mpv_render_context_update(context: Pointer): Long = error("Unexpected software render call in memory-only native fixture")
        override fun mpv_render_context_render(context: Pointer, params: Pointer): Int = error("Unexpected software render call in memory-only native fixture")
        override fun mpv_render_context_free(context: Pointer): Unit = error("Unexpected software render call in memory-only native fixture")
        override fun mpv_free(data: Pointer) = error("Unexpected playback call")
    }
}
