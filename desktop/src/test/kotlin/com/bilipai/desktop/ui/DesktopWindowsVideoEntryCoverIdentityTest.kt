package com.bilipai.desktop.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse

class DesktopWindowsVideoEntryCoverIdentityTest {
    private data class ValueEqualSubject(val value: String)
    private val assembly = Any()
    private val entry = ValueEqualSubject("same entry value")
    private val request = ValueEqualSubject("same BV/CID")
    private val publication = ValueEqualSubject("same source version")
    private val peer = ValueEqualSubject("same HWND")
    private fun key(
        owner: Any = assembly, route: Any = entry, load: Any? = request, token: Long = 8L,
        source: Any? = publication, revision: Long? = 12L, canvas: Any? = peer,
    ) = DesktopWindowsVideoEntryCoverIdentity(owner, route, load, token, source, revision, canvas)

    @Test
    fun aRecompositionOfTheExactSourceKeepsTheRevealTimeline() {
        val first = key()
        val next = key()
        assertEquals(first, next)
        assertEquals(first.hashCode(), next.hashCode())
        assertEquals(1, setOf(first, next).size)
    }

    @Test
    fun valueEqualReentryAndRequestCannotReuseThePreviousReveal() {
        assertEquals(entry, ValueEqualSubject(entry.value))
        assertEquals(request, ValueEqualSubject(request.value))
        assertNotEquals(key(), key(route = ValueEqualSubject(entry.value)))
        assertNotEquals(key(), key(load = ValueEqualSubject(request.value)))
    }

    @Test
    fun retainedSourceVersionAndRecycledHwndAreInsufficient() {
        assertEquals(publication, ValueEqualSubject(publication.value))
        assertEquals(peer, ValueEqualSubject(peer.value))
        assertNotEquals(key(), key(source = ValueEqualSubject(publication.value)))
        assertNotEquals(key(), key(canvas = ValueEqualSubject(peer.value)))
        assertNotEquals(key(), key(owner = Any()))
    }

    @Test
    fun newRequestAndRecoveryRevisionRetireThePriorTimeline() {
        assertNotEquals(key(), key(token = 9L))
        assertNotEquals(key(), key(revision = 13L))
        assertNotEquals(key(), key(source = null, revision = null, canvas = null))
        assertEquals(key(load = null, source = null, revision = null, canvas = null),
            key(load = null, source = null, revision = null, canvas = null))
    }

    @Test
    fun diagnosticsDoNotRenderAnyCapturedSubject() {
        val sensitive = object { override fun toString() = "signed-url-or-account-data" }
        val identity = key(owner = sensitive, route = sensitive, load = sensitive, source = sensitive, canvas = sensitive)
        assertFalse(identity.toString().contains("signed-url-or-account-data"))
        assertEquals("DesktopWindowsVideoEntryCoverIdentity(requestToken=8, revision=12)", identity.toString())
    }
}
