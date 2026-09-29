package com.bilipai.desktop.player

/** Checks the packaged client library before an updated process acknowledges startup. */
object MpvStartupProbe {
    fun verify() = verify(MpvNative.load())

    internal fun verify(native: MpvNative) {
        val handle = native.mpv_create() ?: error("Unable to create the native player for startup verification.")
        try {
            // This checks the native library independently of the lazily mounted
            // video Canvas. Rendering and audio output are tested by PlayerSelfTest.
            mapOf("config" to "no", "load-scripts" to "no", "terminal" to "no", "vo" to "null", "ao" to "null")
                .forEach { (name, value) ->
                    val result = native.mpv_set_option_string(handle, name, value)
                    check(result >= 0) { "Native startup verification $name: ${native.mpv_error_string(result)}" }
                }
            val result = native.mpv_initialize(handle)
            check(result >= 0) { "Native startup verification initialize: ${native.mpv_error_string(result)}" }
        } finally {
            native.mpv_terminate_destroy(handle)
        }
    }
}
