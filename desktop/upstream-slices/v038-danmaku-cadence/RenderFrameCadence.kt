package com.bytedance.danmaku.render.engine

/** Absolute deadlines avoid dropping to 45fps on a 90Hz panel or drifting at 120Hz. */
internal class RenderFrameCadence {
    private var nextFrameNs = 0L

    fun shouldRender(frameTimeNs: Long): Boolean =
        nextFrameNs == 0L || frameTimeNs + VSYNC_TOLERANCE_NS >= nextFrameNs

    fun onFrame(frameTimeNs: Long) {
        if (nextFrameNs == 0L || frameTimeNs - nextFrameNs > FRAME_INTERVAL_NS * 4) {
            nextFrameNs = frameTimeNs + FRAME_INTERVAL_NS
        } else {
            do { nextFrameNs += FRAME_INTERVAL_NS } while (nextFrameNs <= frameTimeNs + VSYNC_TOLERANCE_NS)
        }
    }

    fun reset() { nextFrameNs = 0L }

    companion object {
        const val FRAME_INTERVAL_NS = 1_000_000_000L / 60L
        private const val VSYNC_TOLERANCE_NS = 500_000L
    }
}
