package com.bilipai.desktop.ui

import com.android.purebilibili.core.events.BrandSuccessEvents
import com.android.purebilibili.core.events.BrandSuccessFeedback
import com.android.purebilibili.core.events.BrandSuccessKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Upstream ActionRepository's success announcements reach the Root instance of the calling origin, or fail loudly. */
class DesktopUpstreamBrandSuccessTest {
    @Test fun announcementsReachTheRootInstanceWithTheCallersOrigin(): Unit = runBlocking {
        val events = BrandSuccessEvents { true }
        val received = java.util.concurrent.CopyOnWriteArrayList<BrandSuccessFeedback>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { events.events.collect { received += it } }
        val caller = Job()
        val origin = DesktopBrandSuccessOrigin(caller, { true }) { action -> action(); true }
        withContext(DesktopUpstreamBrandSuccessContext(events, origin)) {
            DesktopUpstreamBrandSuccess.followChanged(following = true)
            DesktopUpstreamBrandSuccess.followChanged(following = false, detail = "UP")
            DesktopUpstreamBrandSuccess.favoriteSaved()
        }
        assertEquals(listOf(BrandSuccessKind.FOLLOW, BrandSuccessKind.UNFOLLOW, BrandSuccessKind.FAVORITE), received.map { it.kind })
        assertEquals("UP", received[1].detail)
        received.forEach { assertSame(origin, it.origin) }
        collector.cancelAndJoin(); caller.cancel(); events.close()
    }

    @Test fun aRetiredOriginCelebratesNothing(): Unit = runBlocking {
        val events = BrandSuccessEvents { true }
        val received = java.util.concurrent.CopyOnWriteArrayList<BrandSuccessFeedback>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { events.events.collect { received += it } }
        val caller = Job().apply { cancel() }
        val origin = DesktopBrandSuccessOrigin(caller, { true }) { action -> action(); true }
        withContext(DesktopUpstreamBrandSuccessContext(events, origin)) { DesktopUpstreamBrandSuccess.favoriteSaved() }
        assertTrue(received.isEmpty())
        collector.cancelAndJoin(); events.close()
    }

    @Test fun anAnnouncementWithoutTheDesktopContextFailsLoudly(): Unit = runBlocking {
        val failure = assertFailsWith<IllegalStateException> { DesktopUpstreamBrandSuccess.followChanged(following = true) }
        assertTrue("DesktopUpstreamBrandSuccessContext" in failure.message.orEmpty())
        assertFailsWith<IllegalStateException> { DesktopUpstreamBrandSuccess.favoriteSaved() }
    }
}
