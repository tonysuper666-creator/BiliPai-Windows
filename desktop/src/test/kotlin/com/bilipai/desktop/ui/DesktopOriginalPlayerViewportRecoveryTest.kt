package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.ui.components.cancelPlayerViewViewportRefresh
import com.android.purebilibili.feature.video.ui.components.schedulePlayerViewViewportRefresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the retained original recovery controller without opening a window. */
class DesktopOriginalPlayerViewportRecoveryTest {
    private class Viewport : DesktopOriginalPlayerViewportPort {
        override var resizeMode = 0
        override var width = 1920
        override var height = 1080
        override var isAttached = true
        override var playbackIdentity: Any? = Any()
        override var refreshController: DesktopOriginalViewportRefreshCancellation? = null
        private val observers = linkedSetOf<DesktopOriginalViewportAttachmentObserver>()
        private val callbacks = linkedSetOf<Runnable>()
        var layoutRequests = 0
        val pendingFrames get() = callbacks.size
        override fun addAttachmentObserver(observer: DesktopOriginalViewportAttachmentObserver) {
            observers.add(observer)
        }
        override fun requestLayout() { layoutRequests++ }
        override fun requestContentLayout() = Unit
        override fun invalidate() = Unit
        override fun post(block: () -> Unit) = block()
        override fun postOnFrame(block: () -> Unit) = postOnFrame(Runnable(block))
        override fun postOnFrame(callback: Runnable) { callbacks.add(callback) }
        override fun cancelFrameCallback(callback: Runnable) { callbacks.remove(callback) }
        fun frame() {
            val batch = callbacks.toList()
            callbacks.removeAll(batch.toSet())
            batch.forEach(Runnable::run)
        }
        fun detach() {
            isAttached = false
            observers.toList().forEach { it.onViewportDetached(this) }
        }
        fun attach() {
            isAttached = true
            observers.toList().forEach { it.onViewportAttached(this) }
        }
    }

    @Test fun latestResizeSupersedesQueuedRecovery() {
        val viewport = Viewport()
        schedulePlayerViewViewportRefresh(viewport, 1, 1280, 720, 1280, 720)
        schedulePlayerViewViewportRefresh(viewport, 0, 1920, 1080, 1920, 1080)
        assertEquals(1, viewport.pendingFrames)
        viewport.frame()
        assertEquals(0, viewport.resizeMode)
        assertEquals(0, viewport.pendingFrames)
    }

    @Test fun completedKnownViewportDoesNotQueueAgain() {
        val viewport = Viewport()
        schedulePlayerViewViewportRefresh(viewport, 0, 1920, 1080, 1920, 1080)
        viewport.frame()
        val previous = viewport.layoutRequests
        schedulePlayerViewViewportRefresh(viewport, 0, 1920, 1080, 1920, 1080)
        assertEquals(0, viewport.pendingFrames)
        assertEquals(previous, viewport.layoutRequests)
    }

    @Test fun nativeSourceReplacementInvalidatesCompletedRequest() {
        val viewport = Viewport()
        schedulePlayerViewViewportRefresh(viewport, 0, 1920, 1080, 1920, 1080)
        viewport.frame()
        viewport.playbackIdentity = Any()
        schedulePlayerViewViewportRefresh(viewport, 0, 1920, 1080, 1920, 1080)
        assertEquals(1, viewport.pendingFrames)
    }

    @Test fun detachCancelsPendingAndAttachRestoresLatestRequest() {
        val viewport = Viewport()
        schedulePlayerViewViewportRefresh(viewport, 0, 1920, 1080, 1920, 1080)
        viewport.detach()
        assertEquals(0, viewport.pendingFrames)
        viewport.attach()
        assertEquals(1, viewport.pendingFrames)
        viewport.frame()
        assertEquals(0, viewport.pendingFrames)
    }

    @Test fun persistentSizeMismatchGetsOnlyOneFollowUp() {
        val viewport = Viewport()
        schedulePlayerViewViewportRefresh(viewport, 0, 1280, 720, 1920, 1080)
        viewport.frame()
        assertEquals(1, viewport.pendingFrames)
        viewport.frame()
        assertEquals(0, viewport.pendingFrames)
        assertTrue(viewport.layoutRequests > 0)
    }

    @Test fun cancellationAndUnknownSizeDoNotRetainCompletedGeometry() {
        val viewport = Viewport()
        schedulePlayerViewViewportRefresh(viewport, 0)
        cancelPlayerViewViewportRefresh(viewport)
        assertEquals(0, viewport.pendingFrames)
        schedulePlayerViewViewportRefresh(viewport, 0)
        viewport.frame()
        schedulePlayerViewViewportRefresh(viewport, 0)
        assertEquals(1, viewport.pendingFrames)
    }
}
