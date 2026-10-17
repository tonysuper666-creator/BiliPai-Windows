package com.bilipai.desktop.player

import kotlin.test.*
import org.junit.jupiter.api.Test

class DesktopWindowsAudioOutputFocusTest {
    private val parked = DesktopWindowsAudioOutputReleaseReadback(0, "null", 0, "null", 0, "floatp")
    private val unloaded = DesktopWindowsAudioOutputReleaseReadback(0, "null", -10, null, -10, null)
    private fun DesktopWindowsAudioOutputFocus.intent(actor: DesktopWindowsAudioOutputFocus.Actor,
        session: DesktopWindowsAudioOutputFocus.NativeSession?, version: Long = 1,
    ) = request(actor, version, 7, 3, session).also { if (session != null) assertTrue(activate(it, session)) }

    @Test fun `unknown initializing peer blocks real output until native release readback`() {
        val focus = DesktopWindowsAudioOutputFocus()
        val video = focus.registerActor(); val music = focus.registerActor()
        val v = focus.registerSession(video); val m = focus.registerSession(music)
        val intent = focus.intent(music, m)
        assertFalse(focus.beginOpen(intent, m, 1))
        assertTrue(focus.acknowledgeReleased(v, 1, unloaded))
        assertTrue(focus.beginOpen(intent, m, 2))
        assertTrue(focus.holds(intent, m))
    }
    @Test fun `pause and configured null with live wasapi do not release a device`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val s = focus.registerSession(a)
        assertFalse(focus.acknowledgeReleased(s, 1, parked.copy(driver = "wasapi")))
        assertFalse(focus.acknowledgeReleased(s, 2, parked.copy(device = "wasapi")))
        assertEquals(1, focus.observation().unreleasedSessions)
        assertTrue(focus.acknowledgeReleased(s, 3, parked))
    }
    @Test fun `arbitrary property errors missing options and transient no AO are not release proofs`() {
        val invalid = listOf(unloaded.copy(deviceCode = -3), unloaded.copy(device = null),
            unloaded.copy(device = "auto"), unloaded.copy(driverCode = -3),
            unloaded.copy(formatCode = -3), unloaded.copy(format = "s16"),
            unloaded.copy(driver = "null"), unloaded.copy(driverCode = 0))
        invalid.forEach { assertFalse(it.confirmsNoPhysicalOutput, it.toString()) }
        assertTrue(unloaded.confirmsNoPhysicalOutput); assertTrue(parked.confirmsNoPhysicalOutput)
    }
    @Test fun `old cleanup cannot clear a newer intent even for identical source values`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val s = focus.registerSession(a)
        val old = focus.intent(a, s); val latest = focus.intent(a, s)
        focus.cancel(old)
        assertTrue(focus.isCurrent(latest, s)); assertFalse(focus.isCurrent(old, s))
        assertNotEquals(old.sequence, latest.sequence)
        assertFalse(focus.beginOpen(old, s, 1)); assertTrue(focus.beginOpen(latest, s, 2))
    }
    @Test fun `open attempt reserves actual resource even after a newer actor requests it`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val b = focus.registerActor()
        val first = focus.registerSession(a); val second = focus.registerSession(b)
        focus.acknowledgeReleased(second, 1, parked)
        val one = focus.intent(a, first); assertTrue(focus.beginOpen(one, first, 1))
        val two = focus.intent(b, second)
        assertFalse(focus.holds(one, first)); assertFalse(focus.beginOpen(two, second, 2))
        assertTrue(focus.acknowledgeReleased(first, 2, parked))
        assertTrue(focus.beginOpen(two, second, 3))
    }
    @Test fun `release sampled before a newer begin cannot retire its resource`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val s = focus.registerSession(a)
        val intent = focus.intent(a, s); assertTrue(focus.beginOpen(intent, s, 12))
        assertFalse(focus.acknowledgeReleased(s, 11, parked)); assertFalse(focus.acknowledgeReleased(s, 12, parked))
        assertTrue(focus.holds(intent, s))
        assertTrue(focus.acknowledgeReleased(s, 13, parked)); assertFalse(focus.holds(intent, s))
    }
    @Test fun `closing and actor retirement preserve held output until actual destruction`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val b = focus.registerActor()
        val old = focus.registerSession(a); val next = focus.registerSession(b)
        focus.acknowledgeReleased(next, 1, parked)
        val one = focus.intent(a, old); assertTrue(focus.beginOpen(one, old, 1))
        focus.markClosing(old); focus.retire(a)
        val two = focus.intent(b, next); assertFalse(focus.beginOpen(two, next, 2))
        focus.acknowledgeDestroyed(old); assertTrue(focus.beginOpen(two, next, 3))
        assertEquals(1, focus.observation().actors)
    }
    @Test fun `a closed core can acknowledge resource release without media ownership`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val b = focus.registerActor()
        val old = focus.registerSession(a); val next = focus.registerSession(b)
        val two = focus.intent(b, next); focus.markClosing(old); focus.retire(a)
        assertTrue(focus.acknowledgeReleased(old, 1, parked)); assertTrue(focus.beginOpen(two, next, 1))
    }
    @Test fun `late old surface attachment cannot steal newer music intent`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val b = focus.registerActor()
        val old = focus.intent(a, null); val latest = focus.intent(b, focus.registerSession(b))
        val surface = focus.registerSession(a)
        assertNull(focus.attach(old, surface))
        assertEquals(latest.sequence, focus.observation().desiredSequence)
    }
    @Test fun `surface replacement waits for original core even within the same actor`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor()
        val old = focus.registerSession(a); val one = focus.intent(a, old)
        assertTrue(focus.beginOpen(one, old, 1))
        val next = focus.registerSession(a)
        assertNull(focus.attach(one, next))
        focus.markClosing(old)
        val two = assertNotNull(focus.attach(one, next))
        assertFalse(focus.beginOpen(two, next, 1))
        focus.acknowledgeDestroyed(old); assertTrue(focus.beginOpen(two, next, 2))
        assertFalse(focus.isCurrent(one, old))
    }
    @Test fun `destroyed handles cannot resurrect or acknowledge a replacement core`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val old = focus.registerSession(a)
        focus.acknowledgeDestroyed(old); val next = focus.registerSession(a)
        assertFalse(focus.acknowledgeReleased(old, 100, parked))
        assertEquals(1, focus.observation().unreleasedSessions)
        val intent = focus.intent(a, next)
        assertFalse(focus.beginOpen(intent, old, 101)); assertTrue(focus.beginOpen(intent, next, 1))
    }
    @Test fun `foreign broker actors sessions and intents cannot acquire or release resources`() {
        val left = DesktopWindowsAudioOutputFocus(); val right = DesktopWindowsAudioOutputFocus()
        val a = left.registerActor(); val s = left.registerSession(a); val one = left.intent(a, s)
        assertFailsWith<IllegalStateException> { right.registerSession(a) }
        assertFailsWith<IllegalStateException> { right.intent(a, s) }
        assertFalse(right.isCurrent(one, s)); assertFalse(right.beginOpen(one, s, 1))
        assertFalse(right.acknowledgeReleased(s, 1, parked)); assertNull(right.attach(one, s))
    }
    @Test fun `new source on same live core changes the intent without borrowing open success`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val s = focus.registerSession(a)
        val old = focus.intent(a, s); assertTrue(focus.beginOpen(old, s, 1))
        val next = focus.intent(a, s, 2)
        assertFalse(focus.holds(next, s)); assertFalse(focus.holds(old, s))
        assertTrue(focus.beginOpen(next, s, 2)); assertTrue(focus.holds(next, s))
    }
    @Test fun `actor without a core does not block another player's output`() {
        val focus = DesktopWindowsAudioOutputFocus(); focus.registerActor()
        val a = focus.registerActor(); val s = focus.registerSession(a); val intent = focus.intent(a, s)
        assertTrue(focus.beginOpen(intent, s, 1))
    }
    @Test fun `retired actor cannot request create a new core or cancel another owner`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val b = focus.registerActor()
        val old = focus.intent(a, null); focus.retire(a); val newer = focus.intent(b, focus.registerSession(b))
        focus.cancel(old)
        assertFailsWith<IllegalStateException> { focus.registerSession(a) }
        assertFailsWith<IllegalStateException> { focus.intent(a, null) }
        assertEquals(newer.sequence, focus.observation().desiredSequence)
    }
    @Test fun `queued but not admitted load cannot revoke another actual owner`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val b = focus.registerActor()
        val first = focus.registerSession(a); val second = focus.registerSession(b)
        focus.acknowledgeReleased(second, 1, parked)
        val playing = focus.intent(a, first); assertTrue(focus.beginOpen(playing, first, 1))
        val queued = focus.request(b, 9, 8, 7, second)
        assertTrue(focus.holds(playing, first)); assertFalse(focus.beginOpen(queued, second, 2))
        focus.cancel(queued); assertTrue(focus.holds(playing, first))
    }
    @Test fun `late native admission cannot activate an earlier logical request`() {
        val focus = DesktopWindowsAudioOutputFocus(); val a = focus.registerActor(); val b = focus.registerActor()
        val early = focus.request(a, 1, 1, 1, null)
        val newer = focus.intent(b, focus.registerSession(b))
        assertNull(focus.attach(early, focus.registerSession(a)))
        assertEquals(newer.sequence, focus.observation().desiredSequence)
    }
}
